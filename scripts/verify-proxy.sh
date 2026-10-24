#!/usr/bin/env bash
# 代理验收：路由（Redis 动态 + 静态别名）与流式转发
#
# 用法：scripts/verify-proxy.sh
# 前置：
#   1) scripts/proxy-up.sh 已启动代理（127.0.0.1:8124）
#   2) 至少有一个应用的预览路由在 Redis 里（平台点一次「预览」即可）
#
# 覆盖：存活、Host 路由、未知 Host、上游不可达、流式不缓冲、请求头过滤与透传、请求体透传、内容长度一致
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PROXY="${PROXY:-http://127.0.0.1:8124}"
ECHO_PORT="${ECHO_PORT:-18124}"
REDIS_CONTAINER="${REDIS_CONTAINER:-yu-ai-platform-redis}"
TMP="$(mktemp -d)"
ECHO_PID=""

PASS=0
FAIL=0
FAILED=()

ok()  { PASS=$((PASS+1)); echo "[✅] $1"; }
bad() { FAIL=$((FAIL+1)); FAILED+=("$1"); echo "[❌] $1"; [ -n "${2:-}" ] && echo "      → $2"; }
check() { if [ "$2" = "1" ]; then ok "$1"; else bad "$1" "${3:-}"; fi; }

cleanup() { [ -n "$ECHO_PID" ] && kill "$ECHO_PID" 2>/dev/null; rm -rf "$TMP"; }
trap cleanup EXIT

# 真实预览域名从 Redis 取（主服务写入），避免把某个随机 code 写死在脚本里
discover_host() {
  if [ -n "${APP_HOST:-}" ]; then printf '%s' "$APP_HOST"; return; fi
  # Redis 里可能残留"容器已删"的旧路由（P6 才做 reconcile），所以逐个探活，取第一个真能通的
  local h
  for h in $(docker exec "$REDIS_CONTAINER" redis-cli --scan --pattern 'route:*.pv.localhost' 2>/dev/null \
      | sed 's/^route://'); do
    if curl -s --max-time 3 -H "Host: $h" "$PROXY/api/health" 2>/dev/null | grep -q '"app":true'; then
      printf '%s' "$h"
      return
    fi
  done
}
APP_HOST="$(discover_host)"

echo "== 代理验收 =="
echo "   代理: $PROXY   应用 Host: ${APP_HOST:-（Redis 里暂无路由）}"
echo

# ---------- 1. 代理存活 ----------
health=$(curl -s --max-time 3 "$PROXY/__health" 2>/dev/null || true)
check "代理存活（/__health）" "$(echo "$health" | grep -q '"ok":true' && echo 1 || echo 0)" "$health"

# ---------- 2. Host 路由命中应用 ----------
if [ -z "$APP_HOST" ]; then
  echo "[⏭️ ] 跳过「Host 命中应用」：Redis 里没有 route:* 记录，先点一次「预览」"
else
  app_health=$(curl -s --max-time 10 -H "Host: $APP_HOST" "$PROXY/api/health" 2>/dev/null || true)
  check "Host 命中应用并返回应用健康（${APP_HOST}）" "$(echo "$app_health" | grep -q '"app":true' && echo 1 || echo 0)" "$app_health"
fi

# ---------- 3. 未知 Host → 404 ----------
code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 -H 'Host: nope.pv.localhost' "$PROXY/" 2>/dev/null || echo 000)
check "未知 Host 返回 404" "$([ "$code" = "404" ] && echo 1 || echo 0)" "实际 $code"

# ---------- 4. 上游不可达 → 502 ----------
code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 -H 'Host: dead.pv.localhost' "$PROXY/" 2>/dev/null || echo 000)
check "上游不可达返回 502" "$([ "$code" = "502" ] && echo 1 || echo 0)" "实际 $code"

# ---------- 5. 流式：TTFB 远小于 total，且 5 个事件都在 ----------
curl -sN --max-time 20 "$PROXY/__selftest/stream" -o "$TMP/stream.txt" -w '%{time_starttransfer} %{time_total}' > "$TMP/timing.txt" 2>/dev/null || true
read -r ttfb total < "$TMP/timing.txt" || true
stream_ok=$(awk -v a="${ttfb:-99}" -v b="${total:-0}" 'BEGIN { print (a < 2.0 && b > 4.0) ? 1 : 0 }')
check "流式不缓冲（TTFB ${ttfb:-?}s << total ${total:-?}s）" "$stream_ok" "$(cat "$TMP/timing.txt" 2>/dev/null)"
events=$(grep -c '^data:' "$TMP/stream.txt" 2>/dev/null || echo 0)
check "流式事件完整（5 条）" "$([ "$events" = "5" ] && echo 1 || echo 0)" "实际 $events 条"

