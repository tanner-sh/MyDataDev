import { describe, expect, it } from 'vitest';
import {
  IDLE_SQL_TRANSACTION,
  isTransactionGone,
  transactionGoneNotice,
  transactionStateAfterError,
  isTransactionActive,
  transactionBadge,
  transactionExecutePath,
  transactionFinishPrompt,
  transactionLeaveWarning,
  transactionTooltip,
  type SqlTransaction,
  type SqlTransactionState,
  restoredTransactionNotice
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

describe('restoredTransactionNotice', () => {
  it('接回带未提交语句的事务时点明条数', () => {
    expect(restoredTransactionNotice(transaction({ statementCount: 3 }))).toContain('3 条未提交语句');
  });

  it('刚开还没执行的事务只说明超时时间', () => {
    const notice = restoredTransactionNotice(transaction({ statementCount: 0, idleTimeoutSeconds: 600 }));
    expect(notice).toContain('10 分钟');
    expect(notice).not.toContain('未提交语句');
  });

  it('超时时间不足一分钟也至少说 1 分钟', () => {
    expect(restoredTransactionNotice(transaction({ statementCount: 0, idleTimeoutSeconds: 20 }))).toContain('1 分钟');
  });

  describe('服务端已经结束的事务', () => {
    const open: SqlTransactionState = {
      transaction: {
        id: 't-1',
        connectionId: 1,
        startedAt: '2026-08-25T00:00:00Z',
        lastUsedAt: '2026-08-25T00:00:00Z',
        statementCount: 3,
        idleTimeoutSeconds: 600
      },
      pending: true
    };

    it('识别 TRANSACTION_NOT_FOUND', () => {
      expect(isTransactionGone('TRANSACTION_NOT_FOUND')).toBe(true);
      expect(isTransactionGone('PRODUCTION_CONFIRMATION_REQUIRED')).toBe(false);
      expect(isTransactionGone(undefined)).toBe(false);
    });

    it('事务不在了就退回自动提交，否则只解除 pending', () => {
      // 留着已失效的事务 id 会让之后每次执行都发到它上面，切换连接也被拦住，只能刷新页面。
      expect(transactionStateAfterError(open, 'TRANSACTION_NOT_FOUND')).toEqual(IDLE_SQL_TRANSACTION);
      // 语句本身出错时事务还开着，清掉就没人去提交或回滚它了。
      expect(transactionStateAfterError(open, 'SQL_ERROR')).toEqual({ ...open, pending: false });
      expect(transactionStateAfterError(open, undefined)).toEqual({ ...open, pending: false });
    });

    it('提示里说清楚改动已丢弃并且回到了自动提交', () => {
      expect(transactionGoneNotice(open)).toContain('3 条语句的改动已丢弃');
      expect(transactionGoneNotice(open)).toContain('自动提交');
      expect(transactionGoneNotice({ transaction: { ...open.transaction!, statementCount: 0 }, pending: false }))
        .not.toContain('丢弃');
    });
  });
});
