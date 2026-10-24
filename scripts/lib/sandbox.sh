#!/usr/bin/env bash
# 沙箱容器名与宿主端口的唯一来源。
#
# P3 起：
#   - 容器名固定为 app-pv-{appId}（部署容器是 app-dep-{appId}-v{n}）
#   - 宿主端口由内核随机分配，必须用 docker port 回读，不能再按 appId 取模计算
#
# 用法：在脚本里 source 本文件（不要直接执行）
#   . "$(dirname "$0")/lib/sandbox.sh"
#   NAME="$(sandbox_container "$APP_ID")"
#   PORT="$(sandbox_app_port "$APP_ID")"

# 容器名
sandbox_container() {
  echo "app-pv-${1}"
}

# 回读某个容器内端口的宿主映射端口；容器不存在或端口未发布时输出空串
sandbox_host_port() {
  local app_id="$1" container_port="$2"
  local name
  name="$(sandbox_container "$app_id")"
  docker port "$name" "${container_port}/tcp" 2>/dev/null | head -1 | awk -F: '{print $NF}'
}

# 工具通道（容器内 8080）
sandbox_tool_port() {
  sandbox_host_port "$1" 8080
}

# 应用通道（容器内 3000）
sandbox_app_port() {
  sandbox_host_port "$1" 3000
}

# 等待宿主机上的某个端口可建立 TCP 连接（最多 timeout 秒）
sandbox_wait_tcp() {
  local port="$1" timeout="${2:-30}"
  local i
  for i in $(seq 1 "$timeout"); do
    if (echo >/dev/tcp/127.0.0.1/"$port") 2>/dev/null; then
      return 0
    fi
    sleep 1
  done
  return 1
}
