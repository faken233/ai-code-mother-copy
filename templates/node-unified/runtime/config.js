'use strict';

/**
 * 运行时配置：只从环境变量读取，代码里不出现任何部署细节。
 * 变量：APP_PORT / APP_DB / DB_HOST / DB_PORT / DB_USER / DB_PASS / JWT_SECRET
 *
 * 注意：应用端口用 APP_PORT，不用 PORT —— 沙箱容器里的文件服务占用了 PORT（8080），
 * 容器级 env 会同时被两个进程继承，用独立变量名才能共存。
 */

function num(value, fallback) {
  const n = Number(value);
  return Number.isFinite(n) ? n : fallback;
}

module.exports = {
  port: num(process.env.APP_PORT, 3000),
  db: {
    host: process.env.DB_HOST || '127.0.0.1',
    port: num(process.env.DB_PORT, 3306),
    database: process.env.APP_DB || 'app_demo',
    user: process.env.DB_USER || 'root',
    password: process.env.DB_PASS || '',
    connectionLimit: num(process.env.DB_POOL_SIZE, 4)
  },
  jwtSecret: process.env.JWT_SECRET || 'dev-only-secret-change-me',
  jwtExpiresIn: process.env.JWT_EXPIRES_IN || '7d',
  bodyLimit: '1mb'
};
