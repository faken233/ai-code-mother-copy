// 代码沙箱 HTTP 服务
// 提供文件读写/修改/删除/列目录 + 命令执行，路径限制在 /workspace 内
const http = require('http');
const crypto = require('crypto');
const fs = require('fs');
const fsp = require('fs/promises');
const path = require('path');
const { spawn } = require('child_process');

const PORT = process.env.PORT || 8080;
const WORKSPACE = '/workspace';
// 一体化应用的运行时端口与日志（与 start.sh / 运行时 config.js 保持一致）
const APP_PORT = Number(process.env.APP_PORT) || 3000;
const APP_LOG = '/tmp/app.log';
const APP_PID_FILE = '/tmp/app.pid';

// 工具通道令牌：容器启动时由主服务按 HMAC(secret, appId) 计算后以 TOOL_TOKEN 注入。
// 除 /api/health 外所有接口都必须带 X-Tool-Token，避免本机任意进程通过映射端口执行命令。
const TOOL_TOKEN = process.env.TOOL_TOKEN || '';
if (!TOOL_TOKEN) {
  console.warn('[code-sandbox] 未注入 TOOL_TOKEN：工具通道将拒绝除 /api/health 外的所有请求');
}

// 允许执行的命令前缀白名单（B7 安全要点）
const ALLOWED_COMMANDS = ['npm', 'mvn', 'java', 'node', 'javac', 'sh', 'bash'];

// 路径归一化并限制在 /workspace 内，防止路径穿越
function resolveSafePath(relativePath) {
  const resolved = path.normalize(path.join(WORKSPACE, relativePath || ''));
  if (!resolved.startsWith(WORKSPACE)) {
    const err = new Error('非法路径：超出工作区范围');
    err.code = 'FORBIDDEN_PATH';
    throw err;
  }
  return resolved;
}

function sendJson(res, status, data) {
  const body = JSON.stringify(data);
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(body),
  });
  res.end(body);
}

function sendOk(res, data) {
  sendJson(res, 200, { ok: true, data });
}

function sendError(res, status, message) {
  sendJson(res, status, { ok: false, error: message });
}

// 读取请求体（限制大小，防止恶意大请求）
function readBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    const MAX_BODY = 20 * 1024 * 1024; // 20MB
    req.on('data', (chunk) => {
      size += chunk.length;
      if (size > MAX_BODY) {
        reject(new Error('请求体过大'));
        req.destroy();
        return;
      }
      chunks.push(chunk);
    });
    req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
    req.on('error', reject);
  });
}

// 执行命令
function runCommand(command, args, cwd, timeoutMs) {
  return new Promise((resolve) => {
    let allowed = false;
    const cmd = String(command || '').toLowerCase();
    for (const prefix of ALLOWED_COMMANDS) {
      if (cmd === prefix || cmd.startsWith(prefix + '/')) {
        allowed = true;
        break;
      }
    }
    if (!allowed) {
      return resolve({ ok: false, code: -1, stdout: '', stderr: '命令不被允许: ' + command });
    }

    const child = spawn(command, args, { cwd, shell: false });
    let stdout = '';
    let stderr = '';
    let timer = null;
    let finished = false;

    const finish = (timedOut) => {
      if (finished) return;
      finished = true;
      if (timer) clearTimeout(timer);
      resolve({ ok: true, code: child.exitCode ?? -1, stdout, stderr, timedOut: !!timedOut });
    };

    child.stdout.on('data', (d) => { stdout += d.toString(); });
    child.stderr.on('data', (d) => { stderr += d.toString(); });
    child.on('close', () => finish(false));
    child.on('error', (err) => {
      finished = true;
      if (timer) clearTimeout(timer);
      resolve({ ok: true, code: -1, stdout: '', stderr: '启动命令失败: ' + err.message });
    });

    if (timeoutMs > 0) {
      timer = setTimeout(() => {
        child.kill('SIGKILL');
        finish(true);
      }, timeoutMs);
    }
  });
}

// ---------- 应用进程管理（对话热更新用） ----------

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function readAppPid() {
  try {
    const pid = Number(fs.readFileSync(APP_PID_FILE, 'utf8').trim());
    return Number.isInteger(pid) && pid > 1 ? pid : null;
  } catch {
    return null;
  }
}

function tailAppLog(lines = 40) {
  try {
    return fs.readFileSync(APP_LOG, 'utf8').trim().split('\n').slice(-lines).join('\n');
  } catch {
    return '';
  }
}

