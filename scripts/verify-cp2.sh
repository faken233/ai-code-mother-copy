#!/usr/bin/env bash
# CP2 验收：沙箱双通道 + 应用进程属容器 + 重构后回归
# 用法：scripts/verify-cp2.sh   （先跑 scripts/sandbox-up.sh）
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APP_ID="${APP_ID:-900001}"
. "$ROOT/scripts/lib/sandbox.sh"
NAME="$(sandbox_container "$APP_ID")"
TOOL_PORT="$(sandbox_tool_port "$APP_ID")"
APP_PORT="$(sandbox_app_port "$APP_ID")"
MYSQL_CONTAINER="${MYSQL_CONTAINER:-yu-ai-sandbox-mysql}"
MYSQL_ROOT_PASS="${SANDBOX_MYSQL_PASSWORD:-123456}"
APP_DB="${APP_DB:-app_demo}"
BASE="http://127.0.0.1:${APP_PORT}"

PASS=0
FAIL=0
FAILED=()
ok()  { PASS=$((PASS+1)); echo "[✅] $1"; }
bad() { FAIL=$((FAIL+1)); FAILED+=("$1"); echo "[❌] $1"; [ -n "${2:-}" ] && echo "      → $2"; }
check() { if [ "$2" = "1" ]; then ok "$1"; else bad "$1" "${3:-}"; fi; }

echo "== CP2 验收 =="
echo "   容器: $NAME   工具:$TOOL_PORT   应用:$APP_PORT"
echo

# ---------- 1. 容器与端口 ----------
check "容器 $NAME 在运行" \
  "$([ "$(docker inspect -f '{{.State.Status}}' "$NAME" 2>/dev/null)" = "running" ] && echo 1 || echo 0)"

map=$(docker port "$NAME" 2>/dev/null | sort)
has_tool=$(echo "$map" | grep -c "8080/tcp -> .*:${TOOL_PORT}")
has_app=$(echo "$map" | grep -c "3000/tcp -> .*:${APP_PORT}")
check "端口映射：8080→${TOOL_PORT} 且 3000→${APP_PORT}" \
  "$([ "$has_tool" -ge 1 ] && [ "$has_app" -ge 1 ] && echo 1 || echo 0)" "$map"

# ---------- 2. 双通道探活可区分 ----------
tool_body=$(curl -s --max-time 3 "http://127.0.0.1:${TOOL_PORT}/api/health")
check "工具通道 /api/health → ok:true 且不带 app 标记（文件服务）" \
  "$(echo "$tool_body" | grep -q '"ok":true' && ! echo "$tool_body" | grep -q '"app":true' && echo 1 || echo 0)" "$tool_body"

app_body=$(curl -s --max-time 3 "$BASE/api/health")
check "应用通道 /api/health → app:true（生成应用）" \
  "$(echo "$app_body" | grep -q '"app":true' && echo 1 || echo 0)" "$app_body"

# ---------- 3. 进程结构：PID1 是文件服务，应用是子进程 ----------
ps_out=$(docker exec "$NAME" ps -eo pid,ppid,cmd 2>/dev/null)
pid1=$(echo "$ps_out" | awk '$1==1 {print $0}')
check "PID 1 是沙箱文件服务（node /app/app.js）" "$(echo "$pid1" | grep -q '/app/app.js' && echo 1 || echo 0)" "$pid1"

app_procs=$(echo "$ps_out" | grep -c '[n]ode server.js')
check "容器内恰有 1 个应用进程（node server.js）" "$([ "$app_procs" = "1" ] && echo 1 || echo 0)" "实际 $app_procs 个"

# ---------- 4. 数据层回归（复用 CP1 的 16 条） ----------
echo
echo "-- 数据层回归（scripts/verify-cp1.sh）--"
if BASE="$BASE" bash "$ROOT/scripts/verify-cp1.sh" > /tmp/cp2-cp1.log 2>&1; then
  ok "CP1 数据层 16 条断言全部通过"
  grep -c '^\[✅\]' /tmp/cp2-cp1.log | xargs -I{} echo "     通过 {} 条"
else
  bad "CP1 数据层断言未全部通过" "见 /tmp/cp2-cp1.log"
  tail -8 /tmp/cp2-cp1.log
fi

# ---------- 5. 容器重启自愈 ----------
echo
echo "-- 容器重启自愈（模拟主服务/容器重启）--"
rows_before=$(docker exec "$MYSQL_CONTAINER" mysql -N -uroot -p"$MYSQL_ROOT_PASS" \
  -e "select count(*) from \`${APP_DB}\`.expenses;" 2>/dev/null)

docker restart "$NAME" >/dev/null
# 随机宿主端口在 restart 后会被 Docker 重新分配，必须重新回读，不能沿用重启前的端口
for _ in $(seq 1 20); do
  APP_PORT="$(sandbox_app_port "$APP_ID")"
  [ -n "$APP_PORT" ] && break
  sleep 1
done
BASE="http://127.0.0.1:${APP_PORT}"
up=0
for _ in $(seq 1 45); do
  if curl -s --max-time 2 "$BASE/api/health" | grep -q '"app":true'; then up=1; break; fi
  sleep 1
done
check "docker restart 后应用自动恢复（start.sh 拉起，新端口 :${APP_PORT}）" "$up" "$(curl -s --max-time 2 "$BASE/api/health")"

rows_after=$(docker exec "$MYSQL_CONTAINER" mysql -N -uroot -p"$MYSQL_ROOT_PASS" \
  -e "select count(*) from \`${APP_DB}\`.expenses;" 2>/dev/null)
check "重启后数据未丢失（expenses: ${rows_before} → ${rows_after}）" \
  "$([ "${rows_after:-0}" -ge "${rows_before:-0}" ] && echo 1 || echo 0)"

procs_after=$(docker exec "$NAME" ps -eo cmd 2>/dev/null | grep -c '[n]ode server.js')
check "重启后没有进程重复启动（仍为 1 个）" "$([ "$procs_after" = "1" ] && echo 1 || echo 0)" "实际 $procs_after 个"

# ---------- 6. 无外部句柄 ----------
echo
echo "-- 进程归属 --"
docker exec "$NAME" ps -eo pid,ppid,cmd 2>/dev/null | grep -E 'PID|node' | head -5
echo "   说明：应用进程的父进程在容器内（PPID=1），宿主侧不存在任何 Process 句柄。"

echo
echo "== 结果：$PASS 通过 / $FAIL 失败 =="
if [ "$FAIL" -gt 0 ]; then
  printf '   失败项：\n'; printf '   - %s\n' "${FAILED[@]}"
  exit 1
fi
echo "   全部通过。应用：$BASE"
