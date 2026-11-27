import { CliError, ExitCode } from '../errors.js';
import { formatKeyValue, formatTabular } from '../output/format.js';
import type { Connection, ConnectionTestResponse, DatabaseServerInfo } from '../types.js';
import { resolveConnection, type CommandContext } from './context.js';

export async function handleConnLs(ctx: CommandContext): Promise<void> {
  const connections = await ctx.client.requestJson<Connection[]>('/api/connections');
  const rows = connections.map((c) => [
    c.id,
    c.name,
    c.dbType,
    c.environment,
    c.readonly ? '只读' : '读写',
    c.jdbcUrl
  ]);
  ctx.stdout.write(
    formatTabular(ctx.format, ['ID', '名称', '类型', '环境', '模式', 'JDBC URL'], rows, connections) + '\n'
  );
}

export async function handleConnInfo(ctx: CommandContext, connRef: string): Promise<void> {
  const connection = await resolveConnection(ctx.client, connRef);
  let serverInfo: DatabaseServerInfo | null = null;
  try {
    serverInfo = await ctx.client.requestJson<DatabaseServerInfo>(`/api/connections/${connection.id}/server-info`);
  } catch {
    // 无 server-info 权限或远端暂不可达时仍展示基本连接信息
  }

  const entries: Array<[string, unknown]> = [
    ['ID', connection.id],
    ['名称', connection.name],
    ['类型', connection.dbType],
    ['环境', connection.environment],
    ['只读', connection.readonly ? '是' : '否'],
    ['JDBC URL', connection.jdbcUrl]
  ];
  if (serverInfo) {
    if (serverInfo.productName || serverInfo.productVersion) {
      entries.push(['服务端', `${serverInfo.productName ?? ''} ${serverInfo.productVersion ?? ''}`.trim()]);
    }
    if (serverInfo.databaseName) entries.push(['数据库', serverInfo.databaseName]);
    if (serverInfo.currentSchema) entries.push(['当前 Schema', serverInfo.currentSchema]);
    if (serverInfo.currentUser) entries.push(['数据库用户', serverInfo.currentUser]);
  }
  ctx.stdout.write(formatKeyValue(ctx.format, entries, { connection, serverInfo }) + '\n');
}

export async function handleConnTest(ctx: CommandContext, connRef: string): Promise<void> {
  const connection = await resolveConnection(ctx.client, connRef);
  const result = await ctx.client.requestJson<ConnectionTestResponse>(`/api/connections/${connection.id}/test`, {
    method: 'POST',
    body: null
  });

  if (ctx.format === 'json' || ctx.format === 'ndjson') {
    ctx.stdout.write(JSON.stringify({ connection: { id: connection.id, name: connection.name }, ...result }, null, ctx.format === 'json' ? 2 : 0) + '\n');
  } else {
    const icon = result.ok ? '✔' : '✖';
    ctx.stdout.write(`${icon} ${connection.name}：${result.message || (result.ok ? '连接成功' : '连接失败')}\n`);
    if (result.steps && result.steps.length > 0) {
      for (const step of result.steps) {
        const mark = step.ok === false ? '✖' : '·';
        const label = step.label || step.name || 'step';
        const detail = step.detail || step.message || '';
        const timing = typeof step.elapsedMs === 'number' ? ` (${step.elapsedMs}ms)` : '';
        ctx.stdout.write(`  ${mark} ${label}${timing}${detail ? `: ${detail}` : ''}\n`);
      }
    }
  }

  // HTTP 200 但响应体 ok:false 时仍必须返回非零退出码
  if (!result.ok) {
    throw new CliError(
      ExitCode.EXECUTION_FAILED,
      'CONNECTION_TEST_FAILED',
      result.message || '连接测试失败。',
      { steps: result.steps }
    );
  }
}
