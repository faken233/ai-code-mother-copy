'use strict';

const path = require('path');
const express = require('express');
const config = require('./config');
const migrate = require('./migrate');
const apiRouter = require('./routes/api');
const { ApiError } = require('./lib/errors');

/**
 * 一体化应用入口：一个进程、一个端口。
 *   /api/**            → REST（含健康检查）
 *   public/dist/**     → 前端构建产物
 *   其它路径           → index.html（SPA 兜底，刷新不 404）
 */

const app = express();
app.disable('x-powered-by');
app.set('trust proxy', true);
app.use(express.json({ limit: config.bodyLimit }));

app.use('/api', apiRouter);

const distDir = path.join(__dirname, 'public', 'dist');
app.use(express.static(distDir, { index: false, maxAge: '1h' }));
app.get('*', (req, res, next) => {
  if (req.path.startsWith('/api/')) return next();
  return res.sendFile(path.join(distDir, 'index.html'));
});

app.use('/api', (req, res) => {
  res.status(404).json({ error: { code: 'NOT_FOUND', message: '接口不存在' } });
});

// 统一错误出口：对外形状稳定，内部错误打日志不泄露堆栈
app.use((err, req, res, next) => {
  if (err instanceof ApiError) {
    return res.status(err.status).json({ error: { code: err.code, message: err.message } });
  }
  if (err && err.type === 'entity.parse.failed') {
    return res.status(400).json({ error: { code: 'BAD_REQUEST', message: '请求体不是合法 JSON' } });
  }
  console.error('[app] 未处理异常:', err);
  return res.status(500).json({ error: { code: 'INTERNAL', message: '服务器内部错误' } });
});

// 纯前端模式：schema.tables 为空，不建表也不连库
const HAS_TABLES = !!(require('./schema.json').tables && Object.keys(require('./schema.json').tables).length);

async function main() {
  if (HAS_TABLES) {
    await migrate.run();
  } else {
    console.log('[app] schema 未声明数据表，跳过建表（纯前端模式，无数据库依赖）');
  }
  const server = app.listen(config.port, () => {
    console.log(`[app] listening on :${config.port}  db=${config.db.database}@${config.db.host}:${config.db.port}`);
  });
  const shutdown = (signal) => {
    console.log(`[app] ${signal} received, closing...`);
    server.close(() => process.exit(0));
    setTimeout(() => process.exit(0), 5000).unref();
  };
  process.on('SIGTERM', () => shutdown('SIGTERM'));
  process.on('SIGINT', () => shutdown('SIGINT'));
}

main().catch((err) => {
  console.error('[app] 启动失败:', err);
  process.exit(1);
});