# ---------- 6. 回显服务：验证头过滤/透传与请求体 ----------
if (echo >/dev/tcp/127.0.0.1/"$ECHO_PORT") 2>/dev/null; then
  echo "  （端口 $ECHO_PORT 已被占用，跳过回显断言）"
else
  python3 - "$ECHO_PORT" <<'PY' >/dev/null 2>&1 &
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer

class H(BaseHTTPRequestHandler):
    def _read_body(self):
        # 代理以 Transfer-Encoding: chunked 转发请求体（流式），所以这里两种都要能读
        te = (self.headers.get('Transfer-Encoding') or '').lower()
        if 'chunked' in te:
            data = b''
            while True:
                line = self.rfile.readline().strip()
                if not line:
                    continue
                size = int(line.split(b';')[0], 16)
                if size == 0:
                    self.rfile.readline()
                    break
                data += self.rfile.read(size)
                self.rfile.readline()
            return data
        n = int(self.headers.get('Content-Length') or 0)
        return self.rfile.read(n) if n else b''

    def _dump(self):
        body = self._read_body().decode('utf-8', 'replace')
        out = ("REQUEST\n" + self.command + " " + self.path
               + "\nHEADERS\n" + str(self.headers) + "\nBODY\n" + body).encode()
        self.send_response(200)
        self.send_header('Content-Type', 'text/plain; charset=utf-8')
        self.send_header('Content-Length', str(len(out)))
        self.end_headers()
        self.wfile.write(out)

    do_GET = _dump
    do_POST = _dump

    def log_message(self, *args):
        pass

HTTPServer(('127.0.0.1', int(sys.argv[1])), H).serve_forever()
PY
  ECHO_PID=$!
  for _ in $(seq 1 20); do
    (echo >/dev/tcp/127.0.0.1/"$ECHO_PORT") 2>/dev/null && break
    sleep 0.2
  done

  echo_resp=$(curl -s --max-time 10 -H 'Host: echo.pv.localhost' \
      -H 'Connection: keep-alive' -H 'TE: trailers' "$PROXY/probe?x=1" 2>/dev/null || true)
  check "X-Forwarded-For 已注入" "$(echo "$echo_resp" | grep -qi 'x-forwarded-for' && echo 1 || echo 0)" "$echo_resp"
  check "X-Forwarded-Host 已注入" "$(echo "$echo_resp" | grep -qi 'x-forwarded-host: echo.pv.localhost' && echo 1 || echo 0)"
  check "逐跳头 Connection 未透传" "$(echo "$echo_resp" | grep -qi '^connection:' && echo 0 || echo 1)" "$(echo "$echo_resp" | head -20)"
  check "逐跳头 TE 未透传" "$(echo "$echo_resp" | grep -qi '^te:' && echo 0 || echo 1)"
  check "查询串已透传" "$(echo "$echo_resp" | grep -q 'GET /probe?x=1' && echo 1 || echo 0)" "$(echo "$echo_resp" | head -3)"

  post_resp=$(curl -s --max-time 10 -X POST -H 'Host: echo.pv.localhost' \
      -H 'Content-Type: application/json' -d '{"hello":"proxy"}' "$PROXY/echo" 2>/dev/null || true)
  check "请求体透传" "$(echo "$post_resp" | grep -q '"hello":"proxy"' && echo 1 || echo 0)" "$post_resp"
  check "Content-Type 透传" "$(echo "$post_resp" | grep -qi 'content-type: application/json' && echo 1 || echo 0)"
fi

# ---------- 7. 静态资源：Content-Length 与实际字节数一致（不截断） ----------
if [ -z "$APP_HOST" ]; then
  echo "[⏭️ ] 跳过「首页响应长度一致」：没有可用的预览路由"
else
  curl -s --max-time 10 -D "$TMP/h.txt" -o "$TMP/b.html" -H "Host: $APP_HOST" "$PROXY/" 2>/dev/null || true
  cl=$(grep -i '^content-length:' "$TMP/h.txt" 2>/dev/null | tr -d '\r' | awk '{print $2}' | head -1)
  size=$(wc -c < "$TMP/b.html" 2>/dev/null | tr -d ' ')
  if [ -n "${cl:-}" ]; then
    check "首页响应长度一致（${cl} vs ${size}）" "$([ "$cl" = "$size" ] && echo 1 || echo 0)"
  else
    check "首页有响应体（无 Content-Length，按 chunked）" "$([ "${size:-0}" -gt 0 ] && echo 1 || echo 0)" "size=$size"
  fi
fi

# ---------- 汇总 ----------
echo
echo "================ 结果 ================"
echo "通过 $PASS 条，失败 $FAIL 条"
if [ "$FAIL" -gt 0 ]; then
  printf '失败项：\n'
  printf '  - %s\n' "${FAILED[@]}"
  exit 1
fi
echo "全部通过 ✅"
