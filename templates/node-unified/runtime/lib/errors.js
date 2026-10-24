'use strict';

/**
 * 统一错误：所有对外错误都走这里，响应体形状稳定为
 * { error: { code, message } }
 */
class ApiError extends Error {
  constructor(status, code, message) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
  }
}

module.exports = {
  ApiError,
  badRequest: (message) => new ApiError(400, 'BAD_REQUEST', message),
  unauthorized: (message = '请先登录') => new ApiError(401, 'UNAUTHORIZED', message),
  forbidden: (message = '无权限') => new ApiError(403, 'FORBIDDEN', message),
  notFound: (message = '资源不存在') => new ApiError(404, 'NOT_FOUND', message),
  methodNotAllowed: (message = '该操作不被允许') => new ApiError(405, 'METHOD_NOT_ALLOWED', message),
  conflict: (message) => new ApiError(409, 'CONFLICT', message),
  tooMany: (message) => new ApiError(429, 'TOO_MANY_REQUESTS', message),
  internal: (message = '服务器内部错误') => new ApiError(500, 'INTERNAL', message)
};
