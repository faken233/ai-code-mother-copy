'use strict';

const { badRequest } = require('./errors');

/**
 * 按 schema.json 的声明做入参校验与清洗。
 * 关键点：请求体里不认识的字段一律丢弃（包括 id / user_id），
 * 所以"客户端传 user_id 覆盖归属"在结构上不可能发生。
 */

function toDateTime(value) {
  if (typeof value !== 'string') throw badRequest('时间字段必须是字符串');
  const s = value.trim().replace('T', ' ').replace(/Z$/, '').replace(/\.\d+$/, '');
  if (!/^\d{4}-\d{2}-\d{2}(?: \d{2}:\d{2}(?::\d{2})?)?$/.test(s)) {
    throw badRequest(`时间格式不正确: ${value}`);
  }
  return s.length === 16 ? `${s}:00` : s;
}

function toDate(value) {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(value.trim())) {
    throw badRequest(`日期格式不正确: ${value}`);
  }
  return value.trim();
}

function coerce(name, c, raw) {
  switch (c.type) {
    case 'string':
    case 'text': {
      if (typeof raw !== 'string') throw badRequest(`字段 ${name} 必须是字符串`);
      if (c.max && raw.length > c.max) throw badRequest(`字段 ${name} 长度不能超过 ${c.max}`);
      return raw;
    }
    case 'int':
    case 'bigint': {
      const n = Number(raw);
      if (!Number.isInteger(n)) throw badRequest(`字段 ${name} 必须是整数`);
      if (c.min != null && n < Number(c.min)) throw badRequest(`字段 ${name} 不能小于 ${c.min}`);
      if (c.max != null && n > Number(c.max)) throw badRequest(`字段 ${name} 不能大于 ${c.max}`);
      return n;
    }
    case 'decimal': {
      const n = Number(raw);
      if (!Number.isFinite(n)) throw badRequest(`字段 ${name} 必须是数字`);
      if (c.min != null && n < Number(c.min)) throw badRequest(`字段 ${name} 不能小于 ${c.min}`);
      if (c.max != null && n > Number(c.max)) throw badRequest(`字段 ${name} 不能大于 ${c.max}`);
      const scale = Array.isArray(c.precision) ? Number(c.precision[1]) || 2 : 2;
      return Number(n.toFixed(scale));
    }
    case 'bool':
      if (raw === true || raw === 1 || raw === '1' || raw === 'true') return 1;
      if (raw === false || raw === 0 || raw === '0' || raw === 'false') return 0;
      throw badRequest(`字段 ${name} 必须是布尔值`);
    case 'enum': {
      const values = c.values || [];
      if (!values.includes(raw)) throw badRequest(`字段 ${name} 只能是：${values.join(' / ')}`);
      return raw;
    }
    case 'date':
      return toDate(raw);
    case 'datetime':
      return toDateTime(raw);
    case 'json':
      return typeof raw === 'string' ? raw : JSON.stringify(raw);
    default:
      throw badRequest(`字段 ${name} 的类型不受支持: ${c.type}`);
  }
}

/**
 * @param {object} meta  表声明
 * @param {object} body  请求体
 * @param {{partial?: boolean}} options  partial=true 用于 PUT（允许只传部分字段）
 */
function sanitize(meta, body, options = {}) {
  const partial = options.partial === true;
  if (!body || typeof body !== 'object' || Array.isArray(body)) {
    throw badRequest('请求体必须是 JSON 对象');
  }
  const out = {};
  for (const [name, c] of Object.entries(meta.columns || {})) {
    const has = Object.prototype.hasOwnProperty.call(body, name);
    const raw = body[name];
    const empty = raw === undefined || raw === null || raw === '';

    if (!has || empty) {
      if (c.required && !partial) throw badRequest(`缺少必填字段：${name}`);
      if (has && empty && !c.required) out[name] = null;
      continue;
    }
    out[name] = coerce(name, c, raw);
  }
  return out;
}

module.exports = { sanitize, coerce };
