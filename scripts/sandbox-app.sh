#!/usr/bin/env bash
# 应用进程生命周期（容器内）：start / stop / restart / status / logs
# 用法：scripts/sandbox-app.sh start
set -euo pipefail

APP_ID="${APP_ID:-900001}"
ACTION="${1:-status}"

# 容器名与端口都来自 docker（见 scripts/lib/sandbox.sh）
. "$(cd "$(dirname "$0")" && pwd)/lib/sandbox.sh"
NAME="$(sandbox_container "$APP_ID")"
TOOL_PORT="$(sandbox_tool_port "$APP_ID")"
APP_PORT="$(sandbox_app_port "$APP_ID")"

if [ -z "${APP_PORT}" ]; then
  echo "❌ 读不到 ${NAME} 的端口映射，容器可能不存在（先跑 scripts/sandbox-up.sh 或点一次预览）" >&2
  exit 1
fi

app_health() { curl -s --max-time 2 "http://127.0.0.1:${APP_PORT}/api/health" 2>/dev/null || true; }

case "$ACTION" in
  start)
    if app_health | grep -q '"app":true'; then
      echo "应用已在运行：http://localhost:${APP_PORT}"
      exit 0
    fi
    docker exec -d "$NAME" sh -c 'cd /workspace && exec node server.js'
    for _ in $(seq 1 30); do app_health | grep -q '"app":true' && break; sleep 1; done
    app_health | grep -q '"app":true' && echo "✅ 已启动：http://localhost:${APP_PORT}" || { echo "❌ 启动失败"; docker exec "$NAME" tail -20 /tmp/app.log || true; exit 1; }
    ;;
  stop)
    docker exec "$NAME" sh -c 'pkill -f "node server.js" || true'
    echo "已停止应用进程（容器与工具通道仍在）"
    ;;
  restart)
    "$0" stop; sleep 1; "$0" start
    ;;
  status)
    echo "容器：$NAME"
    docker ps --filter "name=^/${NAME}$" --format '  状态: {{.Status}}  端口: {{.Ports}}'
    echo "工具通道(:${TOOL_PORT})：$(curl -s --max-time 2 "http://127.0.0.1:${TOOL_PORT}/api/health" || echo '无响应')"
    echo "应用通道(:${APP_PORT})：$(app_health || echo '无响应')"
    echo "容器内进程："
    docker exec "$NAME" ps -eo pid,ppid,cmd 2>/dev/null | grep -E 'node|PID' | head -5
    ;;
  logs)
    docker exec "$NAME" tail -n 50 /tmp/app.log
    ;;
  *)
    echo "未知动作：${ACTION}（start/stop/restart/status/logs）" >&2
    exit 2
    ;;
esac
