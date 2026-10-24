#!/usr/bin/env bash
# P5 验收：容器化部署（快照 → 生产镜像 → 版本容器 → 原子切路由 → 回滚）
#
# 用法：
#   scripts/verify-p5.sh <appId>          # 建议用纯前端（static）应用，验收最快
#
# 前置：
#   1) 后端 8123、proxy 8124 已启动
#   2) 应用属于 USER_ACCOUNT（默认 cp3tester），且已生成过代码
#   3) 本地已有 deploy.base-image（默认 node:18-alpine）
#
# 覆盖：部署地址形态、镜像/容器命名、只暴露应用端口、无工具通道令牌、
#       Redis 部署路由（无 TTL）、代理访问、生产库隔离、重部署、回滚
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PLATFORM="${PLATFORM:-http://127.0.0.1:8123}"
PROXY="${PROXY:-http://127.0.0.1:8124}"
APP_ID="${1:-${APP_ID:-}}"
USER_ACCOUNT="${USER_ACCOUNT:-cp3tester}"
USER_PASSWORD="${USER_PASSWORD:-12345678}"
MYSQL_CONTAINER="${MYSQL_CONTAINER:-yu-ai-platform-mysql}"
REDIS_CONTAINER="${REDIS_CONTAINER:-yu-ai-platform-redis}"
COOKIE="$(mktemp)"
PASS=0
FAIL=0
FAILED=()

ok()  { PASS=$((PASS+1)); echo "[✅] $1"; }
bad() { FAIL=$((FAIL+1)); FAILED+=("$1"); echo "[❌] $1"; [ -n "${2:-}" ] && echo "      → $2"; }
check() { if [ "$2" = "1" ]; then ok "$1"; else bad "$1" "${3:-}"; fi; }
json_get() { python3 -c 'import sys,json;d=json.load(sys.stdin);print(eval(sys.argv[1]))' "$1" 2>/dev/null; }
mysql_q() { docker exec "$MYSQL_CONTAINER" mysql -uroot -p123456 -N -e "use yu_ai_code_mother; $1" 2>/dev/null; }
redis_get() { docker exec "$REDIS_CONTAINER" redis-cli GET "$1" 2>/dev/null; }
redis_ttl() { docker exec "$REDIS_CONTAINER" redis-cli TTL "$1" 2>/dev/null; }

if [ -z "$APP_ID" ]; then
  echo "用法：scripts/verify-p5.sh <appId>" >&2
  exit 2
fi

echo "== P5 验收 =="
echo "   平台: $PLATFORM   appId: $APP_ID"
echo

# ---------- 1. 登录 & 应用类型 ----------
login=$(curl -s -c "$COOKIE" -X POST "$PLATFORM/api/user/login" \
  -H 'Content-Type: application/json' \
  -d "{\"userAccount\":\"$USER_ACCOUNT\",\"userPassword\":\"$USER_PASSWORD\"}")
check "平台登录成功（${USER_ACCOUNT}）" "$(echo "$login" | grep -q '"code":0' && echo 1 || echo 0)" "$login"

appvo=$(curl -s "$PLATFORM/api/app/get/vo?id=$APP_ID")
codegen=$(echo "$appvo" | json_get 'd["data"].get("codeGenType")')
check "应用存在且类型为 static/fullstack" \
  "$([ "$codegen" = "static" ] || [ "$codegen" = "fullstack" ] && echo 1 || echo 0)" "codeGenType=$codegen"

# ---------- 2. 首次部署 ----------
deploy1=$(curl -s -b "$COOKIE" -X POST "$PLATFORM/api/app/deploy" \
  -H 'Content-Type: application/json' -d "{\"appId\":$APP_ID}" -m 300)
url=$(echo "$deploy1" | json_get 'd.get("data") or ""')
check "部署接口返回部署地址" "$([ -n "$url" ] && echo 1 || echo 0)" "$deploy1"
[ -z "$url" ] && { echo; echo "== 结果：$PASS 通过 / $FAIL 失败 =="; rm -f "$COOKIE"; exit 1; }

host=$(echo "$url" | sed -E 's#^https?://([^/:]+).*#\1#')
key=${host%%.*}
check "部署地址形态 {deployKey}.app.localhost:8124（${url}）" \
  "$(echo "$url" | grep -Eq '^http://[a-z0-9]{6}\.app\.localhost:8124/$' && echo 1 || echo 0)" "$url"
check "deployKey 已写回 app 表" \
  "$([ "$(mysql_q "select deployKey from app where id=$APP_ID;")" = "$key" ] && echo 1 || echo 0)" \
  "db=$(mysql_q "select deployKey from app where id=$APP_ID;") url=$key"

