import { describe, expect, it } from 'vitest';
import {
  IDLE_SQL_TRANSACTION,
  isTransactionActive,
  transactionBadge,
  transactionExecutePath,
  transactionFinishPrompt,
  transactionLeaveWarning,
  transactionTooltip,
  type SqlTransaction,
  type SqlTransactionState
} from './sqlTransaction';

const transaction = (overrides: Partial<SqlTransaction> = {}): SqlTransaction => ({
  id: 'tx-1',
  connectionId: 1,
  startedAt: '2026-08-22T00:00:00Z',
  lastUsedAt: '2026-08-22T00:00:00Z',
  statementCount: 0,
  idleTimeoutSeconds: 600,
  ...overrides
});

const active = (overrides: Partial<SqlTransaction> = {}): SqlTransactionState =>
  ({ transaction: transaction(overrides), pending: false });

describe('isTransactionActive / transactionExecutePath', () => {
  it('routes execution to the transaction endpoint only while one is open', () => {
    expect(isTransactionActive(IDLE_SQL_TRANSACTION)).toBe(false);
    expect(transactionExecutePath(IDLE_SQL_TRANSACTION)).toBeNull();
    expect(isTransactionActive(active())).toBe(true);
    expect(transactionExecutePath(active())).toBe('/sql/transactions/tx-1/execute');
  });
});

describe('transactionBadge', () => {
  it('always says which mode the editor is in', () => {
    expect(transactionBadge(IDLE_SQL_TRANSACTION)).toBe('自动提交');
    expect(transactionBadge(active())).toBe('事务已开启');
    expect(transactionBadge(active({ statementCount: 3 }))).toBe('事务中 · 已执行 3 条');
  });
});

describe('transactionTooltip', () => {
  it('explains the autocommit trap and the transaction cost', () => {
    expect(transactionTooltip(IDLE_SQL_TRANSACTION)).toContain('不会回滚');
    expect(transactionTooltip(active())).toContain('独占一条数据库连接');
    expect(transactionTooltip(active())).toContain('10 分钟');
    expect(transactionTooltip(active({ idleTimeoutSeconds: 30 }))).toContain('1 分钟');
  });
});

describe('transactionFinishPrompt', () => {
  it('states the blast radius for both outcomes', () => {
    expect(transactionFinishPrompt(active({ statementCount: 4 }), true)).toContain('4 条语句将一次性生效');
    expect(transactionFinishPrompt(active({ statementCount: 4 }), false)).toContain('丢弃');
  });

  it('does not pretend an empty transaction changes anything', () => {
    expect(transactionFinishPrompt(active(), true)).toContain('不会产生任何变化');
    expect(transactionFinishPrompt(active(), false)).toContain('释放这条连接');
  });
});

describe('transactionLeaveWarning', () => {
  it('warns only while a transaction is open', () => {
    expect(transactionLeaveWarning(IDLE_SQL_TRANSACTION)).toBeNull();
    expect(transactionLeaveWarning(active({ statementCount: 2 }))).toContain('已执行 2 条');
  });
});
