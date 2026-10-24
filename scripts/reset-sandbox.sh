#!/usr/bin/env bash
# 清场：删除所有生成应用的沙箱容器（预览/部署/旧命名）
#
# 用法：
#   scripts/reset-sandbox.sh                  # 只删容器，保留 app_{id} 数据库与工作区代码
#   scripts/reset-sandbox.sh --purge-db --yes # 额外 drop 所有 app_{id} 库与账号（不可逆）
#
# 不会动的：平台 MySQL、Redis、沙箱 MySQL 容器本体、tmp/code_output 下的源码。
set -uo pipefail

PURGE_DB=0
ASSUME_YES=0
for arg in "$@"; do
  case "$arg" in
    --purge-db) PURGE_DB=1 ;;
    --yes|-y)   ASSUME_YES=1 ;;
    -h|--help)  sed -n '2,9p' "$0"; exit 0 ;;
    *) echo "未知参数：$arg" >&2; exit 2 ;;
  esac
done

SANDBOX_MYSQL_CONTAINER="${SANDBOX_MYSQL_CONTAINER:-yu-ai-sandbox-mysql}"
SANDBOX_MYSQL_PASSWORD="${SANDBOX_MYSQL_PASSWORD:-123456}"
PATTERN='^(app-pv-|app-dep-|code-sandbox-)'

echo "== 清理生成应用容器 =="
targets=$(docker ps -a --format '{{.Names}}' 2>/dev/null | grep -E "$PATTERN" || true)

if [ -z "$targets" ]; then
  echo "没有需要清理的容器。"
else
  echo "$targets" | while read -r name; do
    [ -z "$name" ] && continue
    if docker rm -f "$name" >/dev/null 2>&1; then
      echo "  已删除 $name"
    else
      echo "  删除失败 $name（可能已停止，忽略）"
    fi
  done
fi

if [ "$PURGE_DB" = "1" ]; then
  echo
  echo "== 清理应用数据库（--purge-db）=="
  if [ "$ASSUME_YES" != "1" ]; then
    echo "该操作会永久删除所有 app_* 数据库，确认请输入 yes：" >&2
    read -r answer
    [ "$answer" = "yes" ] || { echo "已取消"; exit 1; }
  fi

  dbs=$(docker exec "$SANDBOX_MYSQL_CONTAINER" mysql -uroot -p"$SANDBOX_MYSQL_PASSWORD" -N -e \
    "SELECT schema_name FROM information_schema.schemata WHERE schema_name LIKE 'app\\_%'" 2>/dev/null || true)

  if [ -z "$dbs" ]; then
    echo "没有 app_* 数据库（或无法连接 $SANDBOX_MYSQL_CONTAINER）。"
  else
    for db in $dbs; do
      docker exec "$SANDBOX_MYSQL_CONTAINER" mysql -uroot -p"$SANDBOX_MYSQL_PASSWORD" -e \
        "DROP DATABASE IF EXISTS \`$db\`; DROP USER IF EXISTS '$db'@'%';" >/dev/null 2>&1 \
        && echo "  已删除库与账号 $db" \
        || echo "  删除失败 $db"
    done
    docker exec "$SANDBOX_MYSQL_CONTAINER" mysql -uroot -p"$SANDBOX_MYSQL_PASSWORD" -e \
      "FLUSH PRIVILEGES" >/dev/null 2>&1 || true
  fi
fi

echo
echo "== 剩余容器 =="
docker ps --format '  {{.Names}}  {{.Status}}' || true
