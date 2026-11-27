import { CliError, ExitCode } from '../errors.js';
import { formatKeyValue } from '../output/format.js';
import type { SqlFileTask } from '../types.js';
import { readSqlInput, resolveConnection, type CommandContext } from './context.js';

const SCRIPT_TASKS = '/api/sql-file-executions';

export async function handleScriptSubmit(
  ctx: CommandContext,
  connRef: string,
  options: {
    sql?: string;
    file?: string;
    delimiter?: string;
    transactionMode?: 'autocommit' | 'script';
    endOfFile?: 'commit' | 'rollback';
    confirmProduction?: string;
    allowUnboundedWrite?: boolean;
  }
): Promise<void> {
  if (options.endOfFile && options.transactionMode !== 'script') {
    throw new CliError(ExitCode.USAGE_ERROR, 'SCRIPT_MODE_REQUIRED', '--end-of-file 需搭配 --transaction-mode script 使用。');
  }
  const sql = await readSqlInput({ sql: options.sql, file: options.file, stdin: ctx.stdin });
  const connection = await resolveConnection(ctx.client, connRef);
  const task = await ctx.client.requestJson<SqlFileTask>(`${SCRIPT_TASKS}/commands`, {
    method: 'POST',
    body: {
      connectionId: connection.id,
      sql,
      delimiterMode: options.delimiter === undefined ? undefined : 'CUSTOM',
      delimiter: options.delimiter,
      transactionMode: options.transactionMode?.toUpperCase(),
      endOfFileAction: options.endOfFile?.toUpperCase(),
      unscopedMutationConfirmed: Boolean(options.allowUnboundedWrite)
    },
    productionConfirmation: options.confirmProduction
  });
  renderTask(ctx, task);
  if (task.status === 'FAILED') {
    throw new CliError(ExitCode.EXECUTION_FAILED, 'SCRIPT_SUBMIT_FAILED', task.message || '后台脚本未能启动。');
  }
}

export async function handleScriptTask(ctx: CommandContext, action: 'status' | 'cancel', id: string): Promise<void> {
  if (!/^[1-9]\d*$/.test(id)) {
    throw new CliError(ExitCode.USAGE_ERROR, 'INVALID_SCRIPT_TASK_ID', '请传入有效的后台脚本任务 ID。');
  }
  const endpoint = `${SCRIPT_TASKS}/${id}`;
  const task = action === 'cancel'
    ? await ctx.client.requestJson<SqlFileTask>(`${endpoint}/cancel`, { method: 'POST' })
    : await ctx.client.requestJson<SqlFileTask>(endpoint);
  renderTask(ctx, task);
}

function renderTask(ctx: CommandContext, task: SqlFileTask): void {
  const fields: [string, unknown][] = [
    ['id', task.id],
    ['status', task.status],
    ['phase', task.phase],
    ['statementTotal', task.statementTotal],
    ['statementCurrent', task.statementCurrent],
    ['successCount', task.successCount],
    ['message', task.message]
  ];
  ctx.stdout.write(formatKeyValue(ctx.format, fields, { ...task }) + '\n');
}
