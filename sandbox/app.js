// 代码沙箱 HTTP 服务
// 提供文件读写/修改/删除/列目录 + 命令执行，路径限制在 /workspace 内
const http = require('http');
const fs = require('fs');
const fsp = require('fs/promises');
const path = require('path');
const { exec, execFile, spawn } = require('child_process');

const PORT = process.env.PORT || 8080;
const WORKSPACE = '/workspace';

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

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://localhost:${PORT}`);
  const route = url.pathname;

  // 健康检查
  if (route === '/api/health' && req.method === 'GET') {
    return sendOk(res, { status: 'ok', workspace: WORKSPACE });
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
      default:
        return sendError(res, 404, '未知接口: ' + route);
    }
  } catch (e) {
    const status = e.code === 'FORBIDDEN_PATH' ? 403 : (e.code === 'ENOENT' ? 404 : 500);
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
