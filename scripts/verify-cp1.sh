#!/usr/bin/env bash
# CP1 自动验收：数据层 + 归属隔离 + 越权拦截
# 用法：scripts/verify-cp1.sh   （先跑 scripts/sandbox-up.sh）
set -uo pipefail

BASE="${BASE:-http://127.0.0.1:20081}"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
PASS=0
FAIL=0
FAILED=()

ok()   { PASS=$((PASS+1)); echo "[✅] $1"; }
bad()  { FAIL=$((FAIL+1)); FAILED+=("$1"); echo "[❌] $1"; [ -n "${2:-}" ] && echo "      → $2"; }
check(){ if [ "$2" = "1" ]; then ok "$1"; else bad "$1" "${3:-}"; fi; }

req() { # method path token json
  local method="$1" path="$2" token="${3:-}" data="${4:-}"
  local args=(-s -o "$TMP/body" -w '%{http_code}' -X "$method" "$BASE$path" -H 'Content-Type: application/json')
  # 经 proxy 打时用来指定 Host（例：EXTRA_HEADER='Host: 845.pv.localhost'）
  [ -n "${EXTRA_HEADER:-}" ] && args+=(-H "$EXTRA_HEADER")
  [ -n "$token" ] && args+=(-H "Authorization: Bearer $token")
  [ -n "$data" ] && args+=(-d "$data")
  curl "${args[@]}"
}

pick() { # 路径 a.b.0  → 从 $TMP/body 取值
  python3 -c '
import json, sys
with open(sys.argv[2], encoding="utf-8") as fh:
    data = json.load(fh)
for key in sys.argv[1].split("."):
    data = data[int(key)] if key.isdigit() else data[key]
print(data)
' "$1" "$TMP/body"
}

echo "== CP1 验收 =="
echo "   目标: $BASE"
echo

# ---------- 0. 健康检查 ----------
code=$(req GET /api/health)
check "健康检查 GET /api/health → 200，且 app=true" \
  "$([ "$code" = "200" ] && [ "$(pick app 2>/dev/null)" = "True" ] && echo 1 || echo 0)" \
  "HTTP $code / body=$(cat "$TMP/body")"

# ---------- 1. 注册两个账号 ----------
SUFFIX="$(date +%s)$RANDOM"
A_EMAIL="a${SUFFIX}@demo.com"
B_EMAIL="b${SUFFIX}@demo.com"
A_PASS="pass123456"
B_PASS="pass123456"

code=$(req POST /api/auth/register "" "{\"email\":\"$A_EMAIL\",\"password\":\"$A_PASS\"}")
A_TOKEN="$(pick token 2>/dev/null)"; A_ID="$(pick user.id 2>/dev/null)"
check "注册账号 A → 201" "$([ "$code" = "201" ] && echo 1 || echo 0)" "HTTP $code / $(cat "$TMP/body")"

code=$(req POST /api/auth/register "" "{\"email\":\"$B_EMAIL\",\"password\":\"$B_PASS\"}")
B_TOKEN="$(pick token 2>/dev/null)"
check "注册账号 B → 201" "$([ "$code" = "201" ] && echo 1 || echo 0)" "HTTP $code / $(cat "$TMP/body")"

# ---------- 2. A 写入两条（其中一条故意塞 user_id=999999） ----------
code=$(req POST /api/expenses "$A_TOKEN" '{"title":"午饭","amount":32.5,"category":"餐饮","spent_at":"2026-10-20T12:30","note":"公司楼下","user_id":999999}')
ROW1_ID="$(pick id 2>/dev/null)"; ROW1_OWNER="$(pick user_id 2>/dev/null)"
check "A 新增记录 → 201" "$([ "$code" = "201" ] && echo 1 || echo 0)" "HTTP $code / $(cat "$TMP/body")"
check "请求体注入 user_id=999999 被丢弃，实际归属为 A(id=$A_ID)" \
  "$([ "$ROW1_OWNER" = "$A_ID" ] && echo 1 || echo 0)" "实际 user_id=$ROW1_OWNER"

code=$(req POST /api/expenses "$A_TOKEN" '{"title":"地铁","amount":6,"category":"交通","spent_at":"2026-10-20T09:00"}')
check "A 新增第二条 → 201" "$([ "$code" = "201" ] && echo 1 || echo 0)" "HTTP $code"

# ---------- 3. 列表与隔离 ----------
code=$(req GET "/api/expenses?sort=-spent_at" "$A_TOKEN")
A_TOTAL="$(pick total 2>/dev/null)"
check "A 的列表 total=2" "$([ "$code" = "200" ] && [ "$A_TOTAL" = "2" ] && echo 1 || echo 0)" "HTTP $code / total=$A_TOTAL"

code=$(req GET "/api/expenses" "$B_TOKEN")
B_TOTAL="$(pick total 2>/dev/null)"
check "B 的列表 total=0（数据隔离）" "$([ "$code" = "200" ] && [ "$B_TOTAL" = "0" ] && echo 1 || echo 0)" "HTTP $code / total=$B_TOTAL"

code=$(req GET "/api/expenses" "")
check "未登录访问 owner 表 → 401" "$([ "$code" = "401" ] && echo 1 || echo 0)" "HTTP $code"

# ---------- 4. 越权写 ----------
code=$(req PUT "/api/expenses/$ROW1_ID" "$B_TOKEN" '{"title":"[越权测试] 被篡改了"}')
check "B 修改 A 的记录 → 404（不是 200/403）" "$([ "$code" = "404" ] && echo 1 || echo 0)" "HTTP $code"

code=$(req DELETE "/api/expenses/$ROW1_ID" "$B_TOKEN")
check "B 删除 A 的记录 → 404" "$([ "$code" = "404" ] && echo 1 || echo 0)" "HTTP $code"

code=$(req GET "/api/expenses/$ROW1_ID" "$A_TOKEN")
TITLE_AFTER="$(pick title 2>/dev/null)"
check "A 的记录未被篡改，标题仍是「午饭」" "$([ "$TITLE_AFTER" = "午饭" ] && echo 1 || echo 0)" "实际标题=$TITLE_AFTER"

# ---------- 5. 校验与白名单 ----------
code=$(req POST /api/expenses "$A_TOKEN" '{"title":"负数","amount":-1,"category":"餐饮","spent_at":"2026-10-20T12:30"}')
check "金额为负 → 400（声明式校验）" "$([ "$code" = "400" ] && echo 1 || echo 0)" "HTTP $code / $(cat "$TMP/body")"

code=$(req POST /api/expenses "$A_TOKEN" '{"title":"错误分类","amount":1,"category":"不存在","spent_at":"2026-10-20T12:30"}')
check "非法枚举值 → 400" "$([ "$code" = "400" ] && echo 1 || echo 0)" "HTTP $code / $(cat "$TMP/body")"

code=$(req GET "/api/not_a_table" "$A_TOKEN")
check "未声明的表 → 404（表名白名单）" "$([ "$code" = "404" ] && echo 1 || echo 0)" "HTTP $code"

# ---------- 6. 端口/进程 ----------
code=$(req GET "/api/expenses?sort=id;drop%20table%20expenses" "$A_TOKEN")
check "非法排序字段被拒绝（不拼进 SQL）" "$([ "$code" = "400" ] && echo 1 || echo 0)" "HTTP $code"

echo
echo "== 结果：$PASS 通过 / $FAIL 失败 =="
if [ "$FAIL" -gt 0 ]; then
  printf '   失败项：\n'; printf '   - %s\n' "${FAILED[@]}"
  exit 1
fi
echo "   全部通过，可以打开 $BASE 做人工验收。"
