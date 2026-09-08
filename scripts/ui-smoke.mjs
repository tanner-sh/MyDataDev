/**
 * 界面冒烟：用系统自带的 Chrome 把应用真的打开一遍，逐屏检查关键区域渲染出来了。
 *
 * 为什么不是 Playwright：它要下载一份约 150MB 的浏览器，而这个仓库一贯不为一次检查引依赖。
 * Chrome DevTools Protocol 走 WebSocket，Node 自带，够用。
 *
 * 两种用法：
 *
 * 1）自己拉起 Web 发行包（推荐，跑的是真正发出去的那个产物）：
 *      node scripts/build-web-bundle.mjs      # 先产出 release-assets/*-web.jar
 *      node scripts/ui-smoke.mjs --serve [--shots ./ui-shots]
 *
 * 2）连到已经跑着的开发服务器：
 *      cd backend && mvn spring-boot:run
 *      cd frontend && npm run dev
 *      node scripts/ui-smoke.mjs [--url http://localhost:5173] [--shots ./ui-shots]
 *
 * --serve 默认关闭认证；加 --auth 会初始化独立测试管理员，走真实登录表单，
 * 并验证会话过期后重新登录保留编辑现场。CI 使用 --serve --auth。
 * 数据写进 --work 指定的目录（默认 .ui-smoke-run），每次跑完不清 —— 出问题时那份日志是唯一线索。
 *
 * --serve 模式下还会经 API 播一条 H2 连接和一张 240 行的表，然后走一遍真正每天都在走的路：
 * 选连接 → 打开表 → 翻页 → 改一格 → 提交 → 导出菜单。改完那一格是回库里读出来核对的，
 * 因为界面上显示改过了不代表真的写进去了。此前冒烟只看得到空壳，而空壳恰恰是最不容易坏的
 * 部分 —— 需要先选连接才可用的「备份与恢复」「活动会话」两个分区，过去每次都打印「已跳过」。
 *
 * 剩下的检查项刻意只覆盖「结构还在不在」：抽屉能不能开、管理分区能不能切、结果区能不能出。
 * 视觉细节靠人看截图 —— 脚本不该假装自己能判断好不好看。
 */
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, openSync, readdirSync, statSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { spawn } from 'node:child_process';

const CHROME = process.env.CHROME_PATH
  || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const args = process.argv.slice(2);
const option = (name, fallback) => {
  const index = args.indexOf(name);
  return index >= 0 && args[index + 1] ? args[index + 1] : fallback;
};
const SERVE = args.includes('--serve');
const AUTH = args.includes('--auth');
const TEST_PASSWORD = 'ui-smoke-password-at-least-12';
const apiCookies = new Map();
let apiCsrf;
const SERVE_PORT = Number(option('--serve-port', '8099'));
const WORK_DIR = option('--work', '.ui-smoke-run');
const APP_URL = SERVE ? `http://127.0.0.1:${SERVE_PORT}` : option('--url', 'http://localhost:5173');
const SHOT_DIR = option('--shots', '');
const PORT = Number(option('--debug-port', String(19000 + process.pid % 20000)));
const CHROME_PROFILE = mkdtempSync(path.join(process.env.TMPDIR || '/tmp', 'mydatadev-ui-'));
const SEED_CONNECTION_NAME = 'UI 冒烟库';
// 第二条连接指向同一个内存库，只是标成只读：只读连接在界面上走的是另一套分支
// （工具栏副标题、按钮禁用、布局），只测一条开发连接就看不见它们坏没坏。
const SEED_READONLY_CONNECTION_NAME = '只读冒烟库';
const SEED_TABLE = 'smoke_orders';

// Node 的播种会话与浏览器会话独立；只给本脚本创建的 API 会话附加测试 Cookie。
async function fetch(input, init = {}) {
  const apiRequest = AUTH && String(input).startsWith(APP_URL + '/api/');
  const response = await globalThis.fetch(input, { ...init, signal: init.signal || AbortSignal.timeout(30_000), headers: {
    ...init.headers,
    ...(apiRequest ? { Cookie: [...apiCookies].map(([key, value]) => key + '=' + value).join('; '), ...(apiCsrf ? { [apiCsrf.name]: apiCsrf.token } : {}) } : {})
  }});
  if (apiRequest) for (const cookie of response.headers.getSetCookie()) {
    const [pair] = cookie.split(';'); const index = pair.indexOf('=');
    apiCookies.set(pair.slice(0, index), pair.slice(index + 1));
  }
  return response;
}

async function authenticateSeed() {
  const status = await (await fetch(APP_URL + '/api/auth/status')).json();
  apiCsrf = { name: status.csrfHeaderName, token: status.csrfToken };
  const response = await fetch(APP_URL + '/api/auth/login', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ username: 'admin', password: TEST_PASSWORD }) });
  if (!response.ok) throw new Error('播种测试会话登录失败：' + response.status);
  const loggedIn = await response.json();
  apiCsrf = { name: loggedIn.csrfHeaderName, token: loggedIn.csrfToken };
}

