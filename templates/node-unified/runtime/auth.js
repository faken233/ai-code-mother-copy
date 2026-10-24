'use strict';

const bcrypt = require('bcryptjs');
const jwt = require('jsonwebtoken');
const config = require('./config');
const db = require('./db');
const { badRequest, unauthorized, conflict, tooMany } = require('./lib/errors');

/**
 * 登录体系：平台提供，所有应用同一份实现。
 * 密码 bcrypt 哈希、JWT 无状态、限流在内存（不引入 Redis）。
 */

const WINDOW_MS = 60 * 1000;
const MAX_ATTEMPTS = 10;
const attempts = new Map();

function rateLimit(key) {
  const now = Date.now();
  const recent = (attempts.get(key) || []).filter((t) => now - t < WINDOW_MS);
  if (recent.length >= MAX_ATTEMPTS) {
    throw tooMany('尝试过于频繁，请稍后再试');
  }
  recent.push(now);
  attempts.set(key, recent);
}

function sign(user) {
  return jwt.sign({ sub: String(user.id), email: user.email }, config.jwtSecret, {
    expiresIn: config.jwtExpiresIn
  });
}

function normalizeEmail(value) {
  return String(value || '').trim().toLowerCase();
}

async function register(email, password) {
  const normalized = normalizeEmail(email);
  if (!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(normalized)) {
    throw badRequest('邮箱格式不正确');
  }
  if (typeof password !== 'string' || password.length < 6) {
    throw badRequest('密码至少 6 位');
  }
  const exists = await db.exec('SELECT `id` FROM `users` WHERE `email` = ? LIMIT 1', [normalized]);
  if (exists.length) throw conflict('该邮箱已注册');

  const passwordHash = await bcrypt.hash(password, 10);
  const result = await db.exec('INSERT INTO `users` (`email`, `password_hash`) VALUES (?, ?)', [
    normalized,
    passwordHash
  ]);
  return { id: result.insertId, email: normalized };
}

async function login(email, password) {
  const normalized = normalizeEmail(email);
  const rows = await db.exec(
    'SELECT `id`, `email`, `password_hash` FROM `users` WHERE `email` = ? LIMIT 1',
    [normalized]
  );
  if (!rows.length) throw unauthorized('邮箱或密码错误');
  const ok = await bcrypt.compare(String(password || ''), rows[0].password_hash);
  if (!ok) throw unauthorized('邮箱或密码错误');
  return { id: rows[0].id, email: rows[0].email };
}

function readToken(req) {
  const header = req.headers.authorization || '';
  const matched = header.match(/^Bearer\s+(.+)$/i);
  return matched ? matched[1] : null;
}

/** 必须登录 */
function authRequired(req, res, next) {
  const token = readToken(req);
  if (!token) return next(unauthorized());
  try {
    const payload = jwt.verify(token, config.jwtSecret);
    req.userId = Number(payload.sub);
    return next();
  } catch (err) {
    return next(unauthorized('登录状态无效或已过期'));
  }
}

/** 有 token 就解析，没有也放行（公开读的表需要） */
function optionalAuth(req, res, next) {
  const token = readToken(req);
  if (!token) return next();
  try {
    const payload = jwt.verify(token, config.jwtSecret);
    req.userId = Number(payload.sub);
  } catch (err) {
    /* 忽略无效 token，按未登录处理 */
  }
  return next();
}

module.exports = { register, login, sign, rateLimit, authRequired, optionalAuth };