// 应用通道健康检查：必须命中 "app":true，避免把工具通道自己当成应用
function isAppAlive() {
  return new Promise((resolve) => {
    const req = http.get({
      host: '127.0.0.1',
      port: APP_PORT,
      path: '/api/health',
      timeout: 2000,
    }, (res) => {
      let body = '';
      res.on('data', (chunk) => { body += chunk; });
      res.on('end', () => resolve(res.statusCode === 200 && body.includes('"app":true')));
    });
    req.on('error', () => resolve(false));
    req.on('timeout', () => { req.destroy(); resolve(false); });
  });
}

async function waitAppReady(timeoutMs) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (await isAppAlive()) return true;
    await sleep(500);
  }
  return false;
}

// 停掉运行中的应用。pid 文件可能缺失（容器重启 / 主服务用 docker exec 启动过），
// 所以除了 pid 文件还要扫 /proc 按 cmdline 兜底；应用是异步收尾，等不到再 SIGKILL。
// 不用 pkill：基础镜像不保证装了 procps。
function findAppPids() {
  const pids = new Set();
  const pidFromFile = readAppPid();
  if (pidFromFile) pids.add(pidFromFile);
  try {
    for (const entry of fs.readdirSync('/proc')) {
      if (!/^\d+$/.test(entry)) continue;
      const pid = Number(entry);
      if (pid === process.pid) continue;
      try {
        const argv = fs.readFileSync('/proc/' + entry + '/cmdline', 'utf8').split('\0').filter(Boolean);
        if (argv.some((arg) => arg.endsWith('server.js'))) pids.add(pid);
      } catch { /* 进程已退出或无权限 */ }
    }
  } catch { /* 没有 /proc 时只靠 pid 文件 */ }
  return [...pids];
}

async function stopAppProcess() {
  for (const pid of findAppPids()) {
    try { process.kill(pid, 'SIGTERM'); } catch { /* 已退出 */ }
  }

  const deadline = Date.now() + 10000;
  while (Date.now() < deadline) {
    if (!(await isAppAlive())) return true;
    await sleep(300);
  }

  for (const pid of findAppPids()) {
    try { process.kill(pid, 'SIGKILL'); } catch { /* 已退出 */ }
  }
  return !(await isAppAlive());
}

function startAppProcess() {
  const logFd = fs.openSync(APP_LOG, 'a');
  const child = spawn('node', ['server.js'], {
    cwd: WORKSPACE,
    detached: true,
    stdio: ['ignore', logFd, logFd],
  });
  child.unref();
  fs.writeFileSync(APP_PID_FILE, String(child.pid));
  fs.closeSync(logFd);
  return child.pid;
}

async function restartApp() {
  const stopped = await stopAppProcess();
  const pid = startAppProcess();
  const ready = await waitAppReady(60000);
  if (!ready) {
    const err = new Error('应用重启后未在 60 秒内就绪\n' + tailAppLog(40));
    err.status = 503;
    throw err;
  }
  return { stopped, pid, ready };
}

/**
 * 重建 + 重启应用。
 * forceBuild=true 时执行 npm run build；false 时只校验产物（生成流程已由平台构建过）。
 */
async function rebuildApp(options) {
  const forceBuild = !options || options.forceBuild !== false;
  const distIndex = path.join(WORKSPACE, 'public', 'dist', 'index.html');
  const needBuild = forceBuild || !fs.existsSync(distIndex);

  let built = false;
  if (needBuild) {
    const result = await runCommand('npm', ['run', 'build'], WORKSPACE, 180000);
    if (result.code !== 0) {
      const err = new Error('npm run build 失败（exit=' + result.code + '）\n'
        + (result.stderr || result.stdout || '').slice(-4000));
      err.status = 500;
      throw err;
    }
    built = true;
  }

  const check = await runCommand('node', ['--check', 'server.js'], WORKSPACE, 30000);
  if (check.code !== 0) {
    const err = new Error('node --check server.js 失败\n' + (check.stderr || check.stdout || ''));
    err.status = 500;
    throw err;
  }

  const state = await restartApp();
  return { built, checked: true, ...state };
}