info=$(curl -s -b "$COOKIE" "$PLATFORM/api/app/deploy/$APP_ID/info")
version=$(echo "$info" | json_get 'd["data"].get("currentVersion")')
container="app-dep-${APP_ID}-v${version}"
image="app-dep-${APP_ID}:v${version}"
check "部署信息 currentVersion=${version}" "$([ -n "$version" ] && echo 1 || echo 0)" "$info"

# ---------- 3. 容器 / 镜像 / 端口 / 环境隔离 ----------
running=$(docker inspect -f '{{.State.Running}}' "$container" 2>/dev/null)
check "版本容器在运行（${container}）" "$([ "$running" = "true" ] && echo 1 || echo 0)" "running=$running"

image_exists=$(docker image inspect "$image" >/dev/null 2>&1 && echo 1 || echo 0)
check "生产镜像存在（${image}）" "$image_exists" "docker image inspect 失败"

kind_label=$(docker inspect -f '{{index .Config.Labels "app.kind"}}' "$container" 2>/dev/null)
ver_label=$(docker inspect -f '{{index .Config.Labels "app.version"}}' "$container" 2>/dev/null)
check "容器标签 app.kind=deploy / app.version=${version}" \
  "$([ "$kind_label" = "deploy" ] && [ "$ver_label" = "$version" ] && echo 1 || echo 0)" \
  "kind=$kind_label version=$ver_label"

port_map=$(docker port "$container" 2>/dev/null)
check "只发布应用端口 3000，不暴露工具通道 8080" \
  "$([ "$(echo "$port_map" | grep -c '3000/tcp')" = "1" ] && [ "$(echo "$port_map" | grep -c '8080/tcp')" = "0" ] && echo 1 || echo 0)" \
  "$port_map"
check "部署容器未注入 TOOL_TOKEN（无工具通道）" \
  "$(docker inspect -f '{{range .Config.Env}}{{println .}}{{end}}' "$container" | grep -q '^TOOL_TOKEN=' && echo 0 || echo 1)"

if [ "$codegen" = "fullstack" ]; then
  env_dump=$(docker inspect -f '{{range .Config.Env}}{{println .}}{{end}}' "$container")
  check "fullstack 注入生产库 app_${APP_ID}_prod（不是预览库）" \
    "$(echo "$env_dump" | grep -q "^APP_DB=app_${APP_ID}_prod$" && echo 1 || echo 0)" \
    "$(echo "$env_dump" | grep '^APP_DB=' || true)"
  prod_tables=$(docker exec yu-ai-sandbox-mysql mysql -uroot -p123456 -N -e \
    "select count(*) from information_schema.tables where table_schema='app_${APP_ID}_prod';" 2>/dev/null)
  check "生产库已由 migrate 建表（表数 ${prod_tables:-0}）" \
    "$([ "${prod_tables:-0}" -gt 0 ] && echo 1 || echo 0)"
else
  check "static 部署不注入 APP_DB（纯前端不连库）" \
    "$(docker inspect -f '{{range .Config.Env}}{{println .}}{{end}}' "$container" | grep -q '^APP_DB=' && echo 0 || echo 1)"
fi

# ---------- 4. 路由 / 代理 ----------
redis_raw=$(redis_get "route:${host}")
check "Redis 存在部署路由 route:${host}" "$([ -n "$redis_raw" ] && echo 1 || echo 0)" "$redis_raw"
check "路由 kind=DEPLOY" "$(echo "$redis_raw" | grep -q '"kind":"DEPLOY"' && echo 1 || echo 0)" "$redis_raw"
check "部署路由不设 TTL（TTL=-1）" "$([ "$(redis_ttl "route:${host}")" = "-1" ] && echo 1 || echo 0)" \
  "ttl=$(redis_ttl "route:${host}")"

upstream=$(echo "$redis_raw" | json_get 'd.get("upstream") or ""')
route_port="${upstream##*:}"
docker_port=$(echo "$port_map" | grep '3000/tcp' | head -1 | sed 's/.*://')
check "路由 upstream 与 docker port 一致" "$([ "$route_port" = "$docker_port" ] && echo 1 || echo 0)" \
  "upstream=$upstream docker=$docker_port"

page_code=$(curl -s -o /tmp/p5-page.html -w '%{http_code}' -H "Host: ${host}" "$PROXY/")
page_type=$(curl -s -o /dev/null -w '%{content_type}' -H "Host: ${host}" "$PROXY/")
check "经 proxy 访问部署站点 → 200 HTML" \
  "$([ "$page_code" = "200" ] && echo "$page_type" | grep -q 'text/html' && echo 1 || echo 0)" \
  "HTTP $page_code, $page_type"
