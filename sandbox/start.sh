#!/bin/bash
# 沙箱容器入口脚本
set -e

echo "[code-sandbox] start.sh: 工作目录 $(pwd)"

# 如果传入了命令参数则透传执行（如 mvn / npm），否则启动服务
if [ "$#" -gt 0 ]; then
  exec "$@"
fi

# 一体化应用：容器启动时若工作区已有 server.js，则在后台拉起应用进程。
# 这样"进程属容器"才真正成立——容器重启后应用自己回来，不需要主服务再 exec 一次。
# 环境变量由容器级 -e 注入（APP_PORT/APP_DB/DB_*），这里不做任何推导。
if [ -f /workspace/server.js ]; then
  echo "[code-sandbox] 检测到 /workspace/server.js，后台启动一体化应用"
  (
    cd /workspace
    nohup node server.js >> /tmp/app.log 2>&1 &
    echo $! > /tmp/app.pid
  )
  echo "[code-sandbox] 应用 pid=$(cat /tmp/app.pid 2>/dev/null || echo '?')，日志 /tmp/app.log"
fi

# 默认启动 HTTP 文件服务（工具通道，:8080）
exec node /app/app.js
