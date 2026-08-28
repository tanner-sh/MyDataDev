/**
 * 界面冒烟：用系统自带的 Chrome 把应用真的打开一遍，逐屏检查关键区域渲染出来了。
 *
 * 为什么不是 Playwright：它要下载一份约 150MB 的浏览器，而这个仓库一贯不为一次检查引依赖。
 * Chrome DevTools Protocol 走 WebSocket，Node 自带，够用。
 *
 * 用法（需要先把前后端跑起来）：
 *   cd backend && DB_ADMIN_CRYPTO_KEY=... mvn spring-boot:run
 *   cd frontend && npm run dev
 *   node scripts/ui-smoke.mjs [--url http://localhost:5173] [--shots ./ui-shots]
 *
 * 检查项刻意只覆盖「结构还在不在」：抽屉能不能开、六个管理分区能不能切、结果区能不能出。
 * 视觉细节靠人看截图 —— 脚本不该假装自己能判断好不好看。
 */
import { mkdirSync, writeFileSync } from 'node:fs';
import { spawn } from 'node:child_process';

const CHROME = process.env.CHROME_PATH
  || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const args = process.argv.slice(2);
const option = (name, fallback) => {
  const index = args.indexOf(name);
  return index >= 0 && args[index + 1] ? args[index + 1] : fallback;
};
const APP_URL = option('--url', 'http://localhost:5173');
const SHOT_DIR = option('--shots', '');
const PORT = 9333;

const failures = [];
const check = (name, ok, detail = '') => {
  if (ok) console.log(`  ✓ ${name}`);
  else {
    console.log(`  ✗ ${name}${detail ? ` — ${detail}` : ''}`);
    failures.push(name);
  }
};

async function connect() {
  for (let attempt = 0; attempt < 40; attempt += 1) {
    try {
      const list = await (await fetch(`http://127.0.0.1:${PORT}/json/list`)).json();
      const page = list.find((target) => target.type === 'page');
      if (page?.webSocketDebuggerUrl) return page.webSocketDebuggerUrl;
    } catch {
      // Chrome 还没起来
    }
    await new Promise((resolve) => setTimeout(resolve, 500));
  }
  throw new Error('无法连接到 Chrome 调试端口，检查 CHROME_PATH 是否正确');
}

async function session(url) {
  const socket = new WebSocket(url);
  await new Promise((resolve, reject) => {
    socket.onopen = resolve;
    socket.onerror = reject;
  });
  let id = 0;
  const pending = new Map();
  socket.onmessage = (event) => {
    const message = JSON.parse(event.data);
    const entry = pending.get(message.id);
    if (!entry) return;
    pending.delete(message.id);
    message.error ? entry.reject(new Error(JSON.stringify(message.error))) : entry.resolve(message.result);
  };
  const send = (method, params = {}) => new Promise((resolve, reject) => {
    id += 1;
    pending.set(id, { resolve, reject });
    socket.send(JSON.stringify({ id, method, params }));
  });
  await send('Page.enable');
  await send('Runtime.enable');
  return {
    send,
    close: () => socket.close(),
    sleep: (ms) => new Promise((resolve) => setTimeout(resolve, ms)),
    async evaluate(expression) {
      const result = await send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true });
      if (result.exceptionDetails) throw new Error(`${result.exceptionDetails.text} :: ${expression}`);
      return result.result.value;
    },
    async shot(name) {
      if (!SHOT_DIR) return;
      const { data } = await send('Page.captureScreenshot', { format: 'png' });
      writeFileSync(`${SHOT_DIR}/${name}.png`, Buffer.from(data, 'base64'));
    }
  };
}

const chrome = spawn(CHROME, [
  '--headless=new', '--disable-gpu', '--no-first-run', '--no-default-browser-check',
  '--hide-scrollbars', '--force-device-scale-factor=1',
  `--remote-debugging-port=${PORT}`, `--user-data-dir=${process.env.TMPDIR || '/tmp'}/mydatadev-ui-smoke`,
  'about:blank'
], { stdio: 'ignore' });

let page;
try {
  if (SHOT_DIR) mkdirSync(SHOT_DIR, { recursive: true });
  page = await session(await connect());
  await page.send('Emulation.setDeviceMetricsOverride', { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false });

  console.log(`打开 ${APP_URL}`);
  await page.send('Page.navigate', { url: APP_URL });
  await page.sleep(5000);

  check('应用外壳渲染', await page.evaluate(`Boolean(document.querySelector('.app-shell'))`));
  check('头部渲染', await page.evaluate(`Boolean(document.querySelector('.app-header'))`));
  check('资源管理器渲染', await page.evaluate(`Boolean(document.querySelector('.app-sider, .explorer-panel'))`));
  await page.shot('01-shell');

  const opened = await page.evaluate(`
    (() => {
      const button = [...document.querySelectorAll('button')].find((b) => (b.textContent || '').trim() === '管理');
      if (!button) return false;
      button.click();
      return true;
    })()
  `);
  check('管理入口存在', opened);
  await page.sleep(2000);
  check('管理抽屉打开', await page.evaluate(`Boolean(document.querySelector('.management-shell'))`));
  check('同时只有一个抽屉', (await page.evaluate(`document.querySelectorAll('.ant-drawer-open').length`)) === 1);

  const sections = await page.evaluate(`
    JSON.stringify([...document.querySelectorAll('.management-nav-item')].map((n) => (n.textContent || '').trim()))
  `);
  const labels = JSON.parse(sections || '[]');
  check('六个管理分区', labels.length === 6, `实际 ${labels.length}：${labels.join(' / ')}`);

  for (const label of labels) {
    const clicked = await page.evaluate(`
      (() => {
        const item = [...document.querySelectorAll('.management-nav-item')].find((n) => (n.textContent || '').trim() === ${JSON.stringify(label)});
        if (!item || item.disabled) return 'skip';
        item.click();
        return 'ok';
      })()
    `);
    if (clicked === 'skip') {
      console.log(`  – ${label}（不可用，已跳过）`);
      continue;
    }
    await page.sleep(2200);
    const rendered = await page.evaluate(`
      (() => {
        const body = document.querySelector('.management-body');
        return Boolean(body) && body.textContent.trim().length > 0;
      })()
    `);
    check(`分区「${label}」有内容`, rendered);
    await page.shot(`02-${label}`);
  }
} catch (error) {
  failures.push(String(error.message || error));
  console.log(`  ✗ ${error.message || error}`);
} finally {
  page?.close();
  chrome.kill();
}

if (failures.length > 0) {
  console.log(`\n界面冒烟失败：${failures.length} 项`);
  process.exit(1);
}
console.log('\n界面冒烟通过');
