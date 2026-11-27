import { lstat, mkdir, mkdtemp, readdir, readFile, rm, stat, symlink, writeFile } from 'node:fs/promises';
import http from 'node:http';
import os from 'node:os';
import path from 'node:path';
import { PassThrough } from 'node:stream';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { ExitCode } from '../src/errors.js';
import { runCli } from '../src/index.js';

interface RecordedRequest {
  method: string;
  url: string;
  headers: http.IncomingHttpHeaders;
  body: string;
}

describe('CLI HTTP client and commands', () => {
  let server: http.Server;
  let secondaryServer: http.Server;
  let baseUrl: string;
  let secondaryUrl: string;
  const recorded: RecordedRequest[] = [];
  const secondaryRecorded: RecordedRequest[] = [];
  const transaction = { id: 'e205989f-de89-4d09-aa24-1ea47f6ad3c1', connectionId: 1 };
  const task = { id: 42, status: 'QUEUED', phase: 'QUEUED', statementTotal: 2, statementCurrent: 0, successCount: 0 };
  let authMode: 'LOCAL' | 'DISABLED' = 'LOCAL';
  let apiVersion = 1;
  let deferWhoAmI: ((respond: () => void) => void) | undefined;
  let connectionLists: Array<Array<{ id: number; name: string }>> = [];
  let acknowledgeCancellation = false;

  beforeAll(async () => {
    secondaryServer = http.createServer((req, res) => {
      secondaryRecorded.push({ method: req.method || '', url: req.url || '', headers: req.headers, body: '' });
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ redirected: true }));
    });
    await new Promise<void>((resolve) => secondaryServer.listen(0, '127.0.0.1', resolve));
    const secAddr = secondaryServer.address();
    secondaryUrl = `http://127.0.0.1:${typeof secAddr === 'object' && secAddr ? secAddr.port : 0}`;

    server = http.createServer((req, res) => {
      const chunks: Buffer[] = [];
      req.on('data', (chunk) => chunks.push(chunk));
      req.on('end', () => {
        const body = Buffer.concat(chunks).toString('utf8');
        recorded.push({ method: req.method || '', url: req.url || '', headers: req.headers, body });

        if (req.url === '/api/system/info') {
          res.writeHead(200, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({
            product: 'MyDataDev',
            version: '0.10.2',
            apiVersion,
            mode: 'web',
            authMode
          }));
          return;
        }
        if (req.url === '/api/auth/whoami') {
          if (authMode === 'LOCAL' && req.headers.authorization !== 'Bearer mdd_pat_ok_secret') {
            res.writeHead(401, { 'Content-Type': 'application/json' });
            res.end(JSON.stringify({ ok: false, code: 'INVALID_ACCESS_TOKEN', message: '令牌无效' }));
            return;
          }
          res.writeHead(200, { 'Content-Type': 'application/json' });
          const respond = () => res.end(JSON.stringify({
            authenticated: true,
            authType: authMode === 'DISABLED' ? 'DISABLED' : 'PAT',
            username: authMode === 'DISABLED' ? 'admin' : 'ci.bot',
            role: 'OPERATOR',
            tokenId: 'ok',
            tokenName: 'ci',
            connectionScope: 'LISTED',
            allowedConnectionIds: [1]
          }));
          if (deferWhoAmI) deferWhoAmI(respond);
          else respond();
          return;
        }
        if (req.url === '/api/connections') {
          res.writeHead(200, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify(connectionLists.shift() ?? [
            { id: 1, name: '订单库-dev', dbType: 'h2', environment: 'dev', jdbcUrl: 'jdbc:h2:mem:orders', readonly: false },
            { id: 2, name: '订单库-prod', dbType: 'mysql', environment: 'prod', jdbcUrl: 'jdbc:mysql://prod/orders', readonly: false }
          ]));
          return;
        }
        if (req.url === '/api/connections/1/test') {
          res.writeHead(200, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ ok: false, message: '连接被拒绝', steps: [{ stage: 'TCP', elapsedMs: 5 }] }));
          return;
        }
        if (req.url === '/api/connections/1/server-info') {
          res.writeHead(200, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ product: 'H2', version: '2.3', driver: 'H2 JDBC' }));
          return;
        }
        if (req.url === '/api/sql/query') {
          const parsed = JSON.parse(body);
          if (parsed.connectionId === 2 && !req.headers['x-production-confirmation']) {
            res.writeHead(409, { 'Content-Type': 'application/json' });
            res.end(JSON.stringify({
              ok: false,
              code: 'PRODUCTION_CONFIRMATION_REQUIRED',
              message: '生产连接需要确认',
              confirmationText: '订单库-prod'
            }));
            return;
          }
          if (parsed.sql === 'select duplicate_labels') {
            res.writeHead(200, { 'Content-Type': 'application/json' });
            res.end(JSON.stringify({
              columns: [{ name: 'id' }, { name: 'id' }, { name: 'id_2' }],
              rows: [[10, 20, 30]], resultSet: true, truncated: true
            }));
            return;
          }
          res.writeHead(200, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({
            columns: [{ name: 'id' }, { name: 'name' }],
            rows: [[1, '测试订单']],
            affectedRows: 0,
            elapsedMs: 12,
            resultSet: true
          }));
          return;
        }
        if (req.url === '/api/sql/execute') {
          const parsed = JSON.parse(body);
          if (!parsed.unscopedMutationConfirmed) {
            res.writeHead(409, { 'Content-Type': 'application/json' });
            res.end(JSON.stringify({
              ok: false,
              code: 'UNSCOPED_MUTATION_CONFIRMATION_REQUIRED',
              message: '缺少顶层 WHERE 条件'
            }));
            return;
          }
          res.writeHead(200, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ columns: [], rows: [], affectedRows: 5, elapsedMs: 8, resultSet: false }));
          return;
        }
        if (req.url?.startsWith('/api/metadata/1/search?')) {
          res.writeHead(200, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ hits: [{ name: 'ORDERS', kind: 'TABLE', schemaName: 'PUBLIC' }], truncated: false }));
          return;
        }
        if (req.url === '/api/metadata/1') {
          res.writeHead(200, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ schemas: ['PUBLIC'] }));
          return;
        }
        if (req.url?.startsWith('/api/metadata/1?')) {
          const page = Number(new URL(req.url, baseUrl).searchParams.get('page') ?? '0');
          const objects = [
            { name: 'A_VIEW', type: 'VIEW' },
            { name: 'B_TABLE', type: 'TABLE' },
            { name: 'C_TABLE', type: 'TABLE' }
          ];
          res.writeHead(200, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ objects: objects.slice(page, page + 1), pageSize: 1, hasMore: page < 2 }));
          return;
        }
        if (req.url?.startsWith('/api/metadata/1/objects/ddl?')) {
          res.writeHead(200, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ ddl: 'CREATE TABLE orders (id INT);', source: 'driver' }));
          return;
        }
        if (req.url === '/api/sql/export' || req.url === '/api/data/table/export') {
          const { format, sql } = JSON.parse(body);
          if (sql === 'select portal') {
            res.writeHead(303, { Location: '/portal' });
            res.end();
            return;
          }
          if (sql === 'select interrupted') {
            res.writeHead(200, { 'Content-Type': 'text/csv', 'Content-Length': 80 });
            res.write('id\n1\n', () => res.destroy());
            return;
          }
          const exports: Record<string, [string, string | Buffer]> = {
            csv: ['text/csv', '\ufeffid,name\n1,中文订单\n'],
            json: ['application/json', '{"columns":["id"],"rows":[[1]]}'],
            xlsx: ['application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', Buffer.from([0x50, 0x4b, 0x03, 0x04, 0xff])],
            sql: ['text/plain', 'INSERT INTO "orders" ("id") VALUES (1);']
          };
          const [contentType, content] = exports[format];
          res.writeHead(200, { 'Content-Type': contentType, 'X-Export-Truncated': String(sql === 'select over_limit') });
          res.end(content);
          return;
        }
        if (req.url === '/api/sql/execute-script' || req.url === `/api/sql/transactions/${transaction.id}/execute`) {
          const { sql } = JSON.parse(body);
          if (sql === 'SELECT STALLED;') {
            if (!acknowledgeCancellation) {
              res.writeHead(200, { 'Content-Type': 'application/json' });
              res.flushHeaders();
            }
            return;
          }
          const failed = sql.includes('BROKEN');
          const result = { columns: [], rows: [], affectedRows: 1, elapsedMs: 8, resultSet: false };
          const results = [{ index: 1, status: 'SUCCESS', result }];
          if (!sql.startsWith('BEGIN')) {
            results.push({ index: 2, status: failed ? 'FAILED' : 'SUCCESS', result });
          }
          res.writeHead(200, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ status: failed ? 'FAILED' : 'SUCCESS', elapsedMs: 16, results, executedCount: results.length,
            ...(req.url.endsWith('/execute') ? { transaction } : {}) }));
          return;
        }
        if (req.url?.startsWith('/api/sql/executions/') && req.url.endsWith('/cancel')) {
          res.writeHead(200, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ ok: acknowledgeCancellation }));
          return;
        }
        if (req.url === '/portal') {
          res.writeHead(200, { 'Content-Type': 'text/html' });
          res.end('<html>Login</html>');
          return;
        }
        if (req.url?.startsWith('/api/sql/transactions')) {
          res.writeHead(200, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify(req.url.includes('/active?') ? { transaction } : transaction));
          return;
        }
        if (req.url?.startsWith('/api/sql-file-executions/')) {
          const response = req.url.endsWith('/cancel') ? { ...task, status: 'CANCELLED' } : task;
          res.writeHead(req.url.endsWith('/commands') ? 202 : 200, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify(response));
          return;
        }
        if (req.url === '/api/redirect-out') {
          res.writeHead(307, { Location: `${secondaryUrl}/api/echo` });
          res.end();
          return;
        }
        if (req.url === '/api/redirect-local') {
          res.writeHead(307, { Location: '/api/echo' });
          res.end();
          return;
        }
        if (req.url === '/api/echo') {
          res.writeHead(200, { 'Content-Type': 'application/json' });
          res.end(body || '{"id":9007199254740993}');
          return;
        }
        res.writeHead(404, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ ok: false, code: 'NOT_FOUND', message: 'not found' }));
      });
    });
    await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
    const addr = server.address();
    baseUrl = `http://127.0.0.1:${typeof addr === 'object' && addr ? addr.port : 0}`;
  });

  afterAll(async () => {
    await new Promise<void>((resolve) => server.close(() => resolve()));
    await new Promise<void>((resolve) => secondaryServer.close(() => resolve()));
  });

  function captureRun(
    args: string[],
    env: NodeJS.ProcessEnv = {},
    options: { configPath?: string; input?: string } = {}
  ) {
    const out: string[] = [];
    const err: string[] = [];
    const stdout = new PassThrough();
    const stderr = new PassThrough();
    const stdin = new PassThrough();
    stdout.on('data', (c) => out.push(c.toString('utf8')));
    stderr.on('data', (c) => err.push(c.toString('utf8')));
    stdin.end(options.input);
    return runCli(['node', 'mdd', ...args], {
      stdout,
      stderr,
      stdin,
      isStdoutTty: false,
      isStdinTty: false,
      env,
      configPath: options.configPath
    }).then((code) => ({ code, stdout: out.join(''), stderr: err.join('') }));
  }

  it('logs in with server and profile flags before or after the command', async () => {
    const dir = await mkdtemp(path.join(os.tmpdir(), 'mdd-login-'));
    const configPath = path.join(dir, 'config.json');
    try {
      authMode = 'DISABLED';
      const disabled = await captureRun(['login', '--server', baseUrl, '--profile', 'local-web'], {}, { configPath });
      expect(disabled.code).toBe(ExitCode.OK);
      expect(JSON.parse(await readFile(configPath, 'utf8'))).toEqual({
        defaultProfile: 'local-web', profiles: { 'local-web': { url: baseUrl, authMode: 'DISABLED' } }
      });

      authMode = 'LOCAL';
      const authenticated = await captureRun(
        ['--server', baseUrl, '--profile=pat-web', 'login', '--token-stdin'],
        {}, { configPath, input: 'mdd_pat_ok_secret\n' }
      );
      expect(authenticated.code).toBe(ExitCode.OK);
      const saved = JSON.parse(await readFile(configPath, 'utf8'));
      expect(saved.defaultProfile).toBe('pat-web');
      expect(saved.profiles['pat-web']).toEqual({ url: baseUrl, token: 'mdd_pat_ok_secret', authMode: 'LOCAL' });
      const missing = await captureRun(['login', '--profile', 'other'], {}, { configPath });
      expect(missing.code).toBe(ExitCode.USAGE_ERROR);
      expect(missing.stderr).toContain('MISSING_SERVER');
    } finally {
      authMode = 'LOCAL';
      await rm(dir, { recursive: true, force: true });
    }
  });

  it('renders metadata search hits from the REST API response', async () => {
    const res = await captureRun(
      ['meta', 'search', '订单库-dev', 'orders', '--schema', 'PUBLIC', '--limit', '5'],
      { MDD_SERVER: baseUrl, MDD_TOKEN: 'mdd_pat_ok_secret' }
    );
    expect(res.code).toBe(ExitCode.OK);
    expect(JSON.parse(res.stdout)).toEqual([{ name: 'ORDERS', kind: 'TABLE', schemaName: 'PUBLIC' }]);
    expect(recorded.at(-1)?.url).toBe('/api/metadata/1/search?keyword=orders&schema=PUBLIC&limit=5');
  });

  it('keeps using the selected REPL connection id and stops if the id disappears', async () => {
    const chosen = { id: 1, name: '2' };
    const other = { id: 2, name: '1' };
    connectionLists = [[chosen, other], [chosen, other], [other], [other]];
    recorded.length = 0;
    try {
      const env = { MDD_SERVER: baseUrl, MDD_TOKEN: 'mdd_pat_ok_secret' };
      const repl = await captureRun(['repl'], env, {
        input: 'conn use 1\nmeta namespaces\nsql exec insert into orders values (1);\nexit\n'
      });
      expect(repl.stdout).toContain('PUBLIC');
      expect(repl.stderr).toContain('CONNECTION_NOT_FOUND');
      expect(recorded.some((request) => request.url === '/api/sql/execute' || request.url === '/api/metadata/2')).toBe(false);
      const byName = await captureRun(['conn', 'info', 'name:1'], env);
      expect(JSON.parse(byName.stdout).connection.id).toBe(2);
    } finally {
      connectionLists = [];
    }
  });

  it('warns about truncated CSV and NDJSON queries and keeps every duplicate column', async () => {
    const env = { MDD_SERVER: baseUrl, MDD_TOKEN: 'mdd_pat_ok_secret' };
    const args = ['--quiet', 'sql', 'query', '1', '-c', 'select duplicate_labels'];
    const csv = await captureRun([...args, '--format', 'csv'], env);
    expect(csv.stdout).toBe('id,id,id_2\n10,20,30\n');
    expect(csv.stderr).toContain('结果已截断');
    const ndjson = await captureRun([...args, '--format', 'ndjson'], env);
    expect(JSON.parse(ndjson.stdout)).toEqual({ id: 10, id_3: 20, id_2: 30 });
    expect(ndjson.stderr).toContain('结果已截断');
  });

  it('applies metadata type filters before pagination and writes raw DDL to a pipe by default', async () => {
    const env = { MDD_SERVER: baseUrl, MDD_TOKEN: 'mdd_pat_ok_secret' };
    const first = await captureRun(['meta', 'objects', '1', '--type', 'table', '--page-size', '1'], env);
    expect(first.code).toBe(ExitCode.OK);
    expect(JSON.parse(first.stdout)).toEqual([{ name: 'B_TABLE', type: 'TABLE' }]);
    const second = await captureRun(['meta', 'objects', '1', '--type', 'table', '--page-size', '1', '--page', '1'], env);
    expect(JSON.parse(second.stdout)).toEqual([{ name: 'C_TABLE', type: 'TABLE' }]);
    const unfiltered = await captureRun(['meta', 'objects', '1', '--page', '0'], env);
    expect(JSON.parse(unfiltered.stdout)).toEqual([{ name: 'A_VIEW', type: 'VIEW' }]);

    const ddl = await captureRun(['meta', 'ddl', '1', 'orders'], env);
    expect(ddl.stdout).toBe('CREATE TABLE orders (id INT);\n');
    const json = await captureRun(['meta', 'ddl', '1', 'orders', '--format', 'json'], env);
    expect(JSON.parse(json.stdout).ddl).toBe('CREATE TABLE orders (id INT);');
  });

  it('uses server version and connection test stage in terminal output', async () => {
    const env = { MDD_SERVER: baseUrl, MDD_TOKEN: 'mdd_pat_ok_secret' };
    const info = await captureRun(['conn', 'info', '1', '--format', 'table'], env);
    expect(info.stdout).toContain('H2 2.3');
    const test = await captureRun(['conn', 'test', '1', '--format', 'table'], env);
    expect(test.stdout).toContain('TCP (5ms)');
  });

  it('probes /api/system/info without sending PAT and runs sql query with production confirmation', async () => {
    recorded.length = 0;
    const res = await captureRun(
      ['sql', 'query', '订单库-prod', '-c', 'select 1', '--confirm-production', '订单库-prod', '--format', 'json'],
      { MDD_SERVER: baseUrl, MDD_TOKEN: 'mdd_pat_ok_secret' }
    );
    expect(res.code).toBe(ExitCode.OK);
    const probe = recorded.find((r) => r.url === '/api/system/info');
    expect(probe?.headers.authorization).toBeUndefined();
    const queryReq = recorded.find((r) => r.url === '/api/sql/query');
    expect(queryReq?.headers.authorization).toBe('Bearer mdd_pat_ok_secret');
    expect(queryReq?.headers['x-production-confirmation']).toBe(encodeURIComponent('订单库-prod'));
    expect(JSON.parse(res.stdout).rows).toEqual([[1, '测试订单']]);
  });

  it('returns exit code 1 when conn test HTTP 200 has ok:false', async () => {
    const res = await captureRun(
      ['conn', 'test', '订单库-dev'],
      { MDD_SERVER: baseUrl, MDD_TOKEN: 'mdd_pat_ok_secret' }
    );
    expect(res.code).toBe(ExitCode.EXECUTION_FAILED);
    expect(res.stderr).toContain('CONNECTION_TEST_FAILED');
  });

  it('maps unscoped mutation rejection to exit code 2 and succeeds with --allow-unbounded-write', async () => {
    const blocked = await captureRun(
      ['sql', 'exec', '订单库-dev', '-c', 'delete from orders'],
      { MDD_SERVER: baseUrl, MDD_TOKEN: 'mdd_pat_ok_secret' }
    );
    expect(blocked.code).toBe(ExitCode.USAGE_ERROR);
    expect(blocked.stderr).toContain('--allow-unbounded-write');

    const ok = await captureRun(
      ['sql', 'exec', '订单库-dev', '-c', 'delete from orders', '--allow-unbounded-write'],
      { MDD_SERVER: baseUrl, MDD_TOKEN: 'mdd_pat_ok_secret' }
    );
    expect(ok.code).toBe(ExitCode.OK);
  });

  it('exports CSV to file with --format and -o simultaneously plus --bom', async () => {
    const dir = await mkdtemp(path.join(os.tmpdir(), 'mdd-export-'));
    try {
      const outFile = path.join(dir, 'orders.csv');
      const res = await captureRun(
        ['export', '订单库-dev', '-c', 'select id, name from orders', '--format', 'csv', '-o', outFile, '--bom'],
        { MDD_SERVER: baseUrl, MDD_TOKEN: 'mdd_pat_ok_secret' }
      );
      expect(res.code).toBe(ExitCode.OK);
      const raw = await readFile(outFile);
      expect(raw.toString('utf8')).toBe('\ufeffid,name\n1,中文订单\n');
    } finally {
      await rm(dir, { recursive: true, force: true });
    }
  });

  it('passes JSON, XLSX, and SQL export formats to the API', async () => {
    const env = { MDD_SERVER: baseUrl, MDD_TOKEN: 'mdd_pat_ok_secret' };
    const dir = await mkdtemp(path.join(os.tmpdir(), 'mdd-formats-'));
    try {
      const json = await captureRun(['export', '1', '--table', 'PUBLIC.orders', '--format', 'json'], env);
      expect(json.code).toBe(ExitCode.OK);
      expect(JSON.parse(json.stdout).rows).toEqual([[1]]);
      expect(JSON.parse(recorded.at(-1)!.body)).toMatchObject({ format: 'json', query: { tableName: 'orders', schemaName: 'PUBLIC' } });

      const outFile = path.join(dir, 'orders.xlsx');
      const xlsx = await captureRun(['--format=xlsx', 'export', '1', '-c', 'select id from orders', '-o', outFile], env);
      expect(xlsx.code).toBe(ExitCode.OK);
      expect(await readFile(outFile)).toEqual(Buffer.from([0x50, 0x4b, 0x03, 0x04, 0xff]));
      expect(JSON.parse(recorded.at(-1)!.body).format).toBe('xlsx');

      const sql = await captureRun(['export', '1', '-c', 'select 1 as id', '--format', 'sql', '--target-table', 'PUBLIC.orders'], env);
      expect(sql.code).toBe(ExitCode.OK);
      expect(sql.stdout).toContain('INSERT INTO');
      expect(JSON.parse(recorded.at(-1)!.body)).toMatchObject({ format: 'sql', targetTableParts: ['PUBLIC', 'orders'] });
    } finally {
      await rm(dir, { recursive: true, force: true });
    }
  });

  it('keeps the old export after a broken stream and reports truncation on stderr', async () => {
    const env = { MDD_SERVER: baseUrl, MDD_TOKEN: 'mdd_pat_ok_secret' };
    const dir = await mkdtemp(path.join(os.tmpdir(), 'mdd-export-integrity-'));
    try {
      const output = path.join(dir, 'result.csv');
      await writeFile(output, 'last good export', { mode: 0o600 });
      const failed = await captureRun(['export', '1', '-c', 'select interrupted', '-o', output], env);
      expect(failed.code).not.toBe(ExitCode.OK);
      expect(await readFile(output, 'utf8')).toBe('last good export');
      expect(await readdir(dir)).toEqual(['result.csv']);
      const replaced = await captureRun(['export', '1', '-c', 'select 1', '-o', output], env);
      expect(replaced.code).toBe(ExitCode.OK);
      expect(await readFile(output, 'utf8')).toBe('\ufeffid,name\n1,中文订单\n');
      if (process.platform !== 'win32') expect((await stat(output)).mode & 0o777).toBe(0o600);
      const truncated = await captureRun(['export', '1', '-c', 'select over_limit', '--quiet'], env);
      expect(truncated.code).toBe(ExitCode.OK);
      expect(truncated.stdout).toBe('\ufeffid,name\n1,中文订单\n');
      expect(truncated.stderr).toContain('结果已截断');
    } finally {
      await rm(dir, { recursive: true, force: true });
    }
  });

  it('rejects an HTML response after a same-origin redirect without modifying the export', async () => {
    const env = { MDD_SERVER: baseUrl, MDD_TOKEN: 'mdd_pat_ok_secret' };
    const dir = await mkdtemp(path.join(os.tmpdir(), 'mdd-export-html-'));
    try {
      const output = path.join(dir, 'existing.xlsx');
      await writeFile(output, 'last good export');
      const args = ['export', '1', '-c', 'select portal', '--format', 'xlsx'];
      const file = await captureRun([...args, '-o', output], env);
      expect(file.code).toBe(ExitCode.EXECUTION_FAILED);
      expect(file.stderr).toContain('UNEXPECTED_EXPORT_CONTENT');
      expect(await readFile(output, 'utf8')).toBe('last good export');
      const pipe = await captureRun(args, env);
      expect(pipe.code).toBe(ExitCode.EXECUTION_FAILED);
      expect(pipe.stdout).toBe('');
    } finally {
      await rm(dir, { recursive: true, force: true });
    }
  });

  it.skipIf(process.platform === 'win32')('replaces the target of a relative export symlink while retaining the link', async () => {
    const env = { MDD_SERVER: baseUrl, MDD_TOKEN: 'mdd_pat_ok_secret' };
    const dir = await mkdtemp(path.join(os.tmpdir(), 'mdd-export-link-'));
    try {
      const target = path.join(dir, 'real.csv');
      const output = path.join(dir, 'link.csv');
      await writeFile(target, 'last good export');
      await symlink('real.csv', output);
      const result = await captureRun(['export', '1', '-c', 'select 1', '-o', output], env);
      expect(result.code).toBe(ExitCode.OK);
      expect((await lstat(output)).isSymbolicLink()).toBe(true);
      expect(await readFile(target, 'utf8')).toBe('\ufeffid,name\n1,中文订单\n');
    } finally {
      await rm(dir, { recursive: true, force: true });
    }
  });

  it.skipIf(process.platform === 'win32')('follows directory links before interpreting parent segments in export targets', async () => {
    const env = { MDD_SERVER: baseUrl, MDD_TOKEN: 'mdd_pat_ok_secret' };
    const dir = await mkdtemp(path.join(os.tmpdir(), 'mdd-export-link-parent-'));
    try {
      const storage = path.join(dir, 'storage');
      const output = path.join(dir, 'link.csv');
      const unrelated = path.join(dir, 'result.csv');
      await mkdir(path.join(storage, 'nested'), { recursive: true });
      await writeFile(unrelated, 'unrelated file');
      await symlink(path.join(storage, 'nested'), path.join(dir, 'folder'));
      await symlink('folder/../result.csv', output);

      for (const previous of [undefined, 'old export']) {
        const target = path.join(storage, 'result.csv');
        if (previous !== undefined) await writeFile(target, previous);
        const result = await captureRun(['export', '1', '-c', 'select 1', '-o', output], env);
        expect(result.code).toBe(ExitCode.OK);
        expect((await lstat(output)).isSymbolicLink()).toBe(true);
        expect(await readFile(output, 'utf8')).toBe('\ufeffid,name\n1,中文订单\n');
        expect(await readFile(unrelated, 'utf8')).toBe('unrelated file');
      }
    } finally {
      await rm(dir, { recursive: true, force: true });
    }
  });

  it('executes a file as one server-side script, preserving single-result output and reporting partial failure', async () => {
    const env = { MDD_SERVER: baseUrl, MDD_TOKEN: 'mdd_pat_ok_secret' };
    const dir = await mkdtemp(path.join(os.tmpdir(), 'mdd-script-'));
    try {
      const file = path.join(dir, 'update.sql');
      const sql = 'INSERT INTO orders VALUES (1);\nINSERT INTO orders VALUES (2);';
      await writeFile(file, sql);
      const script = await captureRun(['sql', 'exec', '订单库-prod', '-f', file, '--confirm-production', '订单库-prod'], env);
      expect(script.code).toBe(ExitCode.OK);
      expect(JSON.parse(script.stdout)).toMatchObject({ status: 'SUCCESS', executedCount: 2 });
      expect(recorded.at(-1)).toMatchObject({ url: '/api/sql/execute-script', headers: { 'x-production-confirmation': encodeURIComponent('订单库-prod') } });
      expect(JSON.parse(recorded.at(-1)!.body)).toMatchObject({ sql, connectionId: 2 });

      await writeFile(file, 'BEGIN\n  NULL;\nEND;\n/\n');
      const block = await captureRun(['sql', 'exec', '1', '-f', file], env);
      expect(block.code).toBe(ExitCode.OK);
      expect(JSON.parse(block.stdout)).toMatchObject({ affectedRows: 1 });

      await writeFile(file, sql + '\nBROKEN;');
      const failed = await captureRun(['sql', 'exec', '1', '-f', file], env);
      expect(failed.code).toBe(ExitCode.EXECUTION_FAILED);
      expect(JSON.parse(failed.stdout).status).toBe('FAILED');
      expect(failed.stderr).toContain('SQL_SCRIPT_FAILED');
    } finally {
      await rm(dir, { recursive: true, force: true });
    }
  });

  it.each([true, false])('requests server cancellation after an SQL timeout, acknowledged=%s', async (acknowledged) => {
    const dir = await mkdtemp(path.join(os.tmpdir(), 'mdd-script-timeout-'));
    try {
      const file = path.join(dir, 'script.sql');
      await writeFile(file, 'SELECT STALLED;');
      acknowledgeCancellation = acknowledged;
      recorded.length = 0;
      const result = await captureRun(['--timeout', '1', 'sql', 'exec', '1', '-f', file],
        { MDD_SERVER: baseUrl, MDD_TOKEN: 'mdd_pat_ok_secret' });
      expect(result.code).toBe(ExitCode.UNREACHABLE);
      const script = recorded.find((r) => r.url === '/api/sql/execute-script');
      const executionId = JSON.parse(script!.body).executionId;
      expect(recorded.at(-1)).toMatchObject({ method: 'POST', url: `/api/sql/executions/${executionId}/cancel` });
      expect(JSON.parse(result.stderr.trim().split('\n').at(-1)!)).toMatchObject({
        code: 'SQL_EXECUTION_TIMEOUT',
        details: { executionId, cancellationAcknowledged: acknowledged }
      });
    } finally {
      await rm(dir, { recursive: true, force: true });
    }
  });

  it('uses the same server transaction id for execution and completion', async () => {
    const env = { MDD_SERVER: baseUrl, MDD_TOKEN: 'mdd_pat_ok_secret' };
    const standalone = await captureRun(['sql', 'exec', '1', '-c', 'rollback work;'], env);
    expect(standalone.code).toBe(ExitCode.USAGE_ERROR);
    expect(standalone.stderr).toContain('TRANSACTION_ID_REQUIRED');
    const begin = await captureRun(['sql', 'tx', 'begin', '1', '--schema', 'PUBLIC'], env);
    expect(begin.code).toBe(ExitCode.OK);
    expect(JSON.parse(begin.stdout).id).toBe(transaction.id);
    expect(JSON.parse(recorded.at(-1)!.body)).toEqual({ connectionId: 1, schemaName: 'PUBLIC' });
    const status = await captureRun(['sql', 'tx', 'status', '1'], env);
    expect(JSON.parse(status.stdout).id).toBe(transaction.id);
    const exec = await captureRun(['sql', 'tx', 'exec', transaction.id, '-c', 'delete from orders', '--allow-unbounded-write'], env);
    expect(exec.code).toBe(ExitCode.OK);
    expect(JSON.parse(recorded.at(-1)!.body).unscopedMutationConfirmed).toBe(true);
    expect(JSON.parse(exec.stdout).transaction.id).toBe(transaction.id);
    for (const action of ['commit', 'rollback']) {
      const done = await captureRun(['sql', 'tx', action, transaction.id], env);
      expect(done.code).toBe(ExitCode.OK);
      expect(recorded.at(-1)).toMatchObject({ url: `/api/sql/transactions/${transaction.id}/${action}`, method: 'POST' });
    }
  });

  it('submits background SQL and looks up or cancels the returned task id', async () => {
    const env = { MDD_SERVER: baseUrl, MDD_TOKEN: 'mdd_pat_ok_secret' };
    const sql = 'INSERT INTO orders VALUES (1);\nCOMMIT;';
    const submit = await captureRun(['sql', 'script', 'submit', '1', '-c', sql,
      '--transaction-mode', 'script', '--end-of-file', 'commit'], env);
    expect(submit.code).toBe(ExitCode.OK);
    expect(JSON.parse(submit.stdout).id).toBe(42);
    expect(recorded.at(-1)?.url).toBe('/api/sql-file-executions/commands');
    expect(JSON.parse(recorded.at(-1)!.body)).toMatchObject({ sql, transactionMode: 'SCRIPT', endOfFileAction: 'COMMIT' });
    const status = await captureRun(['sql', 'script', 'status', '42'], env);
    expect(JSON.parse(status.stdout).status).toBe('QUEUED');
    const cancel = await captureRun(['sql', 'script', 'cancel', '42'], env);
    expect(JSON.parse(cancel.stdout).status).toBe('CANCELLED');
    expect(recorded.at(-1)).toMatchObject({ url: '/api/sql-file-executions/42/cancel', method: 'POST' });
  });

  it('rejects full URLs and follows redirects only within the configured origin', async () => {
    const bad = await captureRun(
      ['api', 'GET', 'https://evil.example/api/connections'],
      { MDD_SERVER: baseUrl, MDD_TOKEN: 'mdd_pat_ok_secret' }
    );
    expect(bad.code).toBe(ExitCode.USAGE_ERROR);

    secondaryRecorded.length = 0;
    const redir = await captureRun(
      ['api', 'POST', '/api/redirect-out', '--data', '{"value":1}'],
      { MDD_SERVER: baseUrl, MDD_TOKEN: 'mdd_pat_ok_secret' }
    );
    expect(redir.code).toBe(ExitCode.EXECUTION_FAILED);
    expect(redir.stderr).toContain('CROSS_ORIGIN_REDIRECT');
    expect(secondaryRecorded).toHaveLength(0);

    const local = await captureRun(
      ['api', 'POST', '/api/redirect-local', '--data', '{"value":1}'],
      { MDD_SERVER: baseUrl, MDD_TOKEN: 'mdd_pat_ok_secret' }
    );
    expect(local.code).toBe(ExitCode.OK);
    expect(recorded.at(-1)).toMatchObject({ url: '/api/echo', body: '{"value":1}', headers: { authorization: 'Bearer mdd_pat_ok_secret' } });
  });

  it('reuses gateway settings only when logging into the same address', async () => {
    const dir = await mkdtemp(path.join(os.tmpdir(), 'mdd-login-gateway-'));
    const configPath = path.join(dir, 'config.json');
    const profile = {
      url: baseUrl + '/',
      headers: { 'X-Gateway': 'env:MDD_GATEWAY_TEST' },
      basicAuth: { username: 'test', passwordEnv: 'MDD_GATEWAY_PASSWORD_TEST' }
    };
    const env = { MDD_GATEWAY_TEST: 'local-test-value' };
    const login = ['login', '--server', baseUrl, '--profile', 'team', '--token-stdin'];
    try {
      await writeFile(configPath, JSON.stringify({ profiles: { team: profile } }));
      recorded.length = 0;
      const same = await captureRun(login, env, { configPath, input: 'mdd_pat_ok_secret' });
      expect(same.code).toBe(ExitCode.OK);
      expect(recorded[0].headers['x-gateway']).toBe('local-test-value');

      await writeFile(configPath, JSON.stringify({ profiles: { team: { ...profile, url: secondaryUrl } } }));
      recorded.length = 0;
      const moved = await captureRun(login, env, { configPath, input: 'mdd_pat_ok_secret' });
      expect(moved.code).toBe(ExitCode.OK);
      expect(recorded.every((request) => request.headers['x-gateway'] === undefined)).toBe(true);
      expect(JSON.parse(await readFile(configPath, 'utf8')).profiles.team).toEqual({
        url: baseUrl, token: 'mdd_pat_ok_secret', authMode: 'LOCAL'
      });
    } finally {
      await rm(dir, { recursive: true, force: true });
    }
  });

  it('keeps profiles from two logins that overlap during token verification', async () => {
    const dir = await mkdtemp(path.join(os.tmpdir(), 'mdd-login-concurrent-'));
    const configPath = path.join(dir, 'config.json');
    try {
      const pending: Array<() => void> = [];
      const bothWaiting = new Promise<void>((resolve) => {
        deferWhoAmI = (respond) => {
          pending.push(respond);
          if (pending.length === 2) resolve();
        };
      });
      const login = (profile: string) => captureRun(
        ['login', '--server', baseUrl, '--profile', profile, '--token-stdin'],
        {}, { configPath, input: 'mdd_pat_ok_secret' }
      );
      const logins = [login('first'), login('second')];
      await bothWaiting;
      for (const respond of pending) respond();
      expect((await Promise.all(logins)).map((login) => login.code)).toEqual([ExitCode.OK, ExitCode.OK]);
      const saved = JSON.parse(await readFile(configPath, 'utf8'));
      expect(Object.keys(saved.profiles).sort()).toEqual(['first', 'second']);
    } finally {
      deferWhoAmI = undefined;
      await rm(dir, { recursive: true, force: true });
    }
  });

  it('preserves exact JSON numbers in both API request fields and responses', async () => {
    const env = { MDD_SERVER: baseUrl, MDD_TOKEN: 'mdd_pat_ok_secret' };
    const get = await captureRun(['api', 'GET', '/api/echo'], env);
    expect(get.stdout).toBe('{"id":9007199254740993}\n');
    const post = await captureRun(['api', 'POST', '/api/echo', '-f', 'id=9007199254740993',
      '-f', 'nested={"value":123.45678901234567890}', '-f', 'name=orders'], env);
    const body = '{"id":9007199254740993,"nested":{"value":123.45678901234567890},"name":"orders"}';
    expect(recorded.at(-1)?.body).toBe(body);
    expect(post.stdout).toBe(body + '\n');
  });

  it('reports an invalid configured PAT as an authentication failure in status', async () => {
    const failure = await captureRun(['status'], { MDD_SERVER: baseUrl, MDD_TOKEN: 'mdd_pat_wrong_secret' });
    expect(failure.code).toBe(ExitCode.AUTH_ERROR);
    expect(failure.stderr).toContain('INVALID_ACCESS_TOKEN');
    const anonymous = await captureRun(['status', '--format', 'table'], { MDD_SERVER: baseUrl });
    expect(anonymous.code).toBe(ExitCode.OK);
    expect(anonymous.stdout).toContain('未登录');
  });

  it('never sends PAT in DISABLED mode and returns exit 3 on 401 and exit 6 on apiVersion mismatch', async () => {
    authMode = 'DISABLED';
    recorded.length = 0;
    const disabledRes = await captureRun(
      ['conn', 'ls'],
      { MDD_SERVER: baseUrl, MDD_TOKEN: 'mdd_pat_should_not_send' }
    );
    expect(disabledRes.code).toBe(ExitCode.OK);
    const connReq = recorded.find((r) => r.url === '/api/connections');
    expect(connReq?.headers.authorization).toBeUndefined();
    expect(connReq?.headers['x-user']).toBe('admin');
    expect(connReq?.headers['x-mydatadev-client']).toBe('cli');
    expect(disabledRes.stderr).toContain('服务端未启用认证');

    authMode = 'LOCAL';
    const badAuth = await captureRun(
      ['api', 'GET', '/api/auth/whoami'],
      { MDD_SERVER: baseUrl, MDD_TOKEN: 'mdd_pat_wrong_secret' }
    );
    expect(badAuth.code).toBe(ExitCode.AUTH_ERROR);

    apiVersion = 99;
    const mismatch = await captureRun(
      ['conn', 'ls'],
      { MDD_SERVER: baseUrl, MDD_TOKEN: 'mdd_pat_ok_secret' }
    );
    expect(mismatch.code).toBe(ExitCode.INCOMPATIBLE_VERSION);
    apiVersion = 1;
  });
});
