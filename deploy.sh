#!/usr/bin/env bash
# 代码生成系统一键部署脚本
# 用法：
#   ./deploy.sh          # 构建并启动
#   ./deploy.sh down     # 停止并移除
#   ./deploy.sh rebuild  # 强制重新构建镜像
set -euo pipefail

cd "$(dirname "$0")"

COMPOSE_FILE="docker-compose.yml"

echo "==> 检查环境变量文件 .env"
if [ ! -f ".env" ]; then
    echo "未找到 .env，正在从 .env.example 复制。请编辑 .env 填入 LLM_API_KEY 等真实值。"
    cp .env.example .env
    echo "已生成 .env，请打开编辑后重新运行：vim .env && ./deploy.sh"
    exit 1
fi

echo "==> 检查沙箱镜像 code-sandbox:latest"
if ! docker image inspect code-sandbox:latest >/dev/null 2>&1; then
    echo "未找到 code-sandbox:latest，开始构建（含 Maven 依赖缓存，耗时较长）..."
    (cd sandbox && docker build -t code-sandbox:latest .)
else
    echo "沙箱镜像已存在，跳过构建。如需重建：cd sandbox && docker build -t code-sandbox:latest ."
fi

case "${1:-up}" in
    up)
        echo "==> 构建并启动服务"
        docker compose -f "$COMPOSE_FILE" up -d --build
        echo "==> 服务已启动："
        echo "   前端:  http://localhost:${FRONTEND_PORT:-8080}"
        echo "   后端:  http://localhost:${BACKEND_PORT:-8123}/api"
        ;;
    down)
        echo "==> 停止并移除服务"
        docker compose -f "$COMPOSE_FILE" down
        ;;
    rebuild)
        echo "==> 强制重新构建镜像并启动"
        docker compose -f "$COMPOSE_FILE" up -d --build --force-recreate
        ;;
    logs)
        docker compose -f "$COMPOSE_FILE" logs -f
        ;;
    *)
        echo "用法: $0 [up|down|rebuild|logs]"
        exit 1
        ;;
esac
