/**
 * CLI 端到端冒烟（验收流程 2）：
 * 启动真实后端 JAR（LOCAL 认证模式） -> 通过 Web 会话登记两条 H2 共享连接并播种中文测试数据 ->
 * 创建仅允许第一条连接的 PAT -> 用打包后的 cli/dist/mdd.js 跑通：
 *   1) mdd status --format json（验证 PAT 元信息）
 *   2) mdd conn ls --format json（仅见白名单连接，另一条共享连接不可见）
 *   3) mdd sql query（读取中文数据成功；写语句被 /api/sql/query 拒绝；访问白名单外连接返回退出码 3）
 *   4) mdd export --format csv --bom -o <file>（验证 UTF-8 BOM 与中文数据）
 *   5) 吊销 PAT 后再次调用 mdd conn ls 返回退出码 3
 *
 * 用法：
 *   node scripts/cli-smoke.mjs [--jar backend/target/web-db-admin-*.jar] [--port 8097] [--work <dir>]
 */
import assert from 'node:assert/strict';
import { closeSync, existsSync, mkdirSync, mkdtempSync, openSync, readdirSync, readFileSync, rmSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawn, spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const rootDir = path.resolve(scriptDir, '..');
const args = process.argv.slice(2);
const option = (name, fallback) => {
  const idx = args.indexOf(name);
  return idx >= 0 && args[idx + 1] ? args[idx + 1] : fallback;
};

function findBackendJar() {
  const explicit = option('--jar', '');
  if (explicit) return path.resolve(explicit);
  const targetDir = path.join(rootDir, 'backend', 'target');
  if (!existsSync(targetDir)) throw new Error(`找不到 ${targetDir}，请先运行 mvn package -DskipTests`);
  const candidates = readdirSync(targetDir)
    .filter((name) => name.endsWith('.jar') && !name.endsWith('.jar.original') && !name.includes('sources'))
    .sort();
  if (candidates.length === 0) throw new Error(`在 ${targetDir} 中未找到可执行的后端 JAR`);
  return path.join(targetDir, candidates[0]);
}

const jarPath = findBackendJar();
const cliEntry = path.join(rootDir, 'cli', 'dist', 'mdd.js');
if (!existsSync(cliEntry)) throw new Error(`找不到 ${cliEntry}，请先运行 cd cli && npm run build`);

const port = Number(option('--port', '8097'));
const baseUrl = `http://127.0.0.1:${port}`;
const workDir = option('--work', '')
  ? path.resolve(option('--work', ''))
  : mkdtempSync(path.join(os.tmpdir(), 'mydatadev-cli-smoke-'));
mkdirSync(workDir, { recursive: true });

const password = 'cli-smoke-password-at-least-12';
const logPath = path.join(workDir, 'server.log');
const logFd = openSync(logPath, 'w');

const javaBin = process.env.JAVA_HOME
  ? path.join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java')
  : 'java';

const serverProc = spawn(
  javaBin,
  [
    '-jar',
    jarPath,
    `--server.port=${port}`,
    '--app.auth.mode=LOCAL',
    '--app.auth.username=admin',
    `--app.auth.password=${password}`
  ],
  {
    cwd: workDir,
    stdio: ['ignore', logFd, logFd],
    env: { ...process.env }
  }
);

const cookies = new Map();
let csrfHeader = 'X-XSRF-TOKEN';
let csrfToken = '';

async function webFetch(urlPath, init = {}) {
  const cookieHeader = [...cookies.entries()].map(([k, v]) => `${k}=${v}`).join('; ');
  const headers = {
    ...(cookieHeader ? { Cookie: cookieHeader } : {}),
    ...(csrfToken ? { [csrfHeader]: csrfToken } : {}),
    ...(init.headers || {})
  };
  const res = await fetch(`${baseUrl}${urlPath}`, { ...init, headers, signal: AbortSignal.timeout(30_000) });
  for (const raw of res.headers.getSetCookie()) {
    const [pair] = raw.split(';');
    const eq = pair.indexOf('=');
    if (eq > 0) cookies.set(pair.slice(0, eq), pair.slice(eq + 1));
  }
  return res;
}

function runMdd(mddArgs, env = {}) {
  return spawnSync(process.execPath, [cliEntry, ...mddArgs], {
    cwd: workDir,
    encoding: 'utf8',
    env: {
      ...process.env,
      MDD_SERVER: baseUrl,
      ...env
    },
    timeout: 30_000
  });
}

async function waitForHealth() {
  const deadline = Date.now() + 90_000;
  while (Date.now() < deadline) {
    if (serverProc.exitCode !== null) {
      throw new Error(`后端进程提前退出（code=${serverProc.exitCode}）：\n${readFileSync(logPath, 'utf8')}`);
    }
    try {
      const res = await fetch(`${baseUrl}/actuator/health`, { signal: AbortSignal.timeout(3_000) });
      if (res.ok) {
        const body = await res.json();
        if (body.status === 'UP') return;
      }
    } catch {
      // retry
    }
    await new Promise((r) => setTimeout(r, 500));
  }
  throw new Error(`后端在 90s 内未就绪：\n${readFileSync(logPath, 'utf8')}`);
}

try {
  console.log(`CLI 冒烟：启动后端 ${path.basename(jarPath)} @ ${baseUrl} ...`);
  await waitForHealth();

  // 1) Web 会话登录
  const statusRes = await webFetch('/api/auth/status');
  assert.equal(statusRes.status, 200);
  const statusBody = await statusRes.json();
  csrfHeader = statusBody.csrfHeaderName || 'X-XSRF-TOKEN';
  csrfToken = statusBody.csrfToken || '';

  const loginRes = await webFetch('/api/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: 'admin', password })
  });
  assert.equal(loginRes.status, 200, '管理员登录失败');
  const loginBody = await loginRes.json();
  if (loginBody.csrfToken) csrfToken = loginBody.csrfToken;

  // 2) 创建两条 H2 连接（均设为 SHARED），并播种中文数据
  const createConn = async (name, dbName) => {
    const r = await webFetch('/api/connections', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        name,
        dbType: 'h2',
        environment: 'dev',
        jdbcUrl: `jdbc:h2:mem:${dbName};DB_CLOSE_DELAY=-1`,
        username: 'sa',
        password: '',
        readonly: false
      })
    });
    assert.equal(r.status, 200, `创建连接 ${name} 失败`);
    const conn = await r.json();
    const policyRes = await webFetch(`/api/admin/access/connections/${conn.id}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ accessMode: 'SHARED', grants: [] })
    });
    assert.equal(policyRes.status, 200);
    return conn;
  };

  const allowedConn = await createConn('smoke-allowed', 'cli_smoke_allowed');
  const blockedConn = await createConn('smoke-blocked', 'cli_smoke_blocked');

  for (const sql of [
    'CREATE TABLE smoke_orders (id INT PRIMARY KEY, title VARCHAR(100))',
    "INSERT INTO smoke_orders VALUES (1, '测试订单一')",
    "INSERT INTO smoke_orders VALUES (2, '测试订单二')"
  ]) {
    const r = await webFetch('/api/sql/execute', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ connectionId: allowedConn.id, sql })
    });
    assert.equal(r.status, 200, `播种 SQL 失败：${sql}`);
  }

  // 3) 创建仅允许 allowedConn 的 PAT
  const patRes = await webFetch('/api/auth/tokens', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      name: 'ci-smoke-token',
      expiresInDays: 7,
      connectionScope: 'LISTED',
      connectionIds: [allowedConn.id]
    })
  });
  assert.equal(patRes.status, 200, '创建 PAT 失败');
  const { tokenInfo, token } = await patRes.json();
  assert.ok(token.startsWith('mdd_pat_'));

  // 4) 验证 mdd status
  const statusCmd = runMdd(['status', '--format', 'json'], { MDD_TOKEN: token });
  assert.equal(statusCmd.status, 0, statusCmd.stderr);
  const statusJson = JSON.parse(statusCmd.stdout);
  assert.equal(statusJson.identity.authType, 'PAT');
  assert.equal(statusJson.identity.connectionScope, 'LISTED');

  // 5) 验证 mdd conn ls（只返回白名单连接）
  const lsCmd = runMdd(['conn', 'ls', '--format', 'json'], { MDD_TOKEN: token });
  assert.equal(lsCmd.status, 0, lsCmd.stderr);
  const visibleConns = JSON.parse(lsCmd.stdout);
  assert.equal(visibleConns.length, 1);
  assert.equal(visibleConns[0].name, 'smoke-allowed');

  // 6) 验证 mdd sql query（成功读取中文 + 拒绝写入 + 拒绝白名单外共享连接）
  const queryCmd = runMdd(
    ['sql', 'query', 'smoke-allowed', '-c', 'SELECT id, title FROM smoke_orders ORDER BY id', '--format', 'json'],
    { MDD_TOKEN: token }
  );
  assert.equal(queryCmd.status, 0, queryCmd.stderr);
  const queryJson = JSON.parse(queryCmd.stdout);
  assert.equal(queryJson.rows.length, 2);
  assert.equal(queryJson.rows[0][1], '测试订单一');

  const writeBlocked = runMdd(
    ['sql', 'query', 'smoke-allowed', '-c', "INSERT INTO smoke_orders VALUES (3, 'nope')"],
    { MDD_TOKEN: token }
  );
  assert.notEqual(writeBlocked.status, 0, 'sql query 不应允许写入');
  assert.ok(writeBlocked.stderr.includes('READONLY_QUERY_REQUIRED'), writeBlocked.stderr);

  const scopeBlocked = runMdd(
    ['api', 'POST', '/api/sql/query', '-f', `connectionId=${blockedConn.id}`, '-f', 'sql=SELECT 1'],
    { MDD_TOKEN: token }
  );
  assert.equal(scopeBlocked.status, 3, `白名单外共享连接应返回退出码 3，实际 ${scopeBlocked.status}: ${scopeBlocked.stderr}`);

  // 7) 验证 mdd export CSV + UTF-8 BOM
  const exportFile = path.join(workDir, 'orders.csv');
  const exportCmd = runMdd(
    ['export', 'smoke-allowed', '-c', 'SELECT id, title FROM smoke_orders ORDER BY id', '--format', 'csv', '--bom', '-o', exportFile],
    { MDD_TOKEN: token }
  );
  assert.equal(exportCmd.status, 0, exportCmd.stderr);
  const exportedBytes = readFileSync(exportFile);
  assert.equal(exportedBytes[0], 0xef);
  assert.equal(exportedBytes[1], 0xbb);
  assert.equal(exportedBytes[2], 0xbf);
  assert.ok(exportedBytes.subarray(3).toString('utf8').includes('测试订单二'));

  // 8) 吊销 PAT 后下一次调用必须返回退出码 3
  const revokeRes = await webFetch(`/api/auth/tokens/${tokenInfo.id}`, { method: 'DELETE' });
  assert.equal(revokeRes.status, 200);
  const afterRevoke = runMdd(['conn', 'ls'], { MDD_TOKEN: token });
  assert.equal(afterRevoke.status, 3, `吊销后应返回退出码 3，实际 ${afterRevoke.status}: ${afterRevoke.stderr}`);

  console.log('✓ CLI 端到端冒烟全部通过。');
} finally {
  serverProc.kill();
  closeSync(logFd);
  if (!option('--work', '')) {
    rmSync(workDir, { recursive: true, force: true });
  }
}
