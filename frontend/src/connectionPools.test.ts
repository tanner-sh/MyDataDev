import { describe, expect, it } from 'vitest';
import { formatIdleFor, poolCloseWarning, poolPressure, poolWarnings } from './connectionPools';
import type { ConnectionPoolStatus } from './types';

const pool = (overrides: Partial<ConnectionPoolStatus> = {}): ConnectionPoolStatus => ({
  connectionId: 1,
  connectionName: '测试库',
  total: 1,
  active: 0,
  idle: 1,
  waiting: 0,
  maxPoolSize: 3,
  pendingBorrows: 0,
  idleMillis: 0,
  tunnelAlive: null,
  ...overrides
});

describe('poolPressure', () => {
  it('名额充足时只报数', () => {
    expect(poolPressure({ pools: [pool()], capacity: 20 })).toMatchObject({ used: 1, free: 19, level: 'ok' });
  });

  /** 等到 0 才提示已经晚了：那时新连接已经建不起来。 */
  it('剩余不到两成时提前提示', () => {
    expect(poolPressure({ pools: Array.from({ length: 17 }, () => pool()), capacity: 20 }).level).toBe('tight');
  });

  it('用满时说清楚后果', () => {
    const full = poolPressure({ pools: Array.from({ length: 20 }, () => pool()), capacity: 20 });
    expect(full).toMatchObject({ free: 0, level: 'full' });
    expect(full.hint).toContain('淘汰');
  });
});

describe('poolWarnings', () => {
  it('正常的池不给任何提示', () => {
    expect(poolWarnings(pool())).toEqual([]);
    // 直连没有隧道，不该被说成「隧道已断」。
    expect(poolWarnings(pool({ tunnelAlive: null }))).toEqual([]);
  });

  it('排队与隧道断开各说各的', () => {
    expect(poolWarnings(pool({ waiting: 2 }))[0]).toContain('排队');
    expect(poolWarnings(pool({ tunnelAlive: false }))[0]).toContain('隧道');
    expect(poolWarnings(pool({ waiting: 1, tunnelAlive: false }))).toHaveLength(2);
  });
});

describe('formatIdleFor', () => {
  it('按量级换单位', () => {
    expect(formatIdleFor(5_000)).toBe('刚刚用过');
    expect(formatIdleFor(180_000)).toBe('闲置 3 分钟');
    expect(formatIdleFor(3 * 3_600_000)).toBe('闲置 3 小时');
    expect(formatIdleFor(50 * 3_600_000)).toBe('闲置 2 天');
  });
});

describe('poolCloseWarning', () => {
  it('空闲的池关掉不影响谁', () => {
    expect(poolCloseWarning(pool())).toBeNull();
  });

  it('有人在用时说清楚会中断什么', () => {
    expect(poolCloseWarning(pool({ active: 2 }))).toContain('2 条');
    expect(poolCloseWarning(pool({ pendingBorrows: 1 }))).toContain('1 条');
  });
});
