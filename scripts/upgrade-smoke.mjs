/**
 * 升级回归：上一个发行版建好的数据目录，交给当前代码原样打开。
 *
 * 用户升级就是「新 JAR + 旧数据目录」这一件事：元数据库要由 Flyway 从旧版本停下的地方接着迁移，
 * 连接密码要用同一把主密钥解开，管理员账号不会再经 DB_ADMIN_WEB_PASSWORD 重建。任何一件坏了，
 * 用户看到的都是「升级之后连接打不开、登录不进去」。单测里的迁移永远从空库一路建到最新，从来
 * 不经过「停在旧版本」这个状态，所以这件事只能拿真实的旧版本产物来测。
 *
 *   node scripts/upgrade-smoke.mjs --from <旧版本 web.jar> --to <当前 JAR> [--work 目录] [--port 8097]
 *
 * 只走 API，不开浏览器：界面由 ui-smoke.mjs 负责，这里要看的是数据有没有跟过来，所以 --to 用不带
 * 前端的后端 JAR 就够了。目标库是带密码的 H2 文件库 —— 换成空密码或内存库，主密钥解错了也照样
 * 连得上，「测试连接成功」就什么都证明不了。
 */
import { existsSync, mkdirSync, mkdtempSync, openSync, readdirSync, readFileSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawn } from 'node:child_process';

const args = process.argv.slice(2);
const option = (name, fallback) => {
  const index = args.indexOf(name);
  return index >= 0 && args[index + 1] ? args[index + 1] : fallback;
};
const FROM = option('--from', '');
const TO = option('--to', '');
if (!FROM || !TO) {
  console.error('用法：node scripts/upgrade-smoke.mjs --from <旧版本 JAR> --to <当前 JAR> [--work 目录] [--port 8097]');
  process.exit(2);
}
const PORT = Number(option('--port', '8097'));
const APP_URL = `http://127.0.0.1:${PORT}`;
const WORK_DIR = path.resolve(option('--work', '') || mkdtempSync(path.join(os.tmpdir(), 'mydatadev-upgrade-')));
// 残留数据会让结论失真：「升级后还在」的可能是上一轮留下的东西。
if (existsSync(WORK_DIR) && readdirSync(WORK_DIR).length > 0) {
  console.error(`工作目录必须为空：${WORK_DIR}`);
  process.exit(2);
}
mkdirSync(WORK_DIR, { recursive: true });

const ADMIN_PASSWORD = 'upgrade-smoke-password-12';
const TARGET_PASSWORD = 'upgrade-target-secret';
const CONNECTION_NAME = '升级回归库';
const SNIPPET_NAME = '升级前保存的片段';
const CUSTOMERS = ['升级前的客户', 'emoji 🙂 客户', "O'Brien"];
const MARKER_SQL = "SELECT customer FROM upgrade_orders WHERE customer = '升级前的客户'";

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const failures = [];
const check = (name, ok, detail = '') => {
  console.log(ok ? `  ✓ ${name}` : `  ✗ ${name}${detail ? ` — ${detail}` : ''}`);
  if (!ok) failures.push(name);
};

