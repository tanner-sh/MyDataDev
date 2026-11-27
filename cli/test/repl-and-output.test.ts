import http from 'node:http';
import { PassThrough } from 'node:stream';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { ApiClient } from '../src/client/http.js';
import { formatCsv, formatTable, formatTabular } from '../src/output/format.js';
import { runRepl } from '../src/repl/index.js';

describe('output formatting', () => {
  it('formats CJK table, CSV escaping, and NDJSON cleanly', () => {
    const table = formatTable(['ID', '名称'], [[1, '订单库'], [2, '数仓']]);
    expect(table).toContain('订单库');
    expect(table.split('\n')).toHaveLength(4);

    const csv = formatCsv(['a', 'b'], [['hello, "world"', 'line1\nline2']]);
    expect(csv).toBe('a,b\n"hello, ""world""","line1\nline2"');

    const ndjson = formatTabular('ndjson', ['id', 'name'], [[1, 'a'], [2, 'b']]);
    expect(ndjson.split('\n')).toEqual(['{"id":1,"name":"a"}', '{"id":2,"name":"b"}']);
  });
});

describe('minimal interactive REPL', () => {
  let server: http.Server;
  let baseUrl: string;

  beforeAll(async () => {
    server = http.createServer((req, res) => {
      if (req.url === '/api/system/info') {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ product: 'MyDataDev', version: '0.10.2', apiVersion: 1, mode: 'desktop', authMode: 'DISABLED' }));
        return;
      }
      if (req.url === '/api/connections') {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify([
          { id: 1, name: 'local-h2', dbType: 'h2', environment: 'dev', jdbcUrl: 'jdbc:h2:mem:demo' }
        ]));
        return;
      }
      if (req.url === '/api/sql/query') {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({
          columns: [{ name: 'answer' }],
          rows: [[42]],
          affectedRows: 0,
          elapsedMs: 3,
          resultSet: true
        }));
        return;
      }
      if (req.url?.startsWith('/api/metadata/1/objects/detail')) {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({
          name: 'orders',
          type: 'TABLE',
          columns: [{ name: 'id', typeName: 'BIGINT', nullable: false, primaryKey: true }],
          indexes: [],
          primaryKeys: ['id']
        }));
        return;
      }
      res.writeHead(404).end();
    });
    await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
    const addr = server.address();
    baseUrl = `http://127.0.0.1:${typeof addr === 'object' && addr ? addr.port : 0}`;
  });

  afterAll(async () => {
    await new Promise<void>((resolve) => server.close(() => resolve()));
  });

  it('reuses single-shot handlers for conn use, bare SQL queries, and meta describe', async () => {
    const stdin = new PassThrough();
    const stdout = new PassThrough();
    const stderr = new PassThrough();
    const out: string[] = [];
    const err: string[] = [];
    stdout.on('data', (chunk) => out.push(chunk.toString('utf8')));
    stderr.on('data', (chunk) => err.push(chunk.toString('utf8')));

    const client = new ApiClient({ label: 'local', url: baseUrl, source: 'desktop' }, { stderr });
    const replPromise = runRepl({
      client,
      format: 'table',
      quiet: false,
      stdout,
      stderr,
      stdin
    });

    stdin.write('conn ls\n');
    stdin.write('conn use local-h2\n');
    stdin.write('select 42 as answer;\n');
    stdin.write('meta describe orders\n');
    stdin.write('exit\n');
    stdin.end();

    await replPromise;
    const output = out.join('');
    expect(output).toContain('MyDataDev 0.10.2');
    expect(output).toContain('local-h2');
    expect(output).toContain('已选择连接：local-h2');
    expect(output).toContain('42');
    expect(output).toContain('BIGINT');
    expect(err.join('')).not.toContain('✖');
  });
});
