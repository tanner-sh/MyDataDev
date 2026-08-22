/**
 * 手动事务。
 *
 * 脚本执行一律是逐条 autocommit、失败即停，前面已提交的不会回滚 —— 想在生产上「先看效果
 * 再决定提交」是做不到的。手动事务把一个标签页里的多次执行绑到同一条连接、同一个事务上。
 *
 * 代价必须让用户看见：一个开着的事务独占远程连接池里的一条连接（每连接上限个位数），
 * 空闲超时会被服务端自动回滚。界面上因此要一直显示事务状态和已执行条数。
 */

export type SqlTransaction = {
  id: string;
  connectionId: number;
  schemaName?: string | null;
  startedAt: string;
  lastUsedAt: string;
  statementCount: number;
  idleTimeoutSeconds: number;
};

export type SqlTransactionState = {
  transaction: SqlTransaction | null;
  /** 开启/提交/回滚进行中 */
  pending: boolean;
};

export const IDLE_SQL_TRANSACTION: SqlTransactionState = { transaction: null, pending: false };

export function isTransactionActive(state: SqlTransactionState): boolean {
  return state.transaction !== null;
}

/** 事务里执行走独立端点，其余走原来的脚本执行。 */
export function transactionExecutePath(state: SqlTransactionState): string | null {
  return state.transaction ? `/sql/transactions/${state.transaction.id}/execute` : null;
}

export function transactionBadge(state: SqlTransactionState): string {
  const transaction = state.transaction;
  if (!transaction) return '自动提交';
  if (transaction.statementCount === 0) return '事务已开启';
  return `事务中 · 已执行 ${transaction.statementCount} 条`;
}

export function transactionTooltip(state: SqlTransactionState): string {
  const transaction = state.transaction;
  if (!transaction) {
    return '当前为自动提交：每条语句立即生效，失败时前面已成功的语句不会回滚。开启手动事务可先看效果再决定。';
  }
  const minutes = Math.max(1, Math.round(transaction.idleTimeoutSeconds / 60));
  return `手动事务进行中，独占一条数据库连接。空闲超过 ${minutes} 分钟会被服务端自动回滚。`;
}

/** 提交/回滚前的确认文案，写清楚影响范围。 */
export function transactionFinishPrompt(state: SqlTransactionState, commit: boolean): string {
  const count = state.transaction?.statementCount ?? 0;
  if (commit) {
    return count === 0
      ? '当前事务还没有执行任何语句，提交不会产生任何变化。'
      : `提交后，本事务中的 ${count} 条语句将一次性生效，无法撤销。`;
  }
  return count === 0
    ? '当前事务还没有执行任何语句，回滚只会释放这条连接。'
    : `回滚将丢弃本事务中 ${count} 条语句的全部改动。`;
}

/** 离开工作台前必须提醒：忘了处理会一直占着连接直到超时。 */
export function transactionLeaveWarning(state: SqlTransactionState): string | null {
  if (!state.transaction) return null;
  return `当前有一个进行中的手动事务（已执行 ${state.transaction.statementCount} 条），离开前请提交或回滚。`;
}
