import { describe, expect, it } from 'vitest';
import {
  canCountTableRows,
  IDLE_TABLE_ROW_COUNT,
  tablePageSummary,
  tableRowCountLabel,
  type TableRowCountState
} from './tableRowCount';

describe('tableRowCountLabel', () => {
  it('describes each state', () => {
    expect(tableRowCountLabel(IDLE_TABLE_ROW_COUNT)).toBeUndefined();
    expect(tableRowCountLabel({ status: 'loading' })).toBe('正在统计总行数…');
    expect(tableRowCountLabel({ status: 'ready', total: 1234567, elapsedMs: 12 })).toBe('共 1,234,567 行');
    expect(tableRowCountLabel({ status: 'failed' })).toBe('总行数统计失败');
  });
});

describe('tablePageSummary', () => {
  it('keeps the page summary alone until a count exists', () => {
    expect(tablePageSummary(0, 100, IDLE_TABLE_ROW_COUNT)).toBe('第 1 页 · 本页 100 行');
  });

  it('appends the total once counted', () => {
    expect(tablePageSummary(2, 50, { status: 'ready', total: 4200, elapsedMs: 8 }))
      .toBe('第 3 页 · 本页 50 行 · 共 4,200 行');
  });
});

describe('canCountTableRows', () => {
  it('needs an open table that is not already busy', () => {
    expect(canCountTableRows(IDLE_TABLE_ROW_COUNT, true, false)).toBe(true);
    expect(canCountTableRows(IDLE_TABLE_ROW_COUNT, false, false)).toBe(false);
    expect(canCountTableRows(IDLE_TABLE_ROW_COUNT, true, true)).toBe(false);
  });

  it('does not start a second count while one is running', () => {
    const loading: TableRowCountState = { status: 'loading' };
    expect(canCountTableRows(loading, true, false)).toBe(false);
  });

  it('allows recounting after a finished or failed attempt', () => {
    expect(canCountTableRows({ status: 'ready', total: 1, elapsedMs: 1 }, true, false)).toBe(true);
    expect(canCountTableRows({ status: 'failed' }, true, false)).toBe(true);
  });
});
