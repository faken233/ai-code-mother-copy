#!/usr/bin/env bash
# CP3 验收：平台接口触发预览 → 经主服务代理拿到页面与 API → 数据层回归
# 用法：scripts/verify-cp3.sh [APP_ID]
# 前置：后端 8123 已启动；平台库里有该 app（FULLSTACK）且 tmp/code_output/fullstack_{appId}/server.js 存在
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PLATFORM="${PLATFORM:-http://127.0.0.1:8123}"
APP_ID="${1:-${APP_ID:-}}"
USER_ACCOUNT="${USER_ACCOUNT:-cp3tester}"
USER_PASSWORD="${USER_PASSWORD:-12345678}"
COOKIE="$(mktemp)"
PASS=0
FAIL=0
FAILED=()

ok()  { PASS=$((PASS+1)); echo "[✅] $1"; }
bad() { FAIL=$((FAIL+1)); FAILED+=("$1"); echo "[❌] $1"; [ -n "${2:-}" ] && echo "      → $2"; }
check() { if [ "$2" = "1" ]; then ok "$1"; else bad "$1" "${3:-}"; fi; }

if [ -z "$APP_ID" ]; then
  echo "用法：scripts/verify-cp3.sh <APP_ID>（或设置环境变量 APP_ID）" >&2
  exit 2
fi

echo "== CP3 验收 =="
echo "   平台: $PLATFORM   appId: $APP_ID"
echo

# ---------- 1. 平台登录 ----------
login=$(curl -s -c "$COOKIE" -X POST "$PLATFORM/api/user/login" \
  -H 'Content-Type: application/json' \
  -d "{\"userAccount\":\"$USER_ACCOUNT\",\"userPassword\":\"$USER_PASSWORD\"}")
echo "$login" | grep -q '"code":0' && login_ok=1 || login_ok=0
check "平台登录成功（${USER_ACCOUNT}）" "$login_ok" "$login"

# ---------- 2. 触发预览 ----------
preview=$(curl -s -b "$COOKIE" -X POST "$PLATFORM/api/app/preview/$APP_ID" -m 180)
url=$(echo "$preview" | python3 -c 'import sys,json;print(json.load(sys.stdin).get("data") or "")' 2>/dev/null)
check "preview 接口返回预览地址" "$([ -n "$url" ] && echo 1 || echo 0)" "$preview"
[ -z "$url" ] && { echo; echo "== 结果：$PASS 通过 / $FAIL 失败 =="; exit 1; }
echo "   预览地址: $url"

# 预览地址统一成 127.0.0.1，避免 localhost 解析差异
BASE="${url/http:\/\/localhost/http://127.0.0.1}"
BASE="${BASE%/}"

# ---------- 3. 页面与静态资源 ----------
page_code=$(curl -s -o /tmp/cp3-page.html -w '%{http_code}' "$BASE/")
page_type=$(curl -s -o /dev/null -w '%{content_type}' "$BASE/")
check "页面 GET / → 200 且是 HTML" \
  "$([ "$page_code" = "200" ] && echo "$page_type" | grep -q 'text/html' && echo 1 || echo 0)" "HTTP $page_code, $page_type"

asset=$(grep -o '\./assets/[^"]*\.js' /tmp/cp3-page.html | head -1)
if [ -n "$asset" ]; then
  asset_code=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/${asset#./}")
  asset_type=$(curl -s -o /dev/null -w '%{content_type}' "$BASE/${asset#./}")
  check "JS 资源经代理 → 200 且 content-type 是 JS（不是 SPA 兜底的 HTML）" \
    "$([ "$asset_code" = "200" ] && echo "$asset_type" | grep -q 'javascript' && echo 1 || echo 0)" "HTTP $asset_code, $asset_type"
else
  bad "页面里找不到 assets/*.js 引用" "$(head -c 200 /tmp/cp3-page.html)"
fi

# ---------- 4. 数据层回归（同一套 16 条，打到代理地址上） ----------
echo
echo "-- 数据层回归（经主服务代理）--"
if BASE="$BASE" bash "$ROOT/scripts/verify-cp1.sh" > /tmp/cp3-cp1.log 2>&1; then
  ok "CP1 数据层 16 条断言全部通过（经代理）"
  grep -c '^\[✅\]' /tmp/cp3-cp1.log | xargs -I{} echo "     通过 {} 条"
else
  bad "CP1 数据层断言未全部通过" "见 /tmp/cp3-cp1.log"
  tail -8 /tmp/cp3-cp1.log
fi

# ---------- 5. 进程与端口 ----------
echo
echo "-- 进程与端口 --"
. "$ROOT/scripts/lib/sandbox.sh"
CONTAINER="$(sandbox_container "$APP_ID")"
ps_out=$(docker exec "$CONTAINER" ps -eo pid,ppid,stat,cmd 2>/dev/null)
app_procs=$(echo "$ps_out" | grep -c '[n]ode server.js')
zombies=$(echo "$ps_out" | grep -c 'defunct')
check "容器内恰有 1 个应用进程、且无僵尸进程" \
  "$([ "$app_procs" = "1" ] && [ "$zombies" = "0" ] && echo 1 || echo 0)" "app=$app_procs zombie=$zombies"

port_map=$(docker port "$CONTAINER" 2>/dev/null | sort)
bound_local=$(echo "$port_map" | grep -c '^.* -> 127\.0\.0\.1:')
total_ports=$(echo "$port_map" | wc -l | tr -d ' ')
check "端口只绑 127.0.0.1，不暴露到局域网" \
  "$([ "$bound_local" = "$total_ports" ] && [ "$total_ports" -ge 2 ] && echo 1 || echo 0)" "$port_map"

echo
echo "== 结果：$PASS 通过 / $FAIL 失败 =="
if [ "$FAIL" -gt 0 ]; then
  printf '   失败项：\n'; printf '   - %s\n' "${FAILED[@]}"
  rm -f "$COOKIE"
  exit 1
fi
rm -f "$COOKIE"
echo "   全部通过。预览地址：$BASE/"