// 校验工具通道令牌（常量时间比较）
function verifyToolToken(req) {
  const got = req.headers['x-tool-token'];
  if (typeof got !== 'string' || got.length === 0) return false;
  const a = Buffer.from(got);
  const b = Buffer.from(TOOL_TOKEN);
  return a.length === b.length && crypto.timingSafeEqual(a, b);
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://localhost:${PORT}`);
  const route = url.pathname;

  // 健康检查（唯一免鉴权接口，探活/容器就绪判断依赖它）
  if (route === '/api/health' && req.method === 'GET') {
    return sendOk(res, { status: 'ok', workspace: WORKSPACE });
  }

  // 工具通道鉴权：容器未注入 token 时直接拒绝，避免"忘配就裸奔"
  if (!TOOL_TOKEN) {
    return sendError(res, 503, '工具通道未配置 TOOL_TOKEN，拒绝服务');
  }
  if (!verifyToolToken(req)) {
    return sendError(res, 401, '缺少或非法的 X-Tool-Token');
  }

  if (req.method !== 'POST') {
    return sendError(res, 405, '仅支持 POST 请求');
  }

  try {
    const bodyText = await readBody(req);
    let body = {};
    if (bodyText) {
      try { body = JSON.parse(bodyText); } catch { return sendError(res, 400, '请求体不是合法 JSON'); }
    }

    switch (route) {
      case '/api/files/write': {
        const target = resolveSafePath(body.relativeFilePath);
        await fsp.mkdir(path.dirname(target), { recursive: true });
        await fsp.writeFile(target, body.content || '', 'utf8');
        return sendOk(res, { path: body.relativeFilePath });
      }
      case '/api/files/read': {
        const target = resolveSafePath(body.relativeFilePath);
        const stat = await fsp.stat(target).catch(() => null);
        if (!stat || !stat.isFile()) return sendError(res, 404, '文件不存在或不是文件: ' + body.relativeFilePath);
        const content = await fsp.readFile(target, 'utf8');
        return sendOk(res, { path: body.relativeFilePath, content });
      }
      case '/api/files/modify': {
        const target = resolveSafePath(body.relativeFilePath);
        const stat = await fsp.stat(target).catch(() => null);
        if (!stat || !stat.isFile()) return sendError(res, 404, '文件不存在或不是文件: ' + body.relativeFilePath);
        const original = await fsp.readFile(target, 'utf8');
        if (!original.includes(body.oldContent)) return sendError(res, 400, '文件中未找到要替换的内容');
        const modified = original.split(body.oldContent).join(body.newContent || '');
        await fsp.writeFile(target, modified, 'utf8');
        return sendOk(res, { path: body.relativeFilePath });
      }
      case '/api/files/delete': {
        const target = resolveSafePath(body.relativeFilePath);
        const stat = await fsp.stat(target).catch(() => null);
        if (!stat) return sendError(res, 404, '文件不存在，无需删除');
        if (!stat.isFile()) return sendError(res, 400, '指定路径不是文件');
        await fsp.unlink(target);
        return sendOk(res, { path: body.relativeFilePath });
      }
      case '/api/files/list': {
        const target = resolveSafePath(body.relativeDirPath || '');
        const stat = await fsp.stat(target).catch(() => null);
        if (!stat || !stat.isDirectory()) return sendError(res, 404, '目录不存在或不是目录: ' + body.relativeDirPath);
        const entries = await collectFiles(target);
        return sendOk(res, { root: body.relativeDirPath || '', files: entries });
      }
      case '/api/exec': {
        const cwd = resolveSafePath(body.cwd || '');
        const result = await runCommand(body.cmd, body.args || [], cwd, body.timeoutMs || 0);
        return sendJson(res, 200, { ok: true, data: result });
      }
      // 纯重启：进程重拉，打包产物与 schema 不变（schema 变更在启动时 migrate）
      case '/api/app/restart': {
        return sendOk(res, await restartApp());
      }
      // 重建：可选前端构建 + 服务端语法检查 + 重启（对话热更新走这里）
      case '/api/app/rebuild': {
        return sendOk(res, await rebuildApp(body));
      }
      default:
        return sendError(res, 404, '未知接口: ' + route);
    }
  } catch (e) {
    const status = e.status
      || (e.code === 'FORBIDDEN_PATH' ? 403 : (e.code === 'ENOENT' ? 404 : 500));
    return sendError(res, status, e.message);
  }
});

// 递归收集文件列表（相对路径）
async function collectFiles(dir) {
  const out = [];
  async function walk(current) {
    let entries;
    try { entries = await fsp.readdir(current, { withFileTypes: true }); } catch { return; }
    for (const entry of entries) {
      if (entry.name === 'node_modules' || entry.name === '.git' || entry.name === 'dist' || entry.name === 'target' || entry.name === '.DS_Store') continue;
      const full = path.join(current, entry.name);
      const rel = path.relative(WORKSPACE, full).split(path.sep).join('/');
      if (entry.isDirectory()) {
        out.push({ type: 'dir', path: rel });
        await walk(full);
      } else if (entry.isFile()) {
        out.push({ type: 'file', path: rel });
      }
    }
  }
  await walk(dir);
  return out;
}

server.listen(PORT, () => {
  console.log(`[code-sandbox] listening on :${PORT}, workspace=${WORKSPACE}`);
});
