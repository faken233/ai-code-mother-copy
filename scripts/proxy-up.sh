#!/usr/bin/env bash
# 启动/停止访问入口代理（proxy 模块）
#
# 用法：
#   scripts/proxy-up.sh            # 启动（默认，后台，日志 /tmp/proxy.log）
#   scripts/proxy-up.sh stop       # 停止
#   scripts/proxy-up.sh restart
#   scripts/proxy-up.sh status     # 健康 + Redis 里现有路由
#   scripts/proxy-up.sh logs
#
# 前置：Redis（platform-redis:6379）可用；路由由主服务写入，代理只读。
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ACTION="${1:-start}"
PROXY_PORT="${PROXY_PORT:-8124}"
LOG="${PROXY_LOG:-/tmp/proxy.log}"
PID_FILE="${PROXY_PID_FILE:-/tmp/proxy.pid}"
# 受限环境下 ~/.m2 不可写，使用工程内的独立本地仓库
MAVEN_REPO_LOCAL="${MAVEN_REPO_LOCAL:-$ROOT/.m2repo}"
REDIS_CONTAINER="${REDIS_CONTAINER:-yu-ai-platform-redis}"

health() { curl -s --max-time 2 "http://127.0.0.1:${PROXY_PORT}/__health" 2>/dev/null || true; }

start() {
  if health | grep -q '"ok":true'; then
    echo "代理已在运行：http://127.0.0.1:${PROXY_PORT}"
    return 0
  fi

  echo "启动代理 :${PROXY_PORT}，日志 ${LOG}"
  PROXY_PORT="$PROXY_PORT" \
    nohup mvn -f "$ROOT/proxy/pom.xml" -q -DskipTests -Dmaven.repo.local="$MAVEN_REPO_LOCAL" \
      spring-boot:run > "$LOG" 2>&1 &
  echo $! > "$PID_FILE"

  for _ in $(seq 1 90); do
    health | grep -q '"ok":true' && break
    sleep 1
  done

  if health | grep -q '"ok":true'; then
    echo "✅ 代理就绪：http://127.0.0.1:${PROXY_PORT}/__health"
  else
    echo "❌ 代理未在 90 秒内就绪，最近日志：" >&2
    tail -n 30 "$LOG" >&2 || true
    exit 1
  fi
}

stop() {
  if [ -f "$PID_FILE" ]; then
    kill "$(cat "$PID_FILE")" 2>/dev/null || true
    rm -f "$PID_FILE"
  fi
  # spring-boot:run 会 fork 子进程，按端口兜底清理
  pkill -f "app-proxy" 2>/dev/null || true
  pkill -f "proxy/pom.xml" 2>/dev/null || true
  echo "已停止代理（端口 ${PROXY_PORT}）"
}

status() {
  echo "代理端口：${PROXY_PORT}"
  echo "健康检查：$(health || echo '无响应')"
  echo "Redis 路由："
  docker exec "$REDIS_CONTAINER" redis-cli --scan --pattern 'route:*' 2>/dev/null | head -20 | sed 's/^/  /' || echo "  （读不到，检查 ${REDIS_CONTAINER}）"
}

case "$ACTION" in
  start)   start ;;
  stop)    stop ;;
  restart) stop; sleep 1; start ;;
  status)  status ;;
  logs)    tail -n 80 "$LOG" ;;
  *) echo "未知动作：${ACTION}（start/stop/restart/status/logs）" >&2; exit 2 ;;
esac