async function loginInBrowser(page) {
  await page.evaluate(`(() => { const input = document.querySelector('.auth-page input[autocomplete="username"]'); input?.focus(); input?.select(); })()`);
  await page.send('Input.insertText', { text: 'admin' });
  await page.evaluate(`(() => { const input = document.querySelector('.auth-page input[autocomplete="current-password"]'); input?.focus(); input?.select(); })()`);
  await page.send('Input.insertText', { text: TEST_PASSWORD });
  await page.evaluate(`document.querySelector('.auth-page button[type="submit"]')?.click()`);
  await page.sleep(2500);
  if (!await page.evaluate(`Boolean(document.querySelector('.app-shell')) && !document.querySelector('.auth-page')`)) {
    await page.shot('login-failure');
    throw new Error('浏览器登录未成功，请查看登录失败截图');
  }
}

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
    clearTimeout(entry.timer);
    message.error ? entry.reject(new Error(JSON.stringify(message.error))) : entry.resolve(message.result);
  };
  const send = (method, params = {}) => new Promise((resolve, reject) => {
    id += 1;
    const requestId = id;
    const timer = setTimeout(() => { pending.delete(requestId); reject(new Error('浏览器操作超时：' + method)); }, 15_000);
    pending.set(requestId, { resolve, reject, timer });
    socket.send(JSON.stringify({ id: requestId, method, params }));
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

/** 找一个 Web 发行包 JAR：命令行给的优先，其次 release-assets，最后 Maven 的输出目录。 */
function locateJar() {
  const explicit = option('--serve', '');
  if (explicit && !explicit.startsWith('--')) return explicit;
  const candidates = [];
  try {
    for (const name of readdirSync('release-assets')) {
      if (name.endsWith('-web.jar')) candidates.push(path.join('release-assets', name));
    }
  } catch {
    // 没打过发行包，下面再看 target。
  }
  // 目录里可能留着好几个版本的产物，按修改时间挑最新的那个 —— 按目录顺序拿，
  // 冒烟跑的可能是上一个版本，而这种「跑错东西」的失败最难看出来。
  candidates.sort((left, right) => statSync(right).mtimeMs - statSync(left).mtimeMs);
  candidates.push(path.join('backend', 'target', 'mydatadev-web.jar'));
  const found = candidates.find((candidate) => existsSync(candidate));
  if (!found) {
    throw new Error('找不到 Web 发行包 JAR，先跑 node scripts/build-web-bundle.mjs，或用 --serve <jar> 指定');
  }
  return path.resolve(found);
}

/**
 * 拉起 Web 发行包并等它就绪。
 *
 * 主密钥不从环境变量传：后端首次启动会在工作目录下自己生成一份，重复跑也就能复用同一份数据。
 */
async function startServer() {
  const jar = locateJar();
  mkdirSync(WORK_DIR, { recursive: true });
  const log = openSync(path.join(WORK_DIR, 'server.log'), 'w');
  console.log(`启动 ${path.basename(jar)} → ${APP_URL}`);
  const server = spawn('java', [
    '-jar', jar,
    '--spring.profiles.active=web',
    `--server.port=${SERVE_PORT}`,
    `--app.auth.mode=${AUTH ? 'LOCAL' : 'DISABLED'}`
  ], { cwd: WORK_DIR, env: { ...process.env, ...(AUTH ? { DB_ADMIN_WEB_PASSWORD: TEST_PASSWORD } : {}) }, stdio: ['ignore', log, log] });

  let exited = false;
  server.on('exit', () => { exited = true; });
  for (let attempt = 0; attempt < 90; attempt += 1) {
    if (exited) break;
    try {
      const response = await fetch(`${APP_URL}/actuator/health`);
      if (response.ok) return server;
    } catch {
      // 还没起来
    }
    await new Promise((resolve) => setTimeout(resolve, 2000));
  }
  server.kill();
  throw new Error(`后端没能在 3 分钟内就绪，看 ${path.join(WORK_DIR, 'server.log')}`);
}

/**
 * 播一条真的 H2 连接和一张有数据的表。
 *
 * <p>这一段走 API 而不是界面：它是准备工作，不是被测对象。有了它，冒烟才能覆盖「选连接 →
 * 打开表 → 翻页 → 改一格 → 提交 → 导出」这条真正每天都在走的路 —— 此前冒烟只看得到空壳，
 * 而空壳恰恰是最不容易坏的部分。</p>
 */
const FILLER_TABLE_COUNT = 16;

async function seedSmokeData() {
  console.log('播种一条 H2 连接、240 行数据与若干张填充表…');
  const post = async (path, body) => {
    const response = await fetch(`${APP_URL}/api${path}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-User': 'ui-smoke' },
      body: JSON.stringify(body)
    });
    if (!response.ok) throw new Error(`${path} 返回 ${response.status}：${(await response.text()).slice(0, 300)}`);
    return response.json();
  };
  const existing = await (await fetch(`${APP_URL}/api/connections`, { headers: { 'X-User': 'ui-smoke' } })).json();
  const found = existing.find((connection) => connection.name === SEED_CONNECTION_NAME);
  const connection = found || await post('/connections', {
    name: SEED_CONNECTION_NAME,
    dbType: 'h2',
    // 内存库跟着后端进程活着；冒烟结束进程退出，什么都不留下。
    jdbcUrl: 'jdbc:h2:mem:ui-smoke;DB_CLOSE_DELAY=-1',
    username: 'sa',
    password: '',
    environment: 'dev',
    readonly: false
  });
  const run = (sql) => post('/sql/execute', { connectionId: connection.id, sql });
  await run('DROP TABLE IF EXISTS smoke_other');
  await run("CREATE TABLE smoke_other(id INT PRIMARY KEY, note VARCHAR(80))");
  await run("INSERT INTO smoke_other VALUES (1, '第二个标签')");
  await run(`DROP TABLE IF EXISTS ${SEED_TABLE}`);
  await run(`CREATE TABLE ${SEED_TABLE}(id INT PRIMARY KEY, customer VARCHAR(60) NOT NULL, amount INT NOT NULL)`);
  // 240 行：默认每页 100 行，翻页才是真的翻页而不是一页装得下。
  await run(`INSERT INTO ${SEED_TABLE} SELECT X, '客户' || X, X * 3 FROM SYSTEM_RANGE(1, 240)`);
  if (!existing.some((item) => item.name === SEED_READONLY_CONNECTION_NAME)) {
    await post('/connections', {
      name: SEED_READONLY_CONNECTION_NAME,
      dbType: 'h2',
      jdbcUrl: 'jdbc:h2:mem:ui-smoke;DB_CLOSE_DELAY=-1',
      username: 'sa',
      password: '',
      environment: 'dev',
      readonly: true
    });
  }
  // 再播十六张空表：资源树是虚拟列表，只有两张表时渲染窗口多大都装得下，
  // 「切一下页签列表就变短」这类窗口计算的故障根本显不出来。
  for (let index = 1; index <= FILLER_TABLE_COUNT; index++) {
    const name = `smoke_fill_${String(index).padStart(2, '0')}`;
    await run(`DROP TABLE IF EXISTS ${name}`);
    await run(`CREATE TABLE ${name}(id INT PRIMARY KEY)`);
  }
  return connection.id;
}

async function readSeedCustomer(connectionId, id) {
  const response = await fetch(`${APP_URL}/api/sql/execute`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-User': 'ui-smoke' },
    body: JSON.stringify({ connectionId, sql: `SELECT customer FROM ${SEED_TABLE} WHERE id = ${id}` })
  });
  const payload = await response.json();
  return String(payload?.rows?.[0]?.[0] ?? '');
}

const server = SERVE ? await startServer() : null;
process.once('exit', () => server?.kill());
// 播种放在打开浏览器之前：有了连接，「备份与恢复」「活动会话」这些要先选连接才可用的分区
// 才会被真的检查到，而不是每次都打印一行「已跳过」。
if (AUTH && SERVE) await authenticateSeed();
const seedConnectionId = SERVE ? await seedSmokeData() : null;

const chrome = spawn(CHROME, [
  '--headless=new', '--disable-gpu', '--no-first-run', '--no-default-browser-check',
  '--hide-scrollbars', '--force-device-scale-factor=1',
  `--remote-debugging-port=${PORT}`, `--user-data-dir=${CHROME_PROFILE}`,
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

  if (AUTH) {
    check('先登录才能进入工作台', await page.evaluate(`Boolean(document.querySelector('.auth-page input[type=\"password\"]')) && !document.querySelector('.app-shell')`));
    await loginInBrowser(page);
  }

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
  // 断言「关键分区还在」而不是一个固定条数：分区会随功能增减，写死数字会让每加一个功能
  // 都要来改这里，改着改着这条检查就没人当回事了。少一个关键分区才是真的故障。
  const requiredSections = ['连接管理', '结构对比', '数据对比'];
  const missingSections = requiredSections.filter((name) => !labels.includes(name));
  check('关键管理分区都在', missingSections.length === 0,
    `缺少 ${missingSections.join('、')}；实际 ${labels.length} 个：${labels.join(' / ')}`);

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

  // 「有内容」挡不住这一类：合并抽屉时把连接表单的 <Modal> 连带删掉过一次，六个分区照样
  // 全部通过，而点「新建连接」什么都不弹，overlayOpen 还永久为真把全局快捷键锁死。
  // 凡是从面板里再弹一层的入口，都得真的点开、真的关掉。
  const formOpened = await page.evaluate(`
    (() => {
      const item = [...document.querySelectorAll('.management-nav-item')].find((n) => (n.textContent || '').trim() === '连接管理');
      if (!item || item.disabled) return 'skip';
      item.click();
      return 'ok';
    })()
  `);
  if (formOpened === 'skip') {
    console.log('  – 连接表单（分区不可用，已跳过）');
  } else {
    await page.sleep(1200);
    const clicked = await page.evaluate(`
      (() => {
        const button = [...document.querySelectorAll('button')].find((b) => (b.textContent || '').trim() === '新建连接');
        if (!button) return false;
        button.click();
        return true;
      })()
    `);
    check('「新建连接」入口存在', clicked);
    await page.sleep(1800);
    // 只断言弹层在是不够的：表单是懒加载的，加载失败时弹层照样带着标题出现，里面空着。
    // 认字段文案而不是 <form> 标签 —— antd 版本之间会换类名（v5 的 .ant-modal-content 在
    // v6 叫 .ant-modal-container），认渲染出来的字比认结构稳。
    check('连接表单弹出', await page.evaluate(`
      (() => {
        const modal = document.querySelector('.ant-modal-container');
        return Boolean(modal) && modal.textContent.includes('连接名称') && (modal.textContent.includes('数据库地址') || modal.textContent.includes('主机'));
      })()
    `));
    await page.shot('03-连接表单');

    await page.evaluate(`
      (() => {
        const close = document.querySelector('.ant-modal-container .ant-modal-close');
        if (close) close.click();
      })()
    `);
    await page.sleep(1500);
    // 关不掉同样是故障：overlayOpen 会一直为真，把全局快捷键锁死。
    check('连接表单可以关闭', (await page.evaluate(`document.querySelectorAll('.ant-modal-container').length`)) === 0);
  }
  // 页头的连接下拉：antd 的 Select 听的是 mousedown，直接 click() 打不开。
  const pickConnection = async (name) => {
    const opened = await page.evaluate(`
      (() => {
        const selector = document.querySelector('.connection-select .ant-select-selector')
          || document.querySelector('.connection-switcher .ant-select-selector')
          || document.querySelector('.connection-switcher input');
        if (!selector) {
          return { ok: false, html: (document.querySelector('.connection-switcher') || document.body).innerHTML.slice(0, 300) };
        }
        for (const type of ['mousedown', 'mouseup', 'click']) {
          selector.dispatchEvent(new MouseEvent(type, { bubbles: true, cancelable: true, view: window }));
        }
        return { ok: true };
      })()
    `);
    await page.sleep(1200);
    const picked = await page.evaluate(`
      (() => {
        const option = [...document.querySelectorAll('.ant-select-item-option')]
          .find((node) => (node.textContent || '').includes(${JSON.stringify(name)}));
        if (!option) return false;
        for (const type of ['mousedown', 'mouseup', 'click']) {
          option.dispatchEvent(new MouseEvent(type, { bubbles: true, cancelable: true, view: window }));
        }
        return true;
      })()
    `);
    await page.sleep(6000);
    return { opened, picked };
  };

  if (SERVE) {
    const connectionId = seedConnectionId;
    // 先关掉管理抽屉：它开着的时候会把焦点困在抽屉里，后面的单元格编辑要靠焦点。
    await page.evaluate(`
      (() => {
        const close = document.querySelector('.ant-drawer-open .ant-drawer-close');
        if (close) close.click();
      })()
    `);
    await page.sleep(1500);

    const { opened: openedSwitcher, picked } = await pickConnection(SEED_CONNECTION_NAME);
    check('页头能打开连接下拉', openedSwitcher.ok === true, openedSwitcher.html || '');
    check('可以在页头选中连接', picked);

    const openedTable = await page.evaluate(`
      (() => {
        const button = [...document.querySelectorAll('button[aria-label]')]
          .find((node) => /^打开 .*的表数据$/.test(node.getAttribute('aria-label') || '')
            && (node.getAttribute('aria-label') || '').toLowerCase().includes(${JSON.stringify(SEED_TABLE)}));
        if (!button) return false;
        button.click();
        return true;
      })()
    `);
    check('资源树里能找到播种的表', openedTable);
    await page.sleep(6000);

    // 资源树是虚拟列表，渲染多少行取决于量到的视口高度。切到「收藏」时列表整个卸掉，
    // ResizeObserver 会补一次 0×0 的回调 —— 照单全收就会把视口高度记成一行，切回「全部」
    // 后列表只剩十来行、后面全是空白，滚动条却仍按全部行数撑开。断言往返前后行数一致，
    // 而不是断言某个具体数字：渲染窗口的大小本来就跟视口高度和 overscan 走。
    const countTreeRows = () => page.evaluate(
      `document.querySelectorAll('.object-tree-virtual .object-tree-virtual-row').length`);
    const rowsBeforeScopeSwitch = await countTreeRows();
    const switchedScope = await page.evaluate(`
      (() => {
        const labels = [...document.querySelectorAll('.object-view-filter .ant-segmented-item-label')];
        const favorites = labels.find((node) => (node.textContent || '').includes('收藏'));
        if (!favorites) return false;
        favorites.click();
        return true;
      })()
    `);
    check('可以切到「收藏」页签', switchedScope);
    await page.sleep(1200);
    await page.evaluate(`
      (() => {
        const labels = [...document.querySelectorAll('.object-view-filter .ant-segmented-item-label')];
        labels.find((node) => (node.textContent || '').includes('全部'))?.click();
      })()
    `);
    await page.sleep(1200);
    const rowsAfterScopeSwitch = await countTreeRows();
    check('切回「全部」后资源树渲染的行数没有变少',
      rowsBeforeScopeSwitch > 0 && rowsAfterScopeSwitch === rowsBeforeScopeSwitch,
      `切换前 ${rowsBeforeScopeSwitch} 行，切换后 ${rowsAfterScopeSwitch} 行`);

    const firstPage = await page.evaluate(`
      (() => {
        const rows = document.querySelectorAll('.table-grid-pane .ant-table-row');
        const first = rows[0];
        return { rows: rows.length, firstCell: first ? (first.textContent || '').slice(0, 40) : '' };
      })()
    `);
    check('表数据加载出来了', firstPage.rows > 0, `实际 ${firstPage.rows} 行`);
    check('表数据页脚完整可见', await page.evaluate(`(() => { const r = document.querySelector('.table-pagination-actions')?.getBoundingClientRect(); return r?.height > 0 && r.bottom <= innerHeight; })()`));
    await page.shot('05-表数据');

    const turned = await page.evaluate(`
      (() => {
        const button = [...document.querySelectorAll('.table-pagination-actions button')]
          .find((node) => (node.textContent || '').includes('下一页'));
        if (!button || button.disabled) return false;
        button.click();
        return true;
      })()
    `);
    await page.sleep(4000);
    const secondPage = await page.evaluate(`
      (() => {
        const first = document.querySelector('.table-grid-pane .ant-table-row');
        return first ? (first.textContent || '').slice(0, 40) : '';
      })()
    `);
    // 翻页翻的是服务端的下一批，不是同一批数据换个显示 —— 所以首行必须变。
    check('可以翻到下一页', turned && Boolean(secondPage) && secondPage !== firstPage.firstCell,
      `第一页「${firstPage.firstCell}」第二页「${secondPage}」`);

    await page.evaluate(`
      (() => {
        const button = [...document.querySelectorAll('.table-pagination-actions button')]
          .find((node) => (node.textContent || '').includes('第一页'));
        if (button && !button.disabled) button.click();
      })()
    `);
    await page.sleep(4000);

    // 改一格：进编辑态 → 写值 → 回车 → 提交。React 受控输入要用原生 setter 赋值，
    // 直接改 value 不会触发 onChange。
    const edited = await page.evaluate(`
      (() => {
        const cell = [...document.querySelectorAll('.table-grid-pane [aria-label]')]
          .find((node) => /^CUSTOMER|^customer/i.test(node.getAttribute('aria-label') || '')
            && (node.getAttribute('aria-label') || '').includes('第 1 行'));
        if (!cell) return 'no-cell';
        cell.focus();
        cell.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
        return 'activated';
      })()
    `);
    await page.sleep(1500);
    // 用 CDP 真正「打字」：React 的受控输入不认 JS 直接赋 value（它的 valueTracker 会把这次
    // 变化当成没发生），而 Input.insertText 走的是浏览器自己的输入路径。
    const focused = await page.evaluate(`
      (() => {
        const input = [...document.querySelectorAll('.editable-cell-control input')][0];
        if (!input) return 'no-input';
        input.focus();
        input.select();
        return 'focused';
      })()
    `);
    if (focused === 'focused') await page.send('Input.insertText', { text: '冒烟改过的客户' });
    await page.sleep(400);
    const committed = await page.evaluate(`
      (() => {
        const input = [...document.querySelectorAll('.editable-cell-control input')][0];
        if (!input) return 'no-input';
        const typed = input.value;
        // 单元格是在 blur 时落草稿的（回车也只是 blur 一下）。
        input.blur();
        return 'typed:' + typed;
      })()
    `);
    await page.sleep(1200);
    const clickText = async (scope, label) => page.evaluate(`(() => {
      const button = [...document.querySelectorAll(${JSON.stringify(scope)})].find(node => (node.textContent || '').replace(/\\s/g, '') === ${JSON.stringify(label.replace(/\s/g, ''))});
      if (!button || button.disabled) return false;
      button.click(); return true;
    })()`);
    const undo = await clickText('.table-workspace button, .table-toolbar button, .table-secondary-actions button', '撤销一步');
    await page.sleep(500);
    check('撤销恢复单元格原值', undo && await page.evaluate(`document.querySelector('.table-grid-pane').textContent.includes('客户1') && !document.querySelector('.table-grid-pane').textContent.includes('冒烟改过的客户')`));
    const redo = await clickText('.table-workspace button, .table-toolbar button, .table-secondary-actions button', '重做');
    await page.sleep(500);
    check('重做恢复待提交修改', redo && await page.evaluate(`document.querySelector('.table-grid-pane').textContent.includes('冒烟改过的客户')`));
    await page.evaluate(`(() => {
      const button = [...document.querySelectorAll('button[aria-label]')].find(node => /^打开 .*的表数据$/.test(node.getAttribute('aria-label') || '') && /smoke_other/i.test(node.getAttribute('aria-label')));
      button?.click();
    })()`);
    await page.sleep(1800);
    check('第二张表有独立标签', await page.evaluate(`document.querySelectorAll('.resource-document-tab').length === 2 && document.querySelector('.table-grid-pane').textContent.includes('第二个标签')`));
    await page.evaluate(`(() => {
      const button = [...document.querySelectorAll('.resource-document-tab button')].find(node => /smoke_orders/i.test(node.textContent) && node.textContent.includes('数据'));
      button?.click();
    })()`);
    await page.sleep(1200);
    check('切回表标签保留未提交修改', await page.evaluate(`document.querySelector('.table-grid-pane').textContent.includes('冒烟改过的客户') && document.querySelector('.resource-document-tabs').textContent.includes('●')`));
    await page.evaluate(`(() => { [...document.querySelectorAll('.resource-document-tabs button')].find(node => node.textContent.trim() === 'SQL 工作台')?.click(); })()`);
    await page.sleep(500);
    await page.evaluate(`document.querySelector('button[aria-label="关闭 SMOKE_OTHER 数据标签"]')?.click()`);
    await page.sleep(500);
    check('关闭后台表标签保持当前工作区', await page.evaluate(`Boolean(document.querySelector('.sql-workspace')) && !document.querySelector('.table-grid-pane')`));
    await page.evaluate(`(() => { [...document.querySelectorAll('.resource-document-tab button')].find(node => /smoke_orders/i.test(node.textContent) && node.textContent.includes('数据'))?.click(); })()`);
    await page.sleep(600);

    await page.shot('06a-多标签与待提交修改');
    if (AUTH) {
      await page.send('Network.clearBrowserCookies');
      await page.evaluate(`document.querySelector('button[aria-label="刷新连接"]')?.click()`);
      await page.sleep(1500);
      check('会话过期显示登录页并遮住工作区', await page.evaluate(`document.body.dataset.sessionLocked === 'true' && Boolean(document.querySelector('.auth-page')) && Boolean(document.querySelector('[hidden] .app-shell'))`));
      await page.shot('06b-会话过期');
      await loginInBrowser(page);
      check('重新登录保留表格待提交修改', await page.evaluate(`document.body.dataset.sessionLocked === 'false' && document.querySelector('.table-grid-pane')?.textContent.includes('冒烟改过的客户')`));
    }

    // 用第二条数据库会话制造乐观并发冲突，检查回滚提示和人工合并入口。
    await fetch(APP_URL + '/api/sql/execute', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ connectionId, sql: "UPDATE smoke_orders SET customer='其他会话改过的客户' WHERE id=1" }) });
    const submitted = await page.evaluate(`
      (() => {
        const buttons = [...document.querySelectorAll('.table-primary-actions button')]
          .map((node) => ({ text: (node.textContent || '').trim(), disabled: node.disabled }));
        const button = [...document.querySelectorAll('.table-primary-actions button')]
          .find((node) => (node.textContent || '').startsWith('提交'));
        const status = (document.querySelector('.workspace-status-bar') || {}).textContent || '';
        if (!button || button.disabled) return { ok: false, buttons, status: status.slice(0, 120) };
        button.click();
        return { ok: true, buttons, status: status.slice(0, 120) };
      })()
    `);
    await page.sleep(1800);
    check('并发冲突展示原值、当前值与我的修改', await page.evaluate(`document.querySelector('.conflict-values')?.textContent.includes('其他会话改过的客户') && document.querySelector('.conflict-values')?.textContent.includes('冒烟改过的客户')`));
    await page.shot('06c-并发冲突处理');
    await page.evaluate(`(() => { [...document.querySelectorAll('.ant-modal-confirm button')].find(node => node.textContent.includes('保留我的修改'))?.click(); })()`);
    await page.sleep(500);
    await page.evaluate(`(() => { [...document.querySelectorAll('.table-primary-actions button')].find(node => node.textContent.trim().startsWith('提交'))?.click(); })()`);
    await page.sleep(2500);
    const stored = submitted.ok ? await readSeedCustomer(connectionId, 1) : '';
    // 读库而不是读界面：界面上显示改过了，不代表这一次真的写进去了。
    check('单元格编辑能提交到库里', stored === '冒烟改过的客户',
      `编辑=${edited} 输入=${committed} 提交=${JSON.stringify(submitted)} 库里=「${stored}」`);
    await page.shot('06-表数据编辑');

    const exported = await page.evaluate(`
      (() => {
        const button = [...document.querySelectorAll('.table-secondary-actions button, .table-toolbar-actions button')]
          .find((node) => (node.textContent || '').trim() === '导出');
        if (!button || button.disabled) return false;
        for (const type of ['mouseover', 'mouseenter', 'mousedown', 'mouseup', 'click']) {
          button.dispatchEvent(new MouseEvent(type, { bubbles: true, cancelable: true, view: window }));
        }
        return true;
      })()
    `);
    await page.sleep(1500);
    const exportMenu = await page.evaluate(`
      (() => {
        const items = [...document.querySelectorAll('.ant-dropdown-menu-item')].map((node) => (node.textContent || '').trim());
        return items.filter((text) => text.startsWith('导出'));
      })()
    `);
    check('导出菜单列出格式', exported && exportMenu.includes('导出 CSV') && exportMenu.includes('导出 Excel'),
      exportMenu.join('/'));
  }

  // 命令面板：快捷键是它唯一的入口，坏了不会有任何界面痕迹 —— 这正是 Ctrl/Cmd+P 的对象
  // 搜索曾经悄悄失灵过一整轮的原因（渲染块被删掉，快捷键还在）。
  await page.evaluate(`
    (() => {
      const escape = new KeyboardEvent('keydown', { key: 'Escape', bubbles: true });
      document.dispatchEvent(escape);
    })()
  `);
  await page.sleep(800);
  await page.evaluate(`
    (() => {
      window.dispatchEvent(new KeyboardEvent('keydown', { key: 'k', code: 'KeyK', ctrlKey: true, bubbles: true }));
    })()
  `);
  await page.sleep(1500);
  const palette = await page.evaluate(`
    (() => {
      const modal = document.querySelector('.command-palette-modal');
      if (!modal) return { open: false };
      return {
        open: true,
        commands: modal.querySelectorAll('.command-palette-item').length,
        hasManagement: modal.textContent.includes('打开连接管理')
      };
    })()
  `);
  check('命令面板可以用快捷键打开', palette.open === true);
  check('命令面板列出了命令', (palette.commands || 0) > 0 && palette.hasManagement === true);
  if (palette.open) await page.shot('04-命令面板');
  if (SERVE) {
    await page.send('Input.dispatchKeyEvent', { type: 'keyDown', key: 'Escape', code: 'Escape' });
    await page.send('Input.dispatchKeyEvent', { type: 'keyUp', key: 'Escape', code: 'Escape' });
    await page.sleep(800);
    await page.evaluate(`(() => { [...document.querySelectorAll('.resource-document-tabs button')].find(node => node.textContent.trim() === 'SQL 工作台')?.click(); })()`);
    await page.sleep(1800);
    const draft = 'select 1 as draft_survives_reload;';
    await page.evaluate(`document.querySelector('.cm-content')?.focus()`);
    const modifier = process.platform === 'darwin' ? 4 : 2;
    await page.send('Input.dispatchKeyEvent', { type: 'keyDown', key: 'a', code: 'KeyA', modifiers: modifier });
    await page.send('Input.dispatchKeyEvent', { type: 'keyUp', key: 'a', code: 'KeyA', modifiers: modifier });
    await page.send('Input.insertText', { text: draft });
    await page.sleep(1000);
    check('SQL 草稿实际写入编辑器', await page.evaluate(`document.querySelector('.cm-content')?.textContent.includes('draft_survives_reload')`));
    await page.send('Page.reload');
    await page.sleep(5000);
    check('刷新后恢复 SQL 草稿', await page.evaluate(`document.querySelector('.cm-content')?.textContent.includes('draft_survives_reload')`));
    check('显示草稿保存状态', await page.evaluate(`document.body.textContent.includes('草稿已保存')`));
    await page.shot('07-SQL草稿恢复');
    await page.evaluate(`document.querySelector('.sql-tabs .ant-tabs-nav-add')?.click()`);
    await page.sleep(500);
    await page.evaluate(`document.querySelector('.sql-tabs .ant-tabs-tab .ant-tabs-tab-remove')?.click()`);
    await page.sleep(500);
    await page.evaluate(`(() => { [...document.querySelectorAll('.ant-modal-confirm button')].find(node => node.textContent.replace(/\\s/g, '') === '关闭标签')?.click(); })()`);
    await page.sleep(500);
    await page.evaluate(`(() => { [...document.querySelectorAll('.sql-tabs button')].find(node => node.textContent.includes('找回关闭的 SQL'))?.click(); })()`);
    await page.sleep(800);
    check('可以找回关闭的 SQL 草稿', await page.evaluate(`document.querySelector('.cm-content')?.textContent.includes('draft_survives_reload')`));

    // 真的执行一条查询。此前整轮冒烟都没让结果区渲染过一行数据 —— 而 SQL 工作台的结果表
    // 是这个应用最常被看着的一块界面，它坏了会一路坏到导出、图表和结果内编辑。
    await page.evaluate(`document.querySelector('.cm-content')?.focus()`);
    await page.send('Input.dispatchKeyEvent', { type: 'keyDown', key: 'a', code: 'KeyA', modifiers: modifier });
    await page.send('Input.dispatchKeyEvent', { type: 'keyUp', key: 'a', code: 'KeyA', modifiers: modifier });
    await page.send('Input.insertText', { text: 'select ID, CUSTOMER, AMOUNT from SMOKE_ORDERS order by ID' });
    await page.sleep(600);
    await page.evaluate(`document.querySelector('.sql-execute-button')?.click()`);
    await page.sleep(2500);
    const resultGrid = await page.evaluate(`
      (() => {
        const grid = document.querySelector('.result-grid .data-grid, .data-grid');
        if (!grid) return { rendered: false };
        const headers = [...grid.querySelectorAll('.ant-table-thead th')];
        const column = headers.find(node => node.textContent.trim() === 'ID');
        // 列名被自己的排序/筛选按钮挤成「CUSTOM…」是真出现过的：省略发生在标题元素内部，
        // 只看列宽看不出来，得比 scrollWidth 和 clientWidth。
        const title = headers
          .map(node => node.querySelector('.ant-table-column-title'))
          .find(node => node?.textContent.trim() === 'CUSTOMER');
        return {
          rendered: true,
          // 虚拟表格的行不是 <tr>，是带 ant-table-row 的 div。
          rows: grid.querySelectorAll('.ant-table-row').length,
          hasCustomer: grid.textContent.includes('客户1'),
          // 列宽之和填不满视口时，多余的宽度必须落在尾部空白列上，而不是被均摊进每一列。
          filler: Boolean(grid.querySelector('.grid-filler-column')),
          idWidth: column ? Math.round(column.getBoundingClientRect().width) : 0,
          titleClipped: title ? title.scrollWidth > title.clientWidth + 1 : null
        };
      })()
    `);
    check('查询结果渲染出数据行', resultGrid.rendered === true && resultGrid.rows > 0 && resultGrid.hasCustomer === true,
      JSON.stringify(resultGrid));
    check('结果列宽按内容，多余宽度落在尾部空白列', resultGrid.filler === true && resultGrid.idWidth > 0 && resultGrid.idWidth < 200,
      JSON.stringify(resultGrid));
    check('列名没有被表头按钮挤成省略号', resultGrid.titleClipped === false, JSON.stringify(resultGrid));
    await page.shot('09-查询结果');

    /*
      拖列宽不该惊动服务端。拖动手柄长在表头里，而结果表的表头整格是「点一下排序」——
      松手时浏览器补的那次 click 落在表头上就等于点了排序：offset 归零、重新查一次。
      这条只有盯着网络面板才看得见，所以在页面里数请求，而不是靠肉眼。
    */
    await page.evaluate(`
      (() => {
        window.__queryPageCalls = 0;
        const original = window.fetch;
        window.fetch = (...args) => {
          const url = typeof args[0] === 'string' ? args[0] : args[0]?.url || '';
          if (url.includes('/sql/query-page')) window.__queryPageCalls += 1;
          return original.apply(window, args);
        };
      })()
    `);
    const handle = await page.evaluate(`
      (() => {
        const node = document.querySelector('.result-grid .column-resize-handle');
        if (!node) return null;
        const rect = node.getBoundingClientRect();
        const header = node.closest('th');
        const cell = header.getBoundingClientRect();
        return {
          x: rect.left + rect.width / 2, y: rect.top + rect.height / 2, width: cell.width,
          // 手柄必须压在列的右边界上。它本来是标题里的 flex 项，会被排在列名之后、
          // 排序与筛选图标之前 —— 离真正的边界差着三四十像素，看着像列中间多了一条竖线。
          edgeGap: Math.round(cell.right - rect.right)
        };
      })()
    `);
    if (handle) {
      const drag = (type, x) => page.send('Input.dispatchMouseEvent', {
        type, x, y: handle.y, button: 'left', buttons: type === 'mouseReleased' ? 0 : 1, clickCount: 1, pointerType: 'mouse'
      });
      await drag('mousePressed', handle.x);
      await drag('mouseMoved', handle.x + 70);
      await page.sleep(600);
      /*
        拖动过程中列宽不该变：实时改宽度意味着每次移动都要重建整份列定义并让虚拟表重画
        （40 列时每次约 14.5ms 脚本，一帧只有 16.7ms）。现在拖动期间只移动一条参考线，
        松手才提交一次。这条断言是「没退回实时改宽」的可观测形式。
      */
      const during = await page.evaluate(`
        (() => {
          const guide = document.querySelector('.data-grid-viewport .column-resize-guide');
          const node = document.querySelector('.result-grid .column-resize-handle');
          return {
            guideVisible: Boolean(guide) && guide.hidden === false && guide.getBoundingClientRect().height > 0,
            width: node?.closest('th')?.getBoundingClientRect().width || 0
          };
        })()
      `);
      check('拖动中显示参考线，列宽先不动', during.guideVisible === true
        && Math.abs(during.width - handle.width) < 2, JSON.stringify(during));
      await drag('mouseReleased', handle.x + 70);
      await page.sleep(2500);
      const afterDrag = await page.evaluate(`
        (() => {
          const node = document.querySelector('.result-grid .column-resize-handle');
          return {
            calls: window.__queryPageCalls,
            width: node?.closest('th')?.getBoundingClientRect().width || 0,
            sorted: Boolean(document.querySelector('.result-grid th.ant-table-column-sort'))
          };
        })()
      `);
      check('列宽手柄压在列的右边界上', handle.edgeGap <= 1, `距右缘 ${handle.edgeGap}px`);
      check('拖列宽真的改了宽度', afterDrag.width > handle.width + 20,
        `${Math.round(handle.width)} → ${Math.round(afterDrag.width)}`);
      check('拖列宽不发请求、不触发排序', afterDrag.calls === 0 && afterDrag.sorted === false, JSON.stringify(afterDrag));
    } else {
      check('结果表有列宽拖动手柄', false);
    }

    // 表头同一次悬停只弹一个气泡：antd 的「点击升序」被关掉了，留下我们自己那条「列名 · 类型」。
    const headerBox = await page.evaluate(`
      (() => {
        const th = [...document.querySelectorAll('.result-grid .ant-table-thead th')]
          .find(node => node.textContent.trim().startsWith('CUSTOMER'));
        if (!th) return null;
        const rect = th.getBoundingClientRect();
        return { x: rect.left + 20, y: rect.top + rect.height / 2 };
      })()
    `);
    if (headerBox) {
      await page.send('Input.dispatchMouseEvent', { type: 'mouseMoved', x: headerBox.x, y: headerBox.y, pointerType: 'mouse' });
      await page.sleep(2000);
      const tooltips = await page.evaluate(
        `[...document.querySelectorAll('.ant-tooltip:not(.ant-tooltip-hidden)')].map(n => n.textContent.trim())`);
      check('悬停表头不再弹出「点击升序」气泡', !tooltips.some(text => text.includes('点击升序')),
        JSON.stringify(tooltips));
    }

    // 使用发行包公开接口验证队列、持久化历史与下载，UI 管理入口已经在前面打开检查。
    const post = async (route, body) => {
      const response = await fetch(APP_URL + '/api' + route, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
      if (!response.ok) throw new Error(route + ' ' + response.status + ' ' + await response.text());
      const text = await response.text(); return text ? JSON.parse(text) : undefined;
    };
    const created = await post('/scheduled-queries', { connectionId: seedConnectionId, name: '冒烟后台导出', sql: 'select * from smoke_orders order by id', exportFormat: 'csv', cron: '0 0 8 * * *', scheduleZone: 'UTC', enabled: false });
    const taskId = created.task.id;
    await post('/scheduled-queries/' + taskId + '/run', {});
    let run;
    for (let attempt = 0; attempt < 50; attempt++) {
      const history = await (await fetch(APP_URL + '/api/scheduled-queries/' + taskId + '/runs')).json();
      run = history[0];
      if (run?.finishedAt) break;
      await page.sleep(200);
    }
    check('后台导出记录完整结果', run?.status === 'SUCCESS' && run?.downloadable === true, JSON.stringify(run));
    if (run?.downloadable) {
      const response = await fetch(APP_URL + '/api/scheduled-queries/' + taskId + '/runs/' + run.id + '/download');
      check('导出历史支持下载实际文件', response.ok && (await response.text()).includes('冒烟改过的客户'));
    }
    await page.evaluate(`(() => { [...document.querySelectorAll('.object-tree-object-trigger')].find(node => /smoke_orders/i.test(node.textContent))?.click(); })()`);
    await page.sleep(1800);
    await page.evaluate(`(() => { [...document.querySelectorAll('.resource-document-panel:not([hidden]) .ant-tabs-tab')].find(node => node.textContent.trim() === '设计')?.click(); })()`);
    await page.sleep(800);
    await page.evaluate(`(() => { const input = document.querySelector('.resource-document-panel:not([hidden]) input[aria-label="字段名"]'); input?.focus(); input?.select(); })()`);
    await page.send('Input.insertText', { text: 'DRAFT_IDENTIFIER' });
    await page.sleep(800);
    check('表设计修改标记为未保存', await page.evaluate(`document.querySelector('.resource-document-tabs').textContent.includes('●') && document.querySelector('.resource-document-panel:not([hidden]) input[aria-label="字段名"]')?.value === 'DRAFT_IDENTIFIER'`));
    await page.evaluate(`(() => { [...document.querySelectorAll('.resource-document-tabs button')].find(node => node.textContent.trim() === 'SQL 工作台')?.click(); })()`);
    await page.sleep(600);
    await page.evaluate(`(() => { [...document.querySelectorAll('.resource-document-tab button')].find(node => /smoke_orders/i.test(node.textContent) && node.textContent.includes('结构'))?.click(); })()`);
    await page.sleep(800);
    check('切回对象标签保留设计稿与当前分区', await page.evaluate(`document.querySelector('.resource-document-panel:not([hidden]) input[aria-label="字段名"]')?.value === 'DRAFT_IDENTIFIER' && document.querySelector('.resource-document-tabs').textContent.includes('●')`));
    check('设计表格在可见区域内且有可用高度', await page.evaluate(`(() => { const panel = document.querySelector('.resource-document-panel:not([hidden]) .table-designer'); const input = panel?.querySelector('input[aria-label="字段名"]'); const r = panel?.getBoundingClientRect(); const i = input?.getBoundingClientRect(); return r?.height > 200 && i?.height > 0 && i.top >= r.top && i.bottom <= r.bottom; })()`));
    await page.shot('08-对象设计草稿保留');

    // 切到只读连接再看一眼 SQL 工作台。只读连接曾经把编辑器压成一行：.sql-workspace 是网格
    // 布局，那时给只读连接多排了一行（对应工具栏下面那条只读提示 Alert），提示挪进副标题后
    // 行数比子元素多了一行，于是分栏拿到 36px 那一行、状态栏拿到 minmax(0,1fr)。开发连接上
    // 一切正常，只有只读连接坏 —— 只测一条连接的话，这种问题只能等用户来报。
    const readonlyPick = await pickConnection(SEED_READONLY_CONNECTION_NAME);
    check('可以切到只读连接', readonlyPick.picked === true);
    await page.evaluate(`(() => { [...document.querySelectorAll('.resource-document-tabs button')].find(node => node.textContent.trim() === 'SQL 工作台')?.click(); })()`);
    await page.sleep(1800);
    // 量的是「露出来多少」而不是元素自己的高度：.editor 有 min-height: 120px，分栏塌掉时
    // 它的 getBoundingClientRect 照样是 120，只是被 overflow: hidden 的 .sql-split 裁成一行。
    const readonlyLayout = await page.evaluate(`
      (() => {
        const workspace = document.querySelector('.sql-workspace');
        if (!workspace) return { workspace: false };
        const box = (selector) => workspace.querySelector(selector)?.getBoundingClientRect();
        const split = box('.sql-split');
        const visible = (selector) => {
          const rect = box(selector);
          if (!rect || !split) return 0;
          return Math.round(Math.max(0, Math.min(rect.bottom, split.bottom) - Math.max(rect.top, split.top)));
        };
        return {
          workspace: true,
          readonlyHint: workspace.textContent.includes('只读连接'),
          split: Math.round(split?.height || 0),
          editor: visible('.editor'),
          results: visible('.sql-results-pane'),
          status: Math.round(box('.workspace-status')?.height || 0)
        };
      })()
    `);
    check('只读连接下 SQL 编辑器真的露出来了', readonlyLayout.editor >= 120, JSON.stringify(readonlyLayout));
    check('只读连接下结果区真的露出来了', readonlyLayout.results >= 200, JSON.stringify(readonlyLayout));
    check('只读连接下上下分栏拿到主区域高度', readonlyLayout.split >= 360, JSON.stringify(readonlyLayout));
    check('只读连接下状态栏只占一行', readonlyLayout.status > 0 && readonlyLayout.status <= 40, JSON.stringify(readonlyLayout));
    check('只读连接在工具栏里有提示', readonlyLayout.readonlyHint === true, JSON.stringify(readonlyLayout));
    await page.shot('10-只读连接SQL工作台');

    // 最后回头看一眼服务端日志。整轮冒烟刷新过页面、切过连接，SSE 与在途请求被断了好几次 ——
    // 这些「对端先走了」此前每次都留下一条带整页栈的 ERROR，用户看到的现象就是应用起着不动、
    // 日志定期刷错误。跑完一整轮正常操作，服务端不该记下任何 ERROR。
    const serverLog = readFileSync(path.join(WORK_DIR, 'server.log'), 'utf8');
    const errorLines = serverLog.split('\n').filter((line) => / ERROR /.test(line));
    check('服务端日志里没有 ERROR', errorLines.length === 0, errorLines.slice(0, 3).join(' ⏎ '));
  }


} catch (error) {
  failures.push(String(error.message || error));
  console.log(`  ✗ ${error.message || error}`);
} finally {
  page?.close();
  chrome.kill('SIGKILL'); // 测试故意保留草稿，不能让 beforeunload 阻止关闭独立测试浏览器。
  await new Promise(resolve => chrome.exitCode != null ? resolve() : chrome.once('exit', resolve));
  rmSync(CHROME_PROFILE, { recursive: true, force: true });
  server?.kill();
}

if (failures.length > 0) {
  console.log(`\n界面冒烟失败：${failures.length} 项`);
  process.exit(1);
}
console.log('\n界面冒烟通过');
