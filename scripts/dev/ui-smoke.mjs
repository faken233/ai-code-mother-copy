// UI 冒烟测试（CDP）：真实浏览器里走「平台登录 → 打开应用 → 点预览 → 在应用内注册并记一笔」
//
// 为什么需要它：curl 只能验证"接口按预期路径返回 200"，验证不了页面里的 JS 用了什么路径。
// 相对路径写错时，curl 全绿、浏览器全挂 —— 这个脚本专门抓这类问题。
//
// 前置：
//   1. 本地起 headless 浏览器并开 CDP：
//      "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge" \
//        --headless=new --remote-debugging-port=9222 \
//        --user-data-dir=/tmp/edge-smoke --window-size=1280,900 about:blank &
//   2. 平台前端 5173、后端 8123、目标应用已可预览
//
// 用法：node scripts/dev/ui-smoke.mjs <appId> [截图前缀]
import fs from 'node:fs';

const APP_ID = process.argv[2];
const OUT_PREFIX = process.argv[3] || '/tmp/ui-smoke';
const PLATFORM = process.env.PLATFORM || 'http://localhost:5173';
const ACCOUNT = process.env.ACCOUNT || 'cp3tester';
const PASSWORD = process.env.PASSWORD || '12345678';
const CDP = process.env.CDP || 'http://127.0.0.1:9222';

if (!APP_ID) {
  console.error('用法：node scripts/dev/ui-smoke.mjs <appId> [截图前缀]');
  process.exit(2);
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const targets = await fetch(`${CDP}/json/list`).then((r) => r.json());
const target = targets.find((t) => t.type === 'page');
if (!target) {
  console.error('没有可用的 page target，请先启动带 --remote-debugging-port 的浏览器');
  process.exit(2);
}

const ws = new WebSocket(target.webSocketDebuggerUrl);
await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej; });

let seq = 0;
const pending = new Map();
const badRequests = [];

ws.onmessage = (ev) => {
  const msg = JSON.parse(ev.data);
  if (msg.id && pending.has(msg.id)) {
    const { resolve, reject } = pending.get(msg.id);
    pending.delete(msg.id);
    msg.error ? reject(new Error(JSON.stringify(msg.error))) : resolve(msg.result);
    return;
  }
  if (msg.method === 'Network.responseReceived' && msg.params.response.status >= 400) {
    badRequests.push(`${msg.params.response.status} ${msg.params.response.url}`);
  }
};

const send = (method, params = {}) => {
  const id = ++seq;
  return new Promise((resolve, reject) => {
    pending.set(id, { resolve, reject });
    ws.send(JSON.stringify({ id, method, params }));
  });
};

const evalJs = async (expression) => {
  const r = await send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true });
  if (r.exceptionDetails) throw new Error(r.exceptionDetails.text);
  return r.result.value;
};

const waitFor = async (expression, timeoutMs = 25000) => {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    try {
      if (await evalJs(expression)) return true;
    } catch (e) { /* 页面可能仍在加载 */ }
    await sleep(400);
  }
  return false;
};

const screenshot = async (path) => {
  const r = await send('Page.captureScreenshot', { format: 'png' });
  fs.writeFileSync(path, Buffer.from(r.data, 'base64'));
  console.log('   截图:', path);
};

await send('Page.enable');
await send('Runtime.enable');
await send('Network.enable');
await send('Emulation.setDeviceMetricsOverride', { width: 1280, height: 900, deviceScaleFactor: 1, mobile: false });

console.log('1) 平台登录');
await send('Page.navigate', { url: `${PLATFORM}/user/login` });
await sleep(2500);
const login = await evalJs(`fetch('/api/user/login',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({userAccount:${JSON.stringify(ACCOUNT)},userPassword:${JSON.stringify(PASSWORD)}})}).then(r=>r.json())`);
if (login.code !== 0) {
  console.error('   登录失败:', login);
  process.exit(1);
}
console.log('   ok, userId =', login.data.id);

console.log('2) 打开应用并点预览');
await send('Page.navigate', { url: `${PLATFORM}/app/chat/${APP_ID}` });
const hasButton = await waitFor(`[...document.querySelectorAll('button')].some(b=>b.innerText.trim()==='预览')`, 25000);
if (!hasButton) {
  console.error('   找不到「预览」按钮');
  await screenshot(`${OUT_PREFIX}-fail.png`);
  process.exit(1);
}
await evalJs(`[...document.querySelectorAll('button')].find(b=>b.innerText.trim()==='预览')?.click()`);
const hasIframe = await waitFor(`(()=>{const f=document.querySelector('iframe.preview-iframe');return !!(f&&f.src)})()`, 40000);
if (!hasIframe) {
  console.error('   iframe 未出现');
  await screenshot(`${OUT_PREFIX}-fail.png`);
  process.exit(1);
}
const src = await evalJs(`(()=>{const f=document.querySelector('iframe.preview-iframe');return f?f.src:''})()`);
console.log('   iframe =', src);
await sleep(5000);
await screenshot(`${OUT_PREFIX}-platform.png`);

console.log('3) 在应用内注册并记一笔');
await send('Page.navigate', { url: src });
await sleep(3000);
// 浏览器里可能已保存上一个账号的 token，已登录就直接用，避免找不到登录表单
const appLoggedIn = await waitFor(`document.body.innerText.includes('记一笔')`, 2500);
if (!appLoggedIn) {
  const email = `smoke${Date.now()}@demo.com`;
  await evalJs(`[...document.querySelectorAll('button')].find(b=>b.innerText.trim()==='注册')?.click()`);
  await sleep(400);
  await evalJs(`
  (() => {
    const setVal = (el, v) => {
      const s = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
      s.call(el, v); el.dispatchEvent(new Event('input', { bubbles: true }));
    };
    setVal(document.querySelector('input[type=email]'), ${JSON.stringify(email)});
    setVal(document.querySelector('input[type=password]'), 'pass123456');
    [...document.querySelectorAll('button')].find(b => b.type === 'submit')?.click();
    return true;
  })()`);
}
const loggedIn = await waitFor(`document.body.innerText.includes('记一笔')`, 20000);
if (!loggedIn) {
  console.error('   注册/登录后未进入主界面');
  await screenshot(`${OUT_PREFIX}-fail.png`);
  process.exit(1);
}

await evalJs(`
(() => {
  const setVal = (el, v) => {
    const s = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
    s.call(el, v); el.dispatchEvent(new Event('input', { bubbles: true }));
  };
  const inputs = [...document.querySelectorAll('input')];
  setVal(inputs.find(i => i.placeholder && i.placeholder.includes('标题')), 'UI 冒烟记录');
  setVal(inputs.find(i => i.type === 'number'), '12.5');
  return true;
})()`);
await sleep(300);
await evalJs(`[...document.querySelectorAll('button')].find(b=>b.innerText.trim()==='保存')?.click()`);
const added = await waitFor(`document.body.innerText.includes('UI 冒烟记录')`, 15000);
await sleep(800);
await screenshot(`${OUT_PREFIX}-app.png`);

const appFailures = badRequests.filter((r) => r.includes('/api/preview/'));
console.log('4) 结果');
console.log('   记账成功:', added);
if (appFailures.length) console.log('   应用侧失败请求:', [...new Set(appFailures)].slice(0, 8));
else console.log('   应用侧无 4xx/5xx');

ws.close();
process.exit(added && appFailures.length === 0 ? 0 : 1);
