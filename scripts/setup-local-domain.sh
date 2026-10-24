#!/usr/bin/env bash
# 本地域名映射（幂等）：把 lowcode.top 指向本机，供本地开发/演示使用
#
# 用法：
#   sudo bash scripts/setup-local-domain.sh add                    # lowcode.top -> 127.0.0.1
#   sudo bash scripts/setup-local-domain.sh add app.lowcode.top    # 追加子域
#   sudo bash scripts/setup-local-domain.sh remove lowcode.top     # 移除
#   bash scripts/setup-local-domain.sh status                      # 只查看（无需 root）
#
# 说明：/etc/hosts 归 root 所有，add/remove 需要 sudo；脚本会先备份再改写，并回读校验。
set -euo pipefail

ACTION="${1:-add}"
DOMAIN="${2:-lowcode.top}"
IP="${3:-127.0.0.1}"
MARKER="# lowcode-local-domain"
HOSTS="/etc/hosts"

flush_dns() {
  if command -v dscacheutil >/dev/null 2>&1; then
    dscacheutil -flushcache 2>/dev/null || true
    killall -HUP mDNSResponder 2>/dev/null || true
  elif command -v systemd-resolve >/dev/null 2>&1; then
    systemd-resolve --flush-caches 2>/dev/null || true
  fi
}

resolve() {
  if command -v dscacheutil >/dev/null 2>&1; then
    dscacheutil -q host -a name "$DOMAIN" 2>/dev/null | awk '/ip_address/{print $2; found=1} END{if(!found) exit 1}'
  else
    getent hosts "$DOMAIN" 2>/dev/null | awk 'NR==1{print $1; found=1} END{if(!found) exit 1}'
  fi
}

if [ "$ACTION" = "status" ]; then
  echo "--- /etc/hosts 中与 ${DOMAIN} 相关的行 ---"
  grep -nF "$DOMAIN" "$HOSTS" || echo "(无)"
  echo "--- 当前解析 ---"
  resolve || echo "(未解析)"
  exit 0
fi

if [ "$(id -u)" -ne 0 ]; then
  echo "需要 root 权限：sudo bash $0 $*" >&2
  exit 1
fi

cp "$HOSTS" "${HOSTS}.bak.$(date +%Y%m%d%H%M%S)"
echo "已备份：${HOSTS}.bak.$(date +%Y%m%d%H%M%S)"

case "$ACTION" in
  add)
    escaped="${DOMAIN//./\\.}"
    if grep -qE "(^|[[:space:]])${escaped}([[:space:]]|$)" "$HOSTS"; then
      echo "已存在 ${DOMAIN} 的映射，跳过写入"
    else
      printf '%s\t%s %s\n' "$IP" "$DOMAIN" "$MARKER" >> "$HOSTS"
      echo "已写入：${IP} ${DOMAIN}"
    fi
    ;;
  remove)
    tmp="$(mktemp)"
    grep -vF "$DOMAIN" "$HOSTS" > "$tmp" || true
    cat "$tmp" > "$HOSTS"
    rm -f "$tmp"
    echo "已移除 ${DOMAIN} 相关行"
    ;;
  *)
    echo "未知动作：${ACTION}（可选 add / remove / status）" >&2
    exit 2
    ;;
esac

flush_dns
echo "--- 回读校验 ---"
grep -nF "$DOMAIN" "$HOSTS" || true
sleep 1
if got="$(resolve)"; then
  echo "✅ 解析生效：${DOMAIN} -> ${got}"
else
  echo "⚠️ 解析尚未生效，可稍等或手动执行：sudo dscacheutil -flushcache; sudo killall -HUP mDNSResponder"
  exit 1
fi
