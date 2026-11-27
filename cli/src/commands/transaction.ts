import { CliError, ExitCode } from '../errors.js';
import { formatKeyValue } from '../output/format.js';
import type { SqlScriptResult, SqlTransaction } from '../types.js';
import { readSqlInput, resolveConnection, type CommandContext } from './context.js';
import { renderSqlScriptResult } from './sql.js';

const TRANSACTIONS = '/api/sql/transactions';

export async function handleTransaction(
  ctx: CommandContext,
  action: 'begin' | 'status' | 'commit' | 'rollback',
  ref: string,
  options: { schema?: string; confirmProduction?: string } = {}
): Promise<void> {
  let transaction: SqlTransaction | undefined;
  if (action === 'begin' || action === 'status') {
    const connection = await resolveConnection(ctx.client, ref);
    if (action === 'begin') {
      transaction = await ctx.client.requestJson<SqlTransaction>(TRANSACTIONS, {
        method: 'POST',
        body: { connectionId: connection.id, schemaName: options.schema },
        productionConfirmation: options.confirmProduction
      });
    } else {
      const response = await ctx.client.requestJson<{ transaction?: SqlTransaction }>(
        `${TRANSACTIONS}/active?connectionId=${connection.id}`
      );
      transaction = response.transaction;
    }
  } else {
    transaction = await ctx.client.requestJson<SqlTransaction>(`${transactionPath(ref)}/${action}`, { method: 'POST' });
  }
  const details = transaction ?? { transaction: null };
  if (!transaction && ctx.format === 'table') {
    ctx.stdout.write('当前没有活动事务。\n');
    return;
  }
  ctx.stdout.write(formatKeyValue(ctx.format, Object.entries(details), { ...details }) + '\n');
}

export async function handleTransactionExec(
  ctx: CommandContext,
  id: string,
  options: { sql?: string; file?: string; maxRows?: number; allowUnboundedWrite?: boolean }
): Promise<void> {
  const endpoint = `${transactionPath(id)}/execute`;
  const sql = await readSqlInput({ sql: options.sql, file: options.file, stdin: ctx.stdin });
  const result = await ctx.client.requestJson<SqlScriptResult & { transaction: SqlTransaction }>(endpoint, {
    method: 'POST',
    body: {
      sql,
      maxRows: options.maxRows,
      unscopedMutationConfirmed: Boolean(options.allowUnboundedWrite)
    }
  });
  renderSqlScriptResult(ctx, result);
}

function transactionPath(id: string): string {
  if (!/^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(id)) {
    throw new CliError(ExitCode.USAGE_ERROR, 'INVALID_TRANSACTION_ID', '请传入 sql tx begin 返回的事务 ID。');
  }
  return `${TRANSACTIONS}/${id}`;
}
