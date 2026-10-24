#!/usr/bin/env bash
# P4 验收：工具通道令牌 + 应用重建/重启（对话热更新）
#
# 用法：
#   scripts/verify-p4.sh                 # 自动取第一个 app-pv-* 容器
#   scripts/verify-p4.sh <appId>         # 指定应用
#
# 前置：
#   1) 沙箱镜像已用新代码重建（docker build -t code-sandbox:latest sandbox/）
#   2) 至少有一个预览容器在跑（平台点一次「预览」，或直接 docker run）
#   3) 主服务与容器的 sandbox.tool-token-secret 一致
#
# 覆盖：令牌注入、HMAC 一致性、健康检查免鉴权、无/错令牌 401、正确令牌可调用、
#       重启、增量重建、全量构建、重启后应用健康
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SECRET="${SANDBOX_TOOL_TOKEN_SECRET:-local-dev-tool-token-secret}"
APP_ID="${1:-${APP_ID:-}}"

PASS=0
FAIL=0
FAILED=()

ok()  { PASS=$((PASS+1)); echo "[✅] $1"; }
bad() { FAIL=$((FAIL+1)); FAILED+=("$1"); echo "[❌] $1"; [ -n "${2:-}" ] && echo "      → $2"; }
check() { if [ "$2" = "1" ]; then ok "$1"; else bad "$1" "${3:-}"; fi; }
code_of() { curl -s -o /dev/null -w '%{http_code}' --max-time "${2:-10}" "$1" 2>/dev/null || echo 000; }

if [ -z "$APP_ID" ]; then
  APP_ID="$(docker ps --format '{{.Names}}' 2>/dev/null | sed -n 's/^app-pv-//p' | head -1)"
fi
if [ -z "$APP_ID" ]; then
  echo "❌ 没有运行中的 app-pv-* 容器，先在平台点一次「预览」" >&2
  exit 1
fi

CONTAINER="app-pv-${APP_ID}"
TOOL_PORT="$(docker port "$CONTAINER" 8080/tcp 2>/dev/null | head -1 | sed 's/.*://')"
APP_PORT="$(docker port "$CONTAINER" 3000/tcp 2>/dev/null | head -1 | sed 's/.*://')"
TOKEN="$(printf '%s' "$APP_ID" | openssl dgst -sha256 -hmac "$SECRET" -hex 2>/dev/null | sed 's/^.*= //')"
BASE="http://127.0.0.1:${TOOL_PORT}"
APP_BASE="http://127.0.0.1:${APP_PORT}"
POST_JSON() {
  local path="$1" payload="${2:-}" timeout="${3:-120}"
  [ -z "$payload" ] && payload='{}'
  curl -s --max-time "$timeout" -X POST \
    -H 'Content-Type: application/json' -H "X-Tool-Token: ${TOKEN}" \
    -d "$payload" "$path" 2>/dev/null
}

echo "== P4 验收 =="
echo "   应用: ${APP_ID}   工具通道: ${TOOL_PORT:-?}   应用通道: ${APP_PORT:-?}"
echo

if [ -z "$TOOL_PORT" ]; then
  echo "❌ 容器 ${CONTAINER} 未映射 8080，退出" >&2
  exit 1
fi

# ---------- 1. 令牌注入 ----------
INJECTED="$(docker exec "$CONTAINER" printenv TOOL_TOKEN 2>/dev/null | tr -d '\r\n')"
check "容器已注入 TOOL_TOKEN" "$([ -n "$INJECTED" ] && echo 1 || echo 0)" "printenv 为空"
check "TOOL_TOKEN = HMAC(secret, appId)" "$([ -n "$INJECTED" ] && [ "$INJECTED" = "$TOKEN" ] && echo 1 || echo 0)" "注入=${INJECTED:0:16}… 期望=${TOKEN:0:16}…"

# ---------- 2. 健康检查免鉴权 ----------
health="$(curl -s --max-time 5 "$BASE/api/health" 2>/dev/null || true)"
check "健康检查免鉴权（GET /api/health）" "$(echo "$health" | grep -q '"ok":true' && echo 1 || echo 0)" "$health"

# ---------- 3. 无令牌 / 错令牌 → 401 ----------
c="$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 -X POST \
  -H 'Content-Type: application/json' -d '{}' "$BASE/api/files/list" 2>/dev/null || echo 000)"
check "无令牌拒绝（POST /api/files/list → 401）" "$([ "$c" = "401" ] && echo 1 || echo 0)" "实际 $c"

c="$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 -X POST -H 'X-Tool-Token: wrong-token' \
  -H 'Content-Type: application/json' -d '{}' "$BASE/api/files/list" 2>/dev/null || echo 000)"
check "错令牌拒绝（→ 401）" "$([ "$c" = "401" ] && echo 1 || echo 0)" "实际 $c"

# ---------- 4. 正确令牌可调用 ----------
resp="$(curl -s --max-time 10 -X POST -H "X-Tool-Token: ${TOKEN}" \
  -H 'Content-Type: application/json' -d '{"relativeDirPath":""}' "$BASE/api/files/list" 2>/dev/null || true)"
check "正确令牌可调用（列出目录）" "$(echo "$resp" | grep -q '"ok":true' && echo 1 || echo 0)" "$(echo "$resp" | head -c 200)"

# ---------- 5. 重启 ----------
resp="$(POST_JSON "$BASE/api/app/restart" '{}' 120)"
check "POST /api/app/restart → ready:true" "$(echo "$resp" | grep -q '"ready":true' && echo 1 || echo 0)" "$(echo "$resp" | head -c 300)"

c="$(code_of "$APP_BASE/api/health" 5)"
app_health="$(curl -s --max-time 5 "$APP_BASE/api/health" 2>/dev/null || true)"
check "重启后应用通道健康" "$(echo "$app_health" | grep -q '"app":true' && echo 1 || echo 0)" "code=$c $app_health"

# ---------- 6. 增量重建（不跑 npm build） ----------
resp="$(POST_JSON "$BASE/api/app/rebuild" '{"forceBuild":false}' 240)"
check "重建（forceBuild=false）→ built:false, ready:true" \
  "$(echo "$resp" | grep -q '"built":false' && echo "$resp" | grep -q '"ready":true' && echo 1 || echo 0)" \
  "$(echo "$resp" | head -c 300)"

# ---------- 7. 全量构建 ----------
resp="$(POST_JSON "$BASE/api/app/rebuild" '{"forceBuild":true}' 300)"
check "重建（forceBuild=true）→ built:true, ready:true" \
  "$(echo "$resp" | grep -q '"built":true' && echo "$resp" | grep -q '"ready":true' && echo 1 || echo 0)" \
  "$(echo "$resp" | head -c 300)"

# ---------- 8. 构建产物存在 ----------
check "public/dist/index.html 存在" \
  "$(docker exec "$CONTAINER" test -f /workspace/public/dist/index.html && echo 1 || echo 0)" \
  "构建未产出 index.html"

echo
echo "================ 结果 ================"
echo "   通过 ${PASS} / 失败 ${FAIL}"
if [ "$FAIL" -gt 0 ]; then
  printf '   ❌ %s\n' "${FAILED[@]}"
  exit 1
fi
echo "   ✅ P4 工具通道与热更新验收通过"
