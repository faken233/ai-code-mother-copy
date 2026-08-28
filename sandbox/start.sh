#!/bin/bash
# 沙箱容器入口脚本
set -e

echo "[code-sandbox] start.sh: 工作目录 $(pwd)"

# 如果传入了命令参数则透传执行（如 mvn / npm），否则启动 HTTP 服务
if [ "$#" -gt 0 ]; then
  exec "$@"
fi

# 默认启动 HTTP 服务
exec node /app/app.js
