import { mkdtemp, readFile, rm } from 'node:fs/promises';
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
  let authMode: 'LOCAL' | 'DISABLED' = 'LOCAL';
  let apiVersion = 1;

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
          res.end(JSON.stringify({
            authenticated: true,
            authType: authMode === 'DISABLED' ? 'DISABLED' : 'PAT',
            username: authMode === 'DISABLED' ? 'admin' : 'ci.bot',
            role: 'OPERATOR',
            tokenId: 'ok',
            tokenName: 'ci',
            connectionScope: 'LISTED',
            allowedConnectionIds: [1]
          }));
          return;
        }
        if (req.url === '/api/connections') {
          res.writeHead(200, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify([
            { id: 1, name: '订单库-dev', dbType: 'h2', environment: 'dev', jdbcUrl: 'jdbc:h2:mem:orders', readonly: false },
            { id: 2, name: '订单库-prod', dbType: 'mysql', environment: 'prod', jdbcUrl: 'jdbc:mysql://prod/orders', readonly: false }
          ]));
          return;
        }
        if (req.url === '/api/connections/1/test') {
          res.writeHead(200, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ ok: false, message: 'TCP 连接被拒绝', steps: [{ label: 'TCP', ok: false }] }));
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
        if (req.url === '/api/sql/export') {
          res.writeHead(200, { 'Content-Type': 'text/csv; charset=utf-8', 'X-Export-Truncated': 'false' });
          res.end('id,name\n1,中文订单\n');
          return;
        }
        if (req.url === '/api/redirect-out') {
          res.writeHead(302, { Location: `${secondaryUrl}/leaked` });
          res.end();
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

  function captureRun(args: string[], env: NodeJS.ProcessEnv = {}) {
    const out: string[] = [];
    const err: string[] = [];
    const stdout = new PassThrough();
    const stderr = new PassThrough();
    const stdin = new PassThrough();
    stdout.on('data', (c) => out.push(c.toString('utf8')));
    stderr.on('data', (c) => err.push(c.toString('utf8')));
    return runCli(['node', 'mdd', ...args], {
      stdout,
      stderr,
      stdin,
      isStdoutTty: false,
      isStdinTty: false,
      env
    }).then((code) => ({ code, stdout: out.join(''), stderr: err.join('') }));
  }

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
      expect(raw[0]).toBe(0xef);
      expect(raw[1]).toBe(0xbb);
      expect(raw[2]).toBe(0xbf);
      expect(raw.subarray(3).toString('utf8')).toContain('中文订单');
    } finally {
      await rm(dir, { recursive: true, force: true });
    }
  });

  it('rejects full URL in mdd api and strips Authorization on cross-origin redirect', async () => {
    const bad = await captureRun(
      ['api', 'GET', 'https://evil.example/api/connections'],
      { MDD_SERVER: baseUrl, MDD_TOKEN: 'mdd_pat_ok_secret' }
    );
    expect(bad.code).toBe(ExitCode.USAGE_ERROR);

    secondaryRecorded.length = 0;
    const redir = await captureRun(
      ['api', 'GET', '/api/redirect-out'],
      { MDD_SERVER: baseUrl, MDD_TOKEN: 'mdd_pat_ok_secret' }
    );
    expect(redir.code).toBe(ExitCode.OK);
    expect(secondaryRecorded).toHaveLength(1);
    expect(secondaryRecorded[0].headers.authorization).toBeUndefined();
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
