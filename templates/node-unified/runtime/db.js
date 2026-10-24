'use strict';

const mysql = require('mysql2/promise');
const config = require('./config');

/**
 * 应用唯一的数据库入口。
 * 约束：只对外暴露参数化执行方法，不导出裸连接，避免任何地方拼接 SQL 字符串。
 */
const pool = mysql.createPool({
  host: config.db.host,
  port: config.db.port,
  user: config.db.user,
  password: config.db.password,
  database: config.db.database,
  waitForConnections: true,
  connectionLimit: config.db.connectionLimit,
  queueLimit: 0,
  charset: 'utf8mb4_general_ci',
  timezone: 'Z',
  supportBigNumbers: true,
  bigNumberStrings: false,
  dateStrings: true
});

async function exec(sql, params = []) {
  const [rows] = await pool.execute(sql, params);
  return rows;
}

async function ping() {
  const conn = await pool.getConnection();
  try {
    await conn.ping();
  } finally {
    conn.release();
  }
  return true;
}

async function close() {
  await pool.end();
}

module.exports = { exec, ping, close };