/** 每个进程一份会话：Cookie 与 CSRF 令牌都跟着服务端进程走，升级后要重新登录。 */
function apiSession() {
  const cookies = new Map();
  let csrf = null;
  async function request(method, url, body) {
    const headers = { Cookie: [...cookies].map(([key, value]) => `${key}=${value}`).join('; ') };
    if (csrf) headers[csrf.name] = csrf.token;
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    const response = await fetch(APP_URL + url, {
      method, headers, body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(30_000)
    });
    for (const cookie of response.headers.getSetCookie()) {
      const [pair] = cookie.split(';');
      const index = pair.indexOf('=');
      cookies.set(pair.slice(0, index), pair.slice(index + 1));
    }
    const text = await response.text();
    if (!response.ok) throw new Error(`${method} ${url} 返回 ${response.status}：${text.slice(0, 300)}`);
    return text ? JSON.parse(text) : null;
  }
  return {
    get: (url) => request('GET', url),
    post: (url, body) => request('POST', url, body),
    async login() {
      const status = await request('GET', '/api/auth/status');
      csrf = { name: status.csrfHeaderName || 'X-XSRF-TOKEN', token: status.csrfToken };
      const loggedIn = await request('POST', '/api/auth/login', { username: 'admin', password: ADMIN_PASSWORD });
      // 登录会轮换 CSRF 令牌，继续用旧令牌的写请求会被拒。
      const current = loggedIn?.csrfToken ? loggedIn : await request('GET', '/api/auth/status');
      csrf = { name: current.csrfHeaderName || csrf.name, token: current.csrfToken };
    }
  };
}

const running = new Set();
process.once('exit', () => {
  for (const server of running) server.kill('SIGKILL');
});

async function healthy() {
  try {
    return (await fetch(`${APP_URL}/actuator/health`, { signal: AbortSignal.timeout(5_000) })).ok;
  } catch {
    return false;
  }
}

async function startServer(jar, label, env) {
  // 端口上已经有别的实例在应答的话，下面的健康检查会拿它的结果冒充这一次启动。
  if (await healthy()) throw new Error(`端口 ${PORT} 已被占用`);
  const log = openSync(path.join(WORK_DIR, `${label}.log`), 'w');
  console.log(`启动 ${path.basename(jar)}（${label}）`);
  const server = spawn('java', ['-jar', path.resolve(jar), '--spring.profiles.active=web', `--server.port=${PORT}`],
    { cwd: WORK_DIR, env, stdio: ['ignore', log, log] });
  running.add(server);
  server.once('exit', () => running.delete(server));
  for (let attempt = 0; attempt < 90 && running.has(server); attempt += 1) {
    if (await healthy()) return server;
    await sleep(2000);
  }
  const logFile = path.join(WORK_DIR, `${label}.log`);
  if (!running.has(server)) {
    // Spring 的异常链一层层包下来，真正的原因在最后一个 Caused by（比如主密钥丢了拒绝启动）。
    const cause = readFileSync(logFile, 'utf8').match(/^Caused by: .*$/gm)?.pop() ?? '';
    throw new Error(`${label} 启动后退出（退出码 ${server.exitCode}）${cause ? `：${cause}` : ''}，看 ${logFile}`);
  }
  server.kill('SIGKILL');
  throw new Error(`${label} 没能在 3 分钟内就绪，看 ${logFile}`);
}

/** 优雅停机：强杀会让元数据库停在没落盘的状态，那测的就是另一件事了。 */
async function stopServer(server, label) {
  if (!running.has(server)) throw new Error(`${label} 已经提前退出，看 ${path.join(WORK_DIR, `${label}.log`)}`);
  const exited = new Promise((resolve) => server.once('exit', resolve));
  server.kill('SIGTERM');
  let timer;
  const timedOut = await Promise.race([
    exited.then(() => false),
    new Promise((resolve) => { timer = setTimeout(resolve, 60_000, true); })
  ]);
  clearTimeout(timer);
  if (timedOut) {
    server.kill('SIGKILL');
    throw new Error(`${label} 60 秒内没有完成优雅停机`);
  }
}

