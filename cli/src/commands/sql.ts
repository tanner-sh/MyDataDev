import { randomUUID } from 'node:crypto';
import { CliError, ExitCode } from '../errors.js';
import { formatTabular } from '../output/format.js';
import type { SqlResult } from '../types.js';
import { readSqlInput, resolveConnection, type CommandContext } from './context.js';

export interface SqlCommonOptions {
  sql?: string;
  file?: string;
  schema?: string;
  maxRows?: number;
  confirmProduction?: string;
  allowStdin?: boolean;
  installSigint?: boolean;
}

export interface SqlExecOptions extends SqlCommonOptions {
  allowUnboundedWrite?: boolean;
}

export async function handleSqlQuery(ctx: CommandContext, connRef: string, options: SqlCommonOptions): Promise<void> {
  const sql = await readSqlInput({
    sql: options.sql,
    file: options.file,
    stdin: ctx.stdin,
    allowStdin: options.allowStdin
  });
  const connection = await resolveConnection(ctx.client, connRef);
  const executionId = randomUUID();

  const result = await withCancellableExecution(ctx, executionId, options.installSigint !== false, (signal) =>
    ctx.client.requestJson<SqlResult>('/api/sql/query', {
      method: 'POST',
      body: {
        connectionId: connection.id,
        sql,
        maxRows: options.maxRows,
        executionId,
        schemaName: options.schema
      },
      productionConfirmation: options.confirmProduction,
      signal
    })
  );

  renderSqlResult(ctx, result);
}

export async function handleSqlExec(ctx: CommandContext, connRef: string, options: SqlExecOptions): Promise<void> {
  const sql = await readSqlInput({
    sql: options.sql,
    file: options.file,
    stdin: ctx.stdin,
    allowStdin: options.allowStdin
  });
  const connection = await resolveConnection(ctx.client, connRef);
  const executionId = randomUUID();

  const result = await withCancellableExecution(ctx, executionId, options.installSigint !== false, (signal) =>
    ctx.client.requestJson<SqlResult>('/api/sql/execute', {
      method: 'POST',
      body: {
        connectionId: connection.id,
        sql,
        maxRows: options.maxRows,
        executionId,
        schemaName: options.schema,
        unscopedMutationConfirmed: Boolean(options.allowUnboundedWrite)
      },
      productionConfirmation: options.confirmProduction,
      signal
    })
  );

  renderSqlResult(ctx, result);
}

export async function handleSqlExplain(ctx: CommandContext, connRef: string, options: SqlCommonOptions): Promise<void> {
  const sql = await readSqlInput({
    sql: options.sql,
    file: options.file,
    stdin: ctx.stdin,
    allowStdin: options.allowStdin
  });
  const connection = await resolveConnection(ctx.client, connRef);
  const result = await ctx.client.requestJson<SqlResult>('/api/sql/explain', {
    method: 'POST',
    body: {
      connectionId: connection.id,
      sql,
      schemaName: options.schema
    },
    productionConfirmation: options.confirmProduction
  });
  renderSqlResult(ctx, result);
}

export function renderSqlResult(ctx: CommandContext, result: SqlResult): void {
  if (ctx.format === 'json') {
    ctx.stdout.write(JSON.stringify(result, null, 2) + '\n');
    return;
  }
  if (!result.resultSet) {
    if (ctx.format === 'ndjson') {
      ctx.stdout.write(JSON.stringify({ affectedRows: result.affectedRows, elapsedMs: result.elapsedMs }) + '\n');
    } else if (ctx.format === 'csv') {
      ctx.stdout.write(`affectedRows,elapsedMs\n${result.affectedRows},${result.elapsedMs}\n`);
    } else {
      ctx.stdout.write(`✔ 影响 ${result.affectedRows} 行 (${result.elapsedMs}ms)\n`);
    }
    return;
  }

  const headers = result.columns.map((col) => col.label || col.name);
  ctx.stdout.write(formatTabular(ctx.format, headers, result.rows) + '\n');
  if (ctx.format === 'table' && !ctx.quiet) {
    const truncatedNote = result.truncated ? '，结果已截断' : '';
    ctx.stderr.write(`(${result.rows.length} 行, ${result.elapsedMs}ms${truncatedNote})\n`);
  }
}

async function withCancellableExecution<T>(
  ctx: CommandContext,
  executionId: string,
  installSigint: boolean,
  runner: (signal: AbortSignal) => Promise<T>
): Promise<T> {
  if (!installSigint) {
    return runner(new AbortController().signal);
  }
  const controller = new AbortController();
  let interrupted = false;
  const onSigint = () => {
    if (interrupted) return;
    interrupted = true;
    ctx.stderr.write('\n正在取消服务端 SQL 执行...\n');
    void ctx.client
      .requestJson(`/api/sql/executions/${encodeURIComponent(executionId)}/cancel`, {
        method: 'POST',
        timeoutMs: 5_000
      })
      .catch(() => undefined)
      .finally(() => controller.abort());
  };
  process.once('SIGINT', onSigint);
  try {
    return await runner(controller.signal);
  } catch (error) {
    if (interrupted) {
      throw new CliError(ExitCode.INTERRUPTED, 'INTERRUPTED', '已发送取消请求并中断执行。');
    }
    throw error;
  } finally {
    process.off('SIGINT', onSigint);
  }
}
