#!/usr/bin/env bash
# 硬下线旧格式应用（html / multi_file / vue_project）。
#
# 背景：P3 起只支持 static / fullstack 两种模式，旧格式应用不能预览、不能部署，
# 也无法热迁移（代码结构不兼容）。这里按「删除数据」处理，执行前会先备份。
#
# 用法：
#   bash scripts/purge-legacy-apps.sh          # 只列出将删除的内容（dry-run）
#   bash scripts/purge-legacy-apps.sh --yes    # 备份并真正删除
#
# 删除范围：
#   app 行（物理删除）、chat_history、gen_task、app_deploy、app_access_route
#   tmp/code_output/*_{appId}、tmp/code_deploy/{appId}
#   app_{appId} / app_{appId}_prod 数据库与同名账号
#   残留的 app-pv-{appId}、app-dep-{appId}-v* 容器
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

if [ -f .env ]; then
  set -a
  # shellcheck disable=SC1091
  . ./.env
  set +a
fi

MYSQL_CONTAINER="${MYSQL_CONTAINER:-yu-ai-platform-mysql}"
MYSQL_DATABASE="${MYSQL_DATABASE:-yu_ai_code_mother}"
MYSQL_USERNAME="${MYSQL_USERNAME:-root}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-123456}"
LEGACY_TYPES="'html','multi_file','vue_project'"

if ! docker inspect "$MYSQL_CONTAINER" >/dev/null 2>&1; then
  echo "❌ MySQL 容器不存在: $MYSQL_CONTAINER" >&2
  exit 1
fi

mysql_exec() {
  docker exec -i -e MYSQL_PWD="$MYSQL_PASSWORD" "$MYSQL_CONTAINER" \
    mysql -u"$MYSQL_USERNAME" --batch --skip-column-names "$MYSQL_DATABASE" "$@"
}

IDS="$(mysql_exec -e "SELECT id FROM app WHERE codeGenType IN ($LEGACY_TYPES) AND isDelete = 0 ORDER BY id;")"
if [ -z "$IDS" ]; then
  echo "✅ 没有需要处理的旧格式应用"
  exit 0
fi
ID_LIST="$(echo "$IDS" | paste -sd, -)"
COUNT="$(echo "$IDS" | wc -l | tr -d ' ')"

echo "旧格式应用：$COUNT 个"
mysql_exec -e "SELECT id, codeGenType, LEFT(appName, 24) FROM app WHERE id IN ($ID_LIST) ORDER BY id;"
echo
echo "关联数据："
mysql_exec -e "SELECT 'chat_history', COUNT(*) FROM chat_history WHERE appId IN ($ID_LIST)
  UNION ALL SELECT 'gen_task', COUNT(*) FROM gen_task WHERE appId IN ($ID_LIST)
  UNION ALL SELECT 'app_deploy', COUNT(*) FROM app_deploy WHERE app_id IN ($ID_LIST)
  UNION ALL SELECT 'app_access_route', COUNT(*) FROM app_access_route WHERE app_id IN ($ID_LIST);"
echo
echo "代码目录："
for id in $IDS; do
  for dir in "$ROOT"/tmp/code_output/*_"$id"; do
    [ -d "$dir" ] && echo "  $dir"
  done
  [ -d "$ROOT/tmp/code_deploy/$id" ] && echo "  $ROOT/tmp/code_deploy/$id"
done
echo

if [ "${1:-}" != "--yes" ]; then
  echo "ℹ️  dry-run：确认无误后执行 bash scripts/purge-legacy-apps.sh --yes"
  exit 0
fi

BACKUP_DIR="$ROOT/tmp/backup/legacy-apps-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$BACKUP_DIR"
echo "备份到 $BACKUP_DIR"
docker exec -e MYSQL_PWD="$MYSQL_PASSWORD" "$MYSQL_CONTAINER" \
  mysqldump -u"$MYSQL_USERNAME" "$MYSQL_DATABASE" app \
  --no-create-info --complete-insert --skip-add-locks --where="id IN ($ID_LIST)" > "$BACKUP_DIR/app.sql"
docker exec -e MYSQL_PWD="$MYSQL_PASSWORD" "$MYSQL_CONTAINER" \
  mysqldump -u"$MYSQL_USERNAME" "$MYSQL_DATABASE" chat_history \
  --no-create-info --complete-insert --skip-add-locks --where="appId IN ($ID_LIST)" > "$BACKUP_DIR/chat_history.sql"
docker exec -e MYSQL_PWD="$MYSQL_PASSWORD" "$MYSQL_CONTAINER" \
  mysqldump -u"$MYSQL_USERNAME" "$MYSQL_DATABASE" gen_task \
  --no-create-info --complete-insert --skip-add-locks --where="appId IN ($ID_LIST)" > "$BACKUP_DIR/gen_task.sql"
printf '%s\n' "$IDS" > "$BACKUP_DIR/app-ids.txt"
echo "备份文件：$(ls "$BACKUP_DIR")"

# 1) 停掉可能残留的容器（旧应用正常情况下已经没有容器）
for id in $IDS; do
  docker rm -f "app-pv-$id" >/dev/null 2>&1 || true
  for name in $(docker ps -a --filter "label=app.id=$id" --format '{{.Names}}' 2>/dev/null); do
    docker rm -f "$name" >/dev/null 2>&1 || true
  done
done

# 2) 删关联行 + 应用行（物理删除）
mysql_exec -e "DELETE FROM chat_history WHERE appId IN ($ID_LIST);
DELETE FROM gen_task WHERE appId IN ($ID_LIST);
DELETE FROM app_deploy WHERE app_id IN ($ID_LIST);
DELETE FROM app_access_route WHERE app_id IN ($ID_LIST);
DELETE FROM app WHERE id IN ($ID_LIST);"
echo "✅ 数据库行已删除"

# 3) 删代码目录（只删 tmp 下、名称以 _{appId} 结尾的目录）
for id in $IDS; do
  for dir in "$ROOT"/tmp/code_output/*_"$id"; do
    [ -d "$dir" ] || continue
    case "$dir" in
      "$ROOT"/tmp/code_output/*_"$id") rm -rf -- "$dir"; echo "  删除目录 $dir" ;;
      *) echo "  跳过非法路径 $dir" >&2 ;;
    esac
  done
  if [ -d "$ROOT/tmp/code_deploy/$id" ]; then
    rm -rf -- "$ROOT/tmp/code_deploy/$id"
    echo "  删除目录 $ROOT/tmp/code_deploy/$id"
  fi
done

# 4) 删预览库 / 生产库与同名账号
for id in $IDS; do
  mysql_exec -e "DROP DATABASE IF EXISTS \`app_$id\`; DROP DATABASE IF EXISTS \`app_${id}_prod\`;
DROP USER IF EXISTS 'app_$id'@'%'; DROP USER IF EXISTS 'app_${id}_prod'@'%';" 2>/dev/null || true
done
echo "✅ 旧格式应用已硬下线（备份保留在 ${BACKUP_DIR}）"