console.log(`${path.basename(FROM)} → ${path.basename(TO)}，工作目录 ${WORK_DIR}`);
const { DB_ADMIN_WEB_PASSWORD: _initialPassword, ...inheritedEnv } = process.env;
try {
  // 第一段：旧版本当作新装的一台机器，建出一份有内容的数据目录。
  const previous = await startServer(FROM, 'previous', { ...inheritedEnv, DB_ADMIN_WEB_PASSWORD: ADMIN_PASSWORD });
  const before = apiSession();
  await before.login();
  const connection = await before.post('/api/connections', {
    name: CONNECTION_NAME,
    dbType: 'h2',
    jdbcUrl: `jdbc:h2:file:${path.join(WORK_DIR, 'target-db', 'upgrade')}`,
    username: 'sa',
    password: TARGET_PASSWORD,
    environment: 'dev',
    readonly: false
  });
  const run = (sql) => before.post('/api/sql/execute', { connectionId: connection.id, sql });
  await run('CREATE TABLE upgrade_orders(id INT PRIMARY KEY, customer VARCHAR(60) NOT NULL)');
  await run(`INSERT INTO upgrade_orders VALUES ${CUSTOMERS.map((name, index) => `(${index + 1}, '${name.replaceAll("'", "''")}')`).join(', ')}`);
  await run(MARKER_SQL);
  const snippet = await before.post('/api/sql-snippets', { name: SNIPPET_NAME, sql: MARKER_SQL, dbType: 'h2' });
  const auditBefore = (await before.get(`/api/audit?connectionId=${connection.id}&pageSize=100`)).items.map((event) => event.id);
  console.log(`旧版本播种完成：连接 #${connection.id}、片段 #${snippet.id}、${auditBefore.length} 条连接审计`);
  await stopServer(previous, 'previous');

  // 第二段：当前代码打开同一个目录。不传初始化密码 —— 账号库非空时本就不该用它，
  // 这样登录成功才证明旧版本存下的密码哈希还认得。
  const current = await startServer(TO, 'current', inheritedEnv);
  const migrated = /Successfully applied (\d+) migrations?/.exec(readFileSync(path.join(WORK_DIR, 'current.log'), 'utf8'));
  console.log(migrated
    ? `当前版本在旧数据上执行了 ${migrated[1]} 个迁移`
    : '当前版本没有新的迁移：本轮只验证了旧数据目录可以原样复用');
  const after = apiSession();
  await after.login();
  check('管理员用升级前的密码登录', true);

  const restored = (await after.get('/api/connections')).find((item) => item.id === connection.id);
  check('连接配置原样保留', restored?.name === CONNECTION_NAME && restored?.jdbcUrl === connection.jdbcUrl,
    JSON.stringify(restored ?? null));
  let tested;
  try {
    tested = await after.post(`/api/connections/${connection.id}/test`);
  } catch (error) {
    tested = { ok: false, message: error.message };
  }
  check('连接密码用同一把主密钥解得开', tested?.ok === true, tested?.message);
  let customers = [];
  try {
    const result = await after.post('/api/sql/execute', { connectionId: connection.id, sql: 'SELECT customer FROM upgrade_orders ORDER BY id' });
    customers = (result?.rows ?? []).map((row) => String(row[0]));
  } catch (error) {
    customers = [error.message];
  }
  check('经这条连接读回升级前写入的数据', JSON.stringify(customers) === JSON.stringify(CUSTOMERS), JSON.stringify(customers));
  const history = await after.get(`/api/sql/history?connectionId=${connection.id}&scope=all&limit=50`);
  check('SQL 历史保留', history.some((item) => item.sql?.includes('升级前的客户')));
  const snippets = await after.get('/api/sql-snippets');
  check('SQL 片段保留', snippets.some((item) => item.id === snippet.id && item.name === SNIPPET_NAME));
  const auditAfter = new Set((await after.get(`/api/audit?connectionId=${connection.id}&pageSize=100`)).items.map((event) => event.id));
  check('连接审计记录保留', auditBefore.length > 0 && auditBefore.every((id) => auditAfter.has(id)),
    `升级前 ${auditBefore.length} 条，升级后 ${auditAfter.size} 条`);
  await stopServer(current, 'current');
} catch (error) {
  check('升级流程跑完', false, error.message);
}

if (failures.length) {
  console.log(`\n升级回归失败：${failures.length} 项，服务日志在 ${WORK_DIR}`);
  process.exit(1);
}
console.log('\n升级回归通过');
