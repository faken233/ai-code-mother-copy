'use strict';

const db = require('../db');
const { badRequest, unauthorized, notFound, methodNotAllowed } = require('./errors');

/**
 * 查询构建器：唯一允许拼 SQL 的地方。
 * 三条铁律：
 *   1. 表名、列名只来自 schema.json（白名单），不来自请求；
 *   2. 值一律走 ? 占位符；
 *   3. 归属条件（user_id）由 scope() 无条件注入，业务代码无需也无法省略。
 */

const OPS = { eq: '=', ne: '<>', gt: '>', gte: '>=', lt: '<', lte: '<=', like: 'LIKE', in: 'IN' };
const FILTER_RE = /^([A-Za-z_][A-Za-z0-9_]*?)(?:_(eq|ne|gt|gte|lt|lte|like|in))?$/;
const SYSTEM_SORTABLE = new Set(['id', 'created_at', 'updated_at', 'user_id']);
const PAGING_KEYS = new Set(['page', 'pageSize', 'size', 'sort']);

function accessRule(meta, mode) {
  const access = meta.access || {};
  if (access[mode]) return access[mode];
  // 默认即安全：owner 表默认只有本人可读写；公共表默认公开只读
  if (mode === 'read') return meta.owner === false ? 'public' : 'owner';
  return meta.owner === false ? 'public' : 'owner';
}

/** 返回 { sql, values }；sql 为空串表示不限制 */
function scope(meta, mode, userId) {
  const rule = accessRule(meta, mode);
  if (rule === 'none') throw methodNotAllowed();
  if (rule === 'public') return { sql: '', values: [] };
  if (!userId) throw unauthorized();
  if (rule === 'user') return { sql: '', values: [] };
  return { sql: '`user_id` = ?', values: [userId] };
}

function buildFilters(meta, query) {
  const sql = [];
  const values = [];
  for (const [key, raw] of Object.entries(query || {})) {
    if (PAGING_KEYS.has(key)) continue;
    const m = String(key).match(FILTER_RE);
    if (!m) continue;
    const column = m[1];
    const op = m[2] || 'eq';
    if (!Object.prototype.hasOwnProperty.call(meta.columns || {}, column)) continue; // 未声明列直接忽略
    if (op === 'in') {
      const list = String(raw).split(',').map((s) => s.trim()).filter(Boolean);
      if (!list.length) continue;
      sql.push(`\`${column}\` IN (${list.map(() => '?').join(',')})`);
      values.push(...list);
    } else if (op === 'like') {
      sql.push(`\`${column}\` LIKE ?`);
      values.push(`%${raw}%`);
    } else {
      sql.push(`\`${column}\` ${OPS[op]} ?`);
      values.push(raw);
    }
  }
  return { sql, values };
}

function buildOrder(meta, sortRaw) {
  const sort = String(sortRaw || meta.sortDefault || '-created_at');
  const parts = sort.split(',').map((s) => s.trim()).filter(Boolean).slice(0, 3);
  const out = [];
  for (const part of parts) {
    const desc = part.startsWith('-');
    const column = desc ? part.slice(1) : part;
    if (!/^[A-Za-z_][A-Za-z0-9_]*$/.test(column)) throw badRequest('排序字段非法');
    const allowed = Object.prototype.hasOwnProperty.call(meta.columns || {}, column) || SYSTEM_SORTABLE.has(column);
    if (!allowed) throw badRequest(`不支持按 ${column} 排序`);
    out.push(`\`${column}\` ${desc ? 'DESC' : 'ASC'}`);
  }
  return out.length ? out.join(', ') : '`created_at` DESC';
}

function buildWhere(parts) {
  const sql = parts.filter((p) => p && p.length).join(' AND ');
  return sql ? `WHERE ${sql}` : '';
}

async function list(meta, table, query, userId) {
  const s = scope(meta, 'read', userId);
  const filters = buildFilters(meta, query);
  const where = buildWhere([s.sql, ...filters.sql]);
  const order = buildOrder(meta, query.sort);

  const page = Math.max(1, Number.parseInt(query.page, 10) || 1);
  const maxPageSize = Number(meta.list && meta.list.maxPageSize) || 50;
  const pageSize = Math.min(maxPageSize, Math.max(1, Number.parseInt(query.pageSize || query.size, 10) || 20));
  const offset = (page - 1) * pageSize;

  // LIMIT/OFFSET 用已校验的整数直接内联（MySQL 预处理协议对 LIMIT ? 兼容性不稳）
  const values = [...s.values, ...filters.values];
  const countRows = await db.exec(`SELECT COUNT(*) AS c FROM \`${table}\` ${where}`, values);
  const items = await db.exec(
    `SELECT * FROM \`${table}\` ${where} ORDER BY ${order} LIMIT ${pageSize} OFFSET ${offset}`,
    values
  );
  return { items, total: Number(countRows[0].c), page, pageSize };
}

async function get(meta, table, id, userId) {
  const s = scope(meta, 'read', userId);
  const where = buildWhere([s.sql]);
  const rows = await db.exec(
    `SELECT * FROM \`${table}\` WHERE \`id\` = ?${where ? ` AND ${where.slice(6)}` : ''} LIMIT 1`,
    [id, ...s.values]
  );
  if (!rows.length) throw notFound();
  return rows[0];
}

async function insert(meta, table, data, userId) {
  const s = scope(meta, 'create', userId);
  const row = Object.assign({}, data);
  if (s.sql) row.user_id = userId; // 归属由服务端写入，请求体无法覆盖
  const columns = Object.keys(row);
  if (!columns.length) throw badRequest('没有可写入的字段');
  const placeholders = columns.map(() => '?').join(',');
  const result = await db.exec(
    `INSERT INTO \`${table}\` (${columns.map((c) => `\`${c}\``).join(',')}) VALUES (${placeholders})`,
    columns.map((c) => row[c])
  );
  return get(meta, table, result.insertId, userId);
}

async function update(meta, table, id, data, userId) {
  const s = scope(meta, 'update', userId);
  const columns = Object.keys(data);
  if (!columns.length) throw badRequest('没有可更新的字段');
  const sets = columns.map((c) => `\`${c}\` = ?`).join(', ');
  const where = buildWhere([s.sql]);
  const result = await db.exec(
    `UPDATE \`${table}\` SET ${sets} WHERE \`id\` = ?${where ? ` AND ${where.slice(6)}` : ''}`,
    [...columns.map((c) => data[c]), id, ...s.values]
  );
  if (!result.affectedRows) throw notFound(); // 不是自己的记录 → 404，不泄露存在性
  return get(meta, table, id, userId);
}

async function remove(meta, table, id, userId) {
  const s = scope(meta, 'delete', userId);
  const where = buildWhere([s.sql]);
  const result = await db.exec(
    `DELETE FROM \`${table}\` WHERE \`id\` = ?${where ? ` AND ${where.slice(6)}` : ''}`,
    [id, ...s.values]
  );
  if (!result.affectedRows) throw notFound();
  return { deleted: 1 };
}

module.exports = { list, get, insert, update, remove, scope, buildFilters, buildOrder, accessRule };
