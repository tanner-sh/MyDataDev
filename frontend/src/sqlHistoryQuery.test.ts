import { describe, expect, it } from 'vitest';
import {
  hasMoreSqlHistory,
  historyLoadedSummary,
  INITIAL_SQL_HISTORY_QUERY,
  isSqlHistoryAtLimit,
  nextSqlHistoryLimit,
  SQL_HISTORY_MAX_LIMIT,
  SQL_HISTORY_PAGE_SIZE,
  sqlHistoryRequestParams,
  historyStatsSummary
} from './sqlHistoryQuery';

describe('hasMoreSqlHistory', () => {
  it('only claims more when the page came back full', () => {
    expect(hasMoreSqlHistory(50, 50)).toBe(true);
    expect(hasMoreSqlHistory(49, 50)).toBe(false);
    expect(hasMoreSqlHistory(0, 50)).toBe(false);
  });

  it('stops claiming more once the backend cap is reached', () => {
    expect(hasMoreSqlHistory(SQL_HISTORY_MAX_LIMIT, SQL_HISTORY_MAX_LIMIT)).toBe(false);
  });
});

describe('nextSqlHistoryLimit', () => {
  it('grows by one page and never past the backend cap', () => {
    expect(nextSqlHistoryLimit(SQL_HISTORY_PAGE_SIZE)).toBe(SQL_HISTORY_PAGE_SIZE * 2);
    expect(nextSqlHistoryLimit(SQL_HISTORY_MAX_LIMIT)).toBe(SQL_HISTORY_MAX_LIMIT);
    expect(nextSqlHistoryLimit(SQL_HISTORY_MAX_LIMIT - 10)).toBe(SQL_HISTORY_MAX_LIMIT);
  });
});

describe('isSqlHistoryAtLimit', () => {
  it('is true only when a full page came back at the cap', () => {
    expect(isSqlHistoryAtLimit(SQL_HISTORY_MAX_LIMIT, SQL_HISTORY_MAX_LIMIT)).toBe(true);
    expect(isSqlHistoryAtLimit(120, SQL_HISTORY_MAX_LIMIT)).toBe(false);
    expect(isSqlHistoryAtLimit(50, 50)).toBe(false);
  });
});

describe('historyLoadedSummary', () => {
  it('never implies more records than there are', () => {
    expect(historyLoadedSummary(12, false, false)).toBe('已加载全部 12 条');
    expect(historyLoadedSummary(50, true, false)).toBe('已加载 50 条，还有更早的记录');
    expect(historyLoadedSummary(200, false, true)).toContain('单次上限');
  });
});

describe('sqlHistoryRequestParams', () => {
  it('omits a blank keyword', () => {
    expect(sqlHistoryRequestParams(7, INITIAL_SQL_HISTORY_QUERY)).toBe('connectionId=7&limit=50&scope=mine');
    expect(sqlHistoryRequestParams(7, { keyword: '   ', limit: 50 })).toBe('connectionId=7&limit=50&scope=mine');
  });

  it('trims and encodes the keyword', () => {
    expect(sqlHistoryRequestParams(7, { keyword: '  select * ', limit: 50 }))
      .toBe('connectionId=7&limit=50&keyword=select+*&scope=mine');
  });

  it('clamps the limit to what the backend accepts', () => {
    expect(sqlHistoryRequestParams(7, { keyword: '', limit: 10_000 }))
      .toBe(`connectionId=7&limit=${SQL_HISTORY_MAX_LIMIT}&scope=mine`);
    expect(sqlHistoryRequestParams(7, { keyword: '', limit: 0 })).toBe('connectionId=7&limit=1&scope=mine');
  });

  it('requests all visible history when explicitly selected', () => {
    expect(sqlHistoryRequestParams(7, { keyword: '', limit: 50, scope: 'all' }))
      .toBe('connectionId=7&limit=50&scope=all');
  });
});

describe('historyStatsSummary', () => {
  /** 失败率而不是失败数：20 次里失败 3 次和 2000 次里失败 3 次是两回事。 */
  it('给出失败率与耗时', () => {
    expect(historyStatsSummary({ days: 7, summary: { total: 20, failed: 3, averageMs: 45, slowestMs: 900 } }))
      .toBe('最近 7 天执行 20 次，失败 3 次（15%），平均 45ms，最慢 900ms。');
  });

  /** 一次都没执行时显示 0% 会让人以为「一切正常」。 */
  it('没有记录时直说没有记录', () => {
    expect(historyStatsSummary({ days: 30, summary: { total: 0, failed: 0, averageMs: 0, slowestMs: 0 } }))
      .toBe('最近 30 天没有执行记录。');
  });
});
