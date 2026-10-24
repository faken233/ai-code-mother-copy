#!/usr/bin/env bash
# 本地开发启动后端（IDE 外方式）
# 从 .env 加载环境变量后启动 Spring Boot，端口默认 8123（与 compose 一致）
#
# 用法：
#   ./run-local.sh            # 启动后端（默认 8123）
#   ./run-local.sh --debug   # 带调试参数
set -euo pipefail

cd "$(dirname "$0")"

# 加载 .env（若存在）
# 注意：不能直接 source .env —— .env 是 key-value 文件，值里的 < > & 空格等
# 会被 bash 当作 shell 语法（重定向/后台符），导致语法错误或变量被吞。
# 这里逐行解析，按第一个 = 切分 key/value，只导出，不执行任何内容。
if [ -f ".env" ]; then
  echo "==> 加载 .env 环境变量"
  while IFS= read -r line || [ -n "$line" ]; do
    # 去除行首空白
    line="${line#"${line%%[![:space:]]*}"}"
    # 跳过空行与注释行
    case "$line" in
      ''|\#*) continue ;;
    esac
    key="${line%%=*}"
    value="${line#*=}"
    # 去除值两侧的成对引号（如 "a b c" 或 'a b c'）
    case "$value" in
      \"*\"|\'*\') value="${value:1:${#value}-2}" ;;
    esac
    export "$key=$value"
  done < .env
fi

# 本地默认后端端口 8123（compose 也是 8123，端口冲突请自行错开运行）
export SERVER_PORT="${SERVER_PORT:-8123}"

echo "==> 启动后端（端口 ${SERVER_PORT}）"
exec mvn spring-boot:run "$@"