health=$(curl -s -H "Host: ${host}" "$PROXY/api/health")
check "经 proxy 健康检查 app:true" "$(echo "$health" | grep -q '"app":true' && echo 1 || echo 0)" "$health"

db_status=$(mysql_q "select status from app_deploy where app_id=$APP_ID and deploy_version=$version;")
check "app_deploy 记录状态 ACTIVE" "$([ "$db_status" = "ACTIVE" ] && echo 1 || echo 0)" "status=$db_status"

# ---------- 5. 重新部署（v2） ----------
deploy2=$(curl -s -b "$COOKIE" -X POST "$PLATFORM/api/app/deploy" \
  -H 'Content-Type: application/json' -d "{\"appId\":$APP_ID}" -m 300)
check "第二次部署成功且地址不变" \
  "$([ "$(echo "$deploy2" | json_get 'd.get("data") or ""')" = "$url" ] && echo 1 || echo 0)" "$deploy2"

info2=$(curl -s -b "$COOKIE" "$PLATFORM/api/app/deploy/$APP_ID/info")
version2=$(echo "$info2" | json_get 'd["data"].get("currentVersion")')
new_container="app-dep-${APP_ID}-v${version2}"
new_image="app-dep-${APP_ID}:v${version2}"
check "版本号递增（v${version} → v${version2}）" \
  "$([ -n "$version2" ] && [ "$version2" -gt "$version" ] && echo 1 || echo 0)" "info=$info2"
check "新版本容器运行、旧版本已停" \
  "$([ "$(docker inspect -f '{{.State.Running}}' "$new_container" 2>/dev/null)" = "true" ] && \
     [ "$(docker inspect -f '{{.State.Running}}' "$container" 2>/dev/null)" = "false" ] && echo 1 || echo 0)" \
  "new=$(docker inspect -f '{{.State.Running}}' "$new_container" 2>/dev/null) old=$(docker inspect -f '{{.State.Running}}' "$container" 2>/dev/null)"
check "旧版本镜像保留（可回滚）" "$(docker image inspect "$image" >/dev/null 2>&1 && echo 1 || echo 0)"

if [ "$codegen" = "fullstack" ]; then
  pass1=$(mysql_q "select db_password from app_deploy where app_id=$APP_ID and deploy_version=$version;")
  pass2=$(mysql_q "select db_password from app_deploy where app_id=$APP_ID and deploy_version=$version2;")
  check "重部署复用生产库凭据（用户不会掉线）" "$([ -n "$pass1" ] && [ "$pass1" = "$pass2" ] && echo 1 || echo 0)"
fi

# ---------- 6. 回滚 ----------
rollback=$(curl -s -b "$COOKIE" -X POST "$PLATFORM/api/app/deploy/$APP_ID/rollback" \
  -H 'Content-Type: application/json' -d '{}' -m 180)
check "回滚接口返回原部署地址" \
  "$([ "$(echo "$rollback" | json_get 'd.get("data") or ""')" = "$url" ] && echo 1 || echo 0)" "$rollback"

info3=$(curl -s -b "$COOKIE" "$PLATFORM/api/app/deploy/$APP_ID/info")
back_version=$(echo "$info3" | json_get 'd["data"].get("currentVersion")')
check "回滚后 currentVersion=v${version}" "$([ "$back_version" = "$version" ] && echo 1 || echo 0)" "info=$info3"

rollback_upstream=$(redis_get "route:${host}" | json_get 'd.get("upstream") or ""')
rollback_port="${rollback_upstream##*:}"
old_port=$(docker port "$container" 3000/tcp | head -1 | sed 's/.*://')
check "路由指回旧版本上游" "$([ "$rollback_port" = "$old_port" ] && echo 1 || echo 0)" \
  "route=$rollback_upstream old=$old_port"
check "回滚后 v${version2} 已停、v${version} 在跑" \
  "$([ "$(docker inspect -f '{{.State.Running}}' "$container" 2>/dev/null)" = "true" ] && \
     [ "$(docker inspect -f '{{.State.Running}}' "$new_container" 2>/dev/null)" = "false" ] && echo 1 || echo 0)"

health3=$(curl -s -H "Host: ${host}" "$PROXY/api/health")
check "回滚后经 proxy 仍健康（地址不变）" "$(echo "$health3" | grep -q '"app":true' && echo 1 || echo 0)" "$health3"

echo
echo "================ 结果 ================"
echo "   通过 ${PASS} / 失败 ${FAIL}"
echo "   部署地址: ${url}   （回滚后在 v${version}）"
if [ "$FAIL" -gt 0 ]; then
  printf '   ❌ %s\n' "${FAILED[@]}"
  rm -f "$COOKIE"
  exit 1
fi
rm -f "$COOKIE"
echo "   ✅ P5 容器化部署验收通过"
