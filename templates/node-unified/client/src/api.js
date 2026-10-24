const TOKEN_KEY = 'token';

export const getToken = () => localStorage.getItem(TOKEN_KEY) || '';
export const setToken = (token) => localStorage.setItem(TOKEN_KEY, token);
export const clearToken = () => localStorage.removeItem(TOKEN_KEY);

/**
 * API 基址必须是「相对当前文档路径」推导，不能用绝对 /api：
 *   http://host/                       → /api
 *   http://host/api/preview/123/       → /api/preview/123/api
 *   http://host/{accessCode}/          → /{accessCode}/api
 * 写死 '/api' 会在预览/短链前缀下打到平台后端，表现为页面能开、接口全挂。
 */
export function resolveApiBase() {
  const pathname = window.location.pathname;
  const dir = pathname.endsWith('/') ? pathname : pathname.slice(0, pathname.lastIndexOf('/') + 1);
  return `${dir}api`;
}

const API_BASE = resolveApiBase();

export async function api(path, options = {}) {
  const headers = { 'Content-Type': 'application/json', ...(options.headers || {}) };
  const token = getToken();
  if (token) headers.Authorization = `Bearer ${token}`;

  const res = await fetch(`${API_BASE}${path}`, { ...options, headers });
  const text = await res.text();
  let body = null;
  if (text) {
    try {
      body = JSON.parse(text);
    } catch (err) {
      body = text;
    }
  }
  return { status: res.status, ok: res.ok, body };
}

export function errorMessage(result, fallback = '请求失败') {
  return (result.body && result.body.error && result.body.error.message) || fallback;
}
