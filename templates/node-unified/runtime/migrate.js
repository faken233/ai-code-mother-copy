'use strict';

const db = require('./db');
const schema = require('./schema.json');
const { createTableDdl, addColumnDdl, createIndexDdl, indexName } = require('./lib/ddl');

/**
 * 启动即建表：schema.json 是唯一真源。
 * - 表不存在 → CREATE TABLE
 * - 表已存在 → 只做增量（加列 / 加索引），破坏性变更显式报错，不静默丢数据
 */

async function tableExists(name) {
  const rows = await db.exec(
    'SELECT 1 AS ok FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = ? LIMIT 1',
    [name]
  );
  return rows.length > 0;
}

async function columnsOf(name) {
  return db.exec(
    'SELECT COLUMN_NAME AS name FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = ?',
    [name]
  );
}

async function indexNames() {
  const rows = await db.exec(
    'SELECT DISTINCT INDEX_NAME AS name FROM information_schema.statistics WHERE table_schema = DATABASE()'
  );
  return new Set(rows.map((r) => r.name));
}

async function ensureMetaTable() {
  await db.exec(
    'CREATE TABLE IF NOT EXISTS `_schema_meta` (' +
      '`k` VARCHAR(64) NOT NULL, `v` TEXT NULL, `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, ' +
      'PRIMARY KEY (`k`)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci'
  );
}

async function ensureUsersTable() {
  await db.exec(
    'CREATE TABLE IF NOT EXISTS `users` (' +
      '`id` BIGINT NOT NULL AUTO_INCREMENT, ' +
      '`email` VARCHAR(190) NOT NULL, ' +
      '`password_hash` VARCHAR(100) NOT NULL, ' +
      '`created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, ' +
      'PRIMARY KEY (`id`), UNIQUE KEY `uk_users_email` (`email`)' +
      ') ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci'
  );
}

async function run() {
  await ensureMetaTable();
  if (schema.auth && schema.auth.enabled) {
    await ensureUsersTable();
  }

  const existingIndexes = await indexNames();
  const created = [];
  const altered = [];

  for (const [table, meta] of Object.entries(schema.tables || {})) {
    if (!(await tableExists(table))) {
      await db.exec(createTableDdl(table, meta));
      created.push(table);
      continue;
    }

    const existingColumns = new Set((await columnsOf(table)).map((r) => r.name));
    for (const [column, def] of Object.entries(meta.columns || {})) {
      if (!existingColumns.has(column)) {
        await db.exec(addColumnDdl(table, column, def));
        altered.push(`${table}.${column}`);
      }
    }
    for (const cols of Array.isArray(meta.indexes) ? meta.indexes : []) {
      if (!Array.isArray(cols) || !cols.length) continue;
      if (!existingIndexes.has(indexName(table, cols))) {
        await db.exec(createIndexDdl(table, cols));
        altered.push(`${table}(${cols.join(',')})`);
      }
    }
  }

  await db.exec(
    'INSERT INTO `_schema_meta` (`k`, `v`) VALUES (?, ?) ON DUPLICATE KEY UPDATE `v` = VALUES(`v`)',
    ['schema_version', String(schema.version || 1)]
  );

  if (created.length || altered.length) {
    console.log(`[migrate] 新建表: ${created.join(', ') || '-'}；增量变更: ${altered.join(', ') || '-'}`);
  } else {
    console.log('[migrate] 表结构已是最新');
  }
}

module.exports = { run, tableExists, columnsOf };
