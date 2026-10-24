#!/usr/bin/env bash
# 起一个"真实形态"的沙箱容器：
#   - 8080 工具通道（文件服务，PID 1）
#   - 3000 应用通道（一体化 node server.js，由 start.sh 后台拉起）
#   - 宿主端口由内核随机分配（-p 127.0.0.1::8080），run 完用 docker port 回读
# 用法：scripts/sandbox-up.sh [--no-build]
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

APP_ID="${APP_ID:-900001}"
WORKSPACE="$ROOT/tmp/demo/expense"

. "$ROOT/scripts/lib/sandbox.sh"
NAME="$(sandbox_container "$APP_ID")"

APP_DB="${APP_DB:-app_demo}"
APP_USER="${APP_DB}"
APP_PASS="${APP_PASS:-demo_pass_change_me}"
JWT_SECRET="${JWT_SECRET:-cp2-local-secret-please-change}"
MYSQL_CONTAINER="${MYSQL_CONTAINER:-yu-ai-sandbox-mysql}"
MYSQL_ROOT_PASS="${SANDBOX_MYSQL_PASSWORD:-123456}"
MYSQL_HOST_PORT="${SANDBOX_MYSQL_PORT:-3307}"

echo "==> 1/5 沙箱 MySQL"
docker compose up -d sandbox-mysql >/dev/null 2>&1
for _ in $(seq 1 30); do
  [ "$(docker inspect -f '{{.State.Health.Status}}' "$MYSQL_CONTAINER" 2>/dev/null || echo none)" = "healthy" ] && break
  sleep 2
done
echo "    $(docker inspect -f '{{.State.Health.Status}}' "$MYSQL_CONTAINER")"

echo "==> 2/5 建库 + 独立账号"
docker exec -i "$MYSQL_CONTAINER" mysql -uroot -p"$MYSQL_ROOT_PASS" 2>/dev/null <<SQL
CREATE DATABASE IF NOT EXISTS \`$APP_DB\` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS '$APP_USER'@'%' IDENTIFIED BY '$APP_PASS';
GRANT ALL PRIVILEGES ON \`$APP_DB\`.* TO '$APP_USER'@'%';
FLUSH PRIVILEGES;
SQL
# 回读校验：命令成功 != 事情做成
rows=$(docker exec "$MYSQL_CONTAINER" mysql -N -uroot -p"$MYSQL_ROOT_PASS" -e \
  "select count(*) from mysql.user where user='$APP_USER';" 2>/dev/null)
[ "$rows" = "1" ] || { echo "❌ 账号创建失败"; exit 1; }
echo "    库=$APP_DB 账号=$APP_USER ✅"

if [ "${1:-}" != "--no-build" ]; then
  echo "==> 3/5 构建（npm install + vite build）"
  bash "$ROOT/scripts/build-sample.sh" >/dev/null
  echo "    $(du -sh "$WORKSPACE/public/dist" | cut -f1) 前端产物"
else
  echo "==> 3/5 跳过构建"
fi

echo "==> 4/5 创建容器 ${NAME}（宿主端口由内核分配，随后回读）"
docker rm -f "$NAME" >/dev/null 2>&1 || true
docker run -d --name "$NAME" \
  -p "127.0.0.1::8080" \
  -p "127.0.0.1::3000" \
  --label "app.id=${APP_ID}" \
  --label "app.kind=preview" \
  --add-host=host.docker.internal:host-gateway \
  -e APP_PORT=3000 \
  -e APP_DB="$APP_DB" \
  -e DB_HOST=host.docker.internal \
  -e DB_PORT="$MYSQL_HOST_PORT" \
  -e DB_USER="$APP_USER" \
  -e DB_PASS="$APP_PASS" \
  -e JWT_SECRET="$JWT_SECRET" \
  -v "$WORKSPACE:/workspace" \
  code-sandbox:latest >/dev/null

TOOL_PORT="$(sandbox_tool_port "$APP_ID")"
APP_PORT="$(sandbox_app_port "$APP_ID")"
[ -n "$TOOL_PORT" ] && [ -n "$APP_PORT" ] || { echo "❌ 读不到端口映射"; exit 1; }
echo "    工具通道 :${TOOL_PORT} -> 8080，应用通道 :${APP_PORT} -> 3000"

echo -n "    等待工具通道"
for _ in $(seq 1 30); do
  curl -fsS "http://127.0.0.1:${TOOL_PORT}/api/health" >/dev/null 2>&1 && break
  echo -n "."
  sleep 1
done
echo " ✅ $(curl -s "http://127.0.0.1:${TOOL_PORT}/api/health" | head -c 80)"

echo -n "    等待应用通道（start.sh 自动拉起）"
for _ in $(seq 1 30); do
  if curl -fsS "http://127.0.0.1:${APP_PORT}/api/health" 2>/dev/null | grep -q '"app":true'; then break; fi
  echo -n "."
  sleep 1
done
echo " ✅ $(curl -s "http://127.0.0.1:${APP_PORT}/api/health" | head -c 80)"

if ! curl -fsS "http://127.0.0.1:${APP_PORT}/api/health" 2>/dev/null | grep -q '"app":true'; then
  echo "    start.sh 未拉起应用，回退到 docker exec -d"
  docker exec -d "$NAME" sh -c 'cd /workspace && exec node server.js'
fi

echo "==> 5/5 完成"
echo "    应用：http://localhost:${APP_PORT}"
echo "    工具：http://localhost:${TOOL_PORT}/api/health"
echo "    日志：docker exec ${NAME} tail -f /tmp/app.log"
