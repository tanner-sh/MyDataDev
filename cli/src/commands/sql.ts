import { randomUUID } from 'node:crypto';
import { CliError, ExitCode } from '../errors.js';
import { formatTabular } from '../output/format.js';
import type { SqlResult, SqlScriptResult } from '../types.js';
import { readSqlInput, resolveConnection, type CommandContext, type ConnectionRef } from './context.js';

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
  delimiter?: string;
  allowLargeScript?: boolean;
}

export async function handleSqlQuery(ctx: CommandContext, connRef: ConnectionRef, options: SqlCommonOptions): Promise<void> {
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

export async function handleSqlExec(ctx: CommandContext, connRef: ConnectionRef, options: SqlExecOptions): Promise<void> {
  if (!options.file && (options.delimiter !== undefined || options.allowLargeScript)) {
    throw new CliError(ExitCode.USAGE_ERROR, 'SQL_FILE_REQUIRED', '--delimiter 和 --allow-large-script 需搭配 -f 使用。');
  }
  const sql = await readSqlInput({
    sql: options.sql,
    file: options.file,
    stdin: ctx.stdin,
    allowStdin: options.allowStdin
  });
  if (!options.file && /^(?:COMMIT|ROLLBACK)(?:\s+WORK)?\s*;?$/i.test(sql)) {
    throw new CliError(ExitCode.USAGE_ERROR, 'TRANSACTION_ID_REQUIRED',
      '请用 sql tx begin 开启事务，通过 sql tx exec 执行写入，再用 sql tx commit 或 rollback 指定事务 ID。');
  }
  const connection = await resolveConnection(ctx.client, connRef);
  const executionId = randomUUID();
  const request = {
    connectionId: connection.id,
    sql,
    maxRows: options.maxRows,
    executionId,
    schemaName: options.schema,
    unscopedMutationConfirmed: Boolean(options.allowUnboundedWrite)
  };
  const installSigint = options.installSigint !== false;
  if (options.file) {
    const result = await withCancellableExecution(ctx, executionId, installSigint, (signal) =>
      ctx.client.requestJson<SqlScriptResult>('/api/sql/execute-script', {
        method: 'POST',
        body: {
          ...request,
          delimiterMode: options.delimiter === undefined ? undefined : 'CUSTOM',
          delimiter: options.delimiter,
          largeScriptConfirmed: Boolean(options.allowLargeScript)
        },
        productionConfirmation: options.confirmProduction,
        signal
      })
    );
    renderSqlScriptResult(ctx, result, true);
    return;
  }
  const result = await withCancellableExecution(ctx, executionId, installSigint, (signal) =>
    ctx.client.requestJson<SqlResult>('/api/sql/execute', {
      method: 'POST',
      body: request,
      productionConfirmation: options.confirmProduction,
      signal
    })
  );

  renderSqlResult(ctx, result);
}

export async function handleSqlExplain(ctx: CommandContext, connRef: ConnectionRef, options: SqlCommonOptions): Promise<void> {
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
  if (result.truncated) ctx.stderr.write('⚠ SQL 查询结果已截断。\n');
  if (ctx.format === 'table' && !ctx.quiet) {
    ctx.stderr.write(`(${result.rows.length} 行, ${result.elapsedMs}ms)\n`);
  }
}

export function renderSqlScriptResult(ctx: CommandContext, script: SqlScriptResult, unwrapSingleResult = false): void {
  if (!ctx.quiet) {
    for (const notice of script.notices ?? []) ctx.stderr.write(notice + '\n');
    if (script.transactionMessage) ctx.stderr.write(script.transactionMessage + '\n');
  }
  if (script.status === 'SUCCESS' && unwrapSingleResult && script.results.length === 1) {
    renderSqlResult(ctx, script.results[0].result);
    return;
  }

  if (ctx.format === 'json') {
    ctx.stdout.write(JSON.stringify(script, null, 2) + '\n');
  } else if (ctx.format === 'ndjson') {
    for (const statement of script.results) ctx.stdout.write(JSON.stringify(statement) + '\n');
  } else if (ctx.format === 'csv') {
    const rows = script.results.map(({ index, status, errorMessage, result }) => [index, status, errorMessage, result]);
    ctx.stdout.write(formatTabular('csv', ['index', 'status', 'errorMessage', 'result'], rows) + '\n');
  } else {
    const succeeded = script.results.filter((statement) => statement.status === 'SUCCESS');
    for (const statement of succeeded) {
      if (script.results.length > 1) ctx.stdout.write(`语句 #${statement.index}\n`);
      renderSqlResult(ctx, statement.result);
    }
  }

  if (script.status !== 'SUCCESS') {
    const failed = script.results.find((statement) => statement.status === 'FAILED');
    const message = failed ? `语句 #${failed.index} 执行失败。 ${failed.errorMessage ?? ''}`.trimEnd() : `SQL 执行结束，状态为 ${script.status}。`;
    throw new CliError(ExitCode.EXECUTION_FAILED, 'SQL_SCRIPT_FAILED', message);
  }
}

async function withCancellableExecution<T>(
  ctx: CommandContext,
  executionId: string,
  installSigint: boolean,
  runner: (signal: AbortSignal) => Promise<T>
): Promise<T> {
  const controller = new AbortController();
  let cancellation: Promise<boolean> | undefined;
  const requestCancellation = () => ctx.client
    .requestJson<{ ok: boolean }>(`/api/sql/executions/${encodeURIComponent(executionId)}/cancel`, {
      method: 'POST',
      timeoutMs: 5_000
    })
    .then((response) => response.ok === true)
    .catch(() => false);
  const onSigint = () => {
    ctx.stderr.write('\n正在取消服务端 SQL 执行...\n');
    cancellation ??= requestCancellation().finally(() => controller.abort());
  };
  if (installSigint) process.once('SIGINT', onSigint);
  try {
    const result = await runner(controller.signal);
    if (!cancellation) return result;
  } catch (error) {
    if (!cancellation) {
      if (!(error instanceof CliError) || error.code !== 'REQUEST_TIMEOUT') throw error;
      const acknowledged = await (cancellation ??= requestCancellation());
      const outcome = acknowledged ? '服务端已接收取消请求。' : '服务端取消未确认。';
      throw new CliError(error.exitCode, 'SQL_EXECUTION_TIMEOUT',
        `${error.message} ${outcome} 已提交的操作会保留，重试前请核对执行结果。`,
        { executionId, cancellationAcknowledged: acknowledged }, { cause: error });
    }
  } finally {
    process.off('SIGINT', onSigint);
  }
  const acknowledged = await cancellation;
  const message = acknowledged
    ? '服务端已接收取消请求。已提交的操作会保留。'
    : '客户端已中断，服务端取消未确认。请核对执行结果。';
  throw new CliError(ExitCode.INTERRUPTED, 'INTERRUPTED', message);
}
