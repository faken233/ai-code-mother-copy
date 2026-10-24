'use strict';

const express = require('express');
const schema = require('../schema.json');
const db = require('../db');
const auth = require('../auth');
const query = require('../lib/query');
const { sanitize } = require('../lib/validate');
const { notFound } = require('../lib/errors');

/**
 * 自动 CRUD 路由：按 schema.json 里的每张表挂一套接口。
 * 路径：/api/{table} 与 /api/{table}/:id
 * 保留路径：/api/health、/api/auth/**
 */

const RESERVED = new Set(['health', 'auth']);
const router = express.Router();

// 纯前端模式（schema.tables 为空）不连数据库，健康检查只报应用存活
const HAS_TABLES = !!(schema.tables && Object.keys(schema.tables).length);

router.get('/health', async (req, res) => {
  if (!HAS_TABLES) {
    return res.json({ ok: true, app: true, db: 'off', time: new Date().toISOString() });
  }
  try {
    await db.ping();
    res.json({ ok: true, app: true, db: 'up', time: new Date().toISOString() });
  } catch (err) {
    res.status(503).json({ ok: false, app: true, db: 'down', message: err.message });
  }
});

if (schema.auth && schema.auth.enabled) {
  router.post('/auth/register', async (req, res, next) => {
    try {
      auth.rateLimit(`register:${req.ip}`);
      const user = await auth.register(req.body && req.body.email, req.body && req.body.password);
      res.status(201).json({ token: auth.sign(user), user });
    } catch (err) {
      next(err);
    }
  });

  router.post('/auth/login', async (req, res, next) => {
    try {
      auth.rateLimit(`login:${req.ip}`);
      const user = await auth.login(req.body && req.body.email, req.body && req.body.password);
      res.json({ token: auth.sign(user), user });
    } catch (err) {
      next(err);
    }
  });

  router.get('/auth/me', auth.authRequired, (req, res) => {
    res.json({ id: req.userId });
  });
}

// 表名解析：只认 schema.json 里声明的表
router.param('table', (req, res, next, table) => {
  if (RESERVED.has(table)) return next(notFound('接口不存在'));
  const meta = (schema.tables || {})[table];
  if (!meta) return next(notFound(`未知资源：${table}`));
  req.tableName = table;
  req.tableMeta = meta;
  return next();
});

router.get('/:table', auth.optionalAuth, async (req, res, next) => {
  try {
    res.json(await query.list(req.tableMeta, req.tableName, req.query, req.userId));
  } catch (err) {
    next(err);
  }
});

router.get('/:table/:id', auth.optionalAuth, async (req, res, next) => {
  try {
    res.json(await query.get(req.tableMeta, req.tableName, req.params.id, req.userId));
  } catch (err) {
    next(err);
  }
});

router.post('/:table', auth.optionalAuth, async (req, res, next) => {
  try {
    const data = sanitize(req.tableMeta, req.body, { partial: false });
    res.status(201).json(await query.insert(req.tableMeta, req.tableName, data, req.userId));
  } catch (err) {
    next(err);
  }
});

router.put('/:table/:id', auth.optionalAuth, async (req, res, next) => {
  try {
    const data = sanitize(req.tableMeta, req.body, { partial: true });
    res.json(await query.update(req.tableMeta, req.tableName, req.params.id, data, req.userId));
  } catch (err) {
    next(err);
  }
});

router.delete('/:table/:id', auth.optionalAuth, async (req, res, next) => {
  try {
    res.json(await query.remove(req.tableMeta, req.tableName, req.params.id, req.userId));
  } catch (err) {
    next(err);
  }
});

module.exports = router;
