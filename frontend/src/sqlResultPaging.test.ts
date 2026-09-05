import { describe, expect, it } from 'vitest';
import {
  activeSqlSort,
  currentSqlPage,
  firstSqlPage,
  nextSqlPage,
  previousSqlPage,
  resizedSqlPage,
  sortedSqlPage,
  sqlResultRangeLabel
} from './sqlResultPaging';
import type { SqlPageInfo } from './types';

const page = (patch: Partial<SqlPageInfo> = {}): SqlPageInfo => ({
  connectionId: 1,
  schemaName: 'archive',
  offset: 0,
  requestedPageSize: 500,
  effectivePageSize: 500,
  hasMore: true,
  previousOffsets: [],
  ...patch
});

describe('sql result paging', () => {
  it('formats unknown and exact ranges', () => {
    expect(sqlResultRangeLabel(page(), 500)).toBe('1-500 / 501+');
    expect(sqlResultRangeLabel(page({ offset: 500, hasMore: false }), 230)).toBe('501-730 / 730');
    expect(sqlResultRangeLabel(page({ hasMore: false }), 0)).toBe('0 / 0');
  });

  it('tracks offsets for forward and backward navigation', () => {
    const second = nextSqlPage(page(), 400);
    expect(second).toEqual({ offset: 400, pageSize: 500, previousOffsets: [0], sort: null });
    expect(previousSqlPage(page({ offset: 400, previousOffsets: [0] })))
      .toEqual({ offset: 0, pageSize: 500, previousOffsets: [], sort: null });
  });

  it('resets history for first page and page-size changes', () => {
    expect(firstSqlPage(page({ offset: 500, previousOffsets: [0] })))
      .toEqual({ offset: 0, pageSize: 500, previousOffsets: [], sort: null });
    expect(resizedSqlPage(1000)).toEqual({ offset: 0, pageSize: 1000, previousOffsets: [], sort: null });
  });
});

describe('currentSqlPage', () => {
  it('原地重查：偏移与翻页历史都不变', () => {
    // 提交就地编辑后要重查，但不能把用户弹回第一页。
    const info = page({ offset: 1_000, requestedPageSize: 200, previousOffsets: [0, 500] });
    expect(currentSqlPage(info)).toEqual({ offset: 1_000, pageSize: 200, previousOffsets: [0, 500], sort: null });
  });

  it('没有翻页历史时给出空数组而不是 undefined', () => {
    const info = page({ offset: 0, previousOffsets: undefined });
    expect(currentSqlPage(info).previousOffsets).toEqual([]);
  });
});

describe('服务端排序', () => {
  const sorted = page({ offset: 500, previousOffsets: [0], sortColumn: 'AMT', sortDirection: 'DESC' });

  it('读出当前生效的排序，方向缺省按升序', () => {
    expect(activeSqlSort(sorted)).toEqual({ column: 'AMT', direction: 'DESC' });
    expect(activeSqlSort(page({ sortColumn: 'AMT' }))).toEqual({ column: 'AMT', direction: 'ASC' });
    expect(activeSqlSort(page())).toBeNull();
  });

  /** 排序是下推进 SQL 的：翻页时丢了它，顺序就换了，而用户只是点了「下一批」。 */
  it('翻页、回首页、改单页行数都把排序带上', () => {
    expect(nextSqlPage(sorted, 500)?.sort).toEqual({ column: 'AMT', direction: 'DESC' });
    expect(previousSqlPage(sorted)?.sort).toEqual({ column: 'AMT', direction: 'DESC' });
    expect(firstSqlPage(sorted).sort).toEqual({ column: 'AMT', direction: 'DESC' });
    expect(currentSqlPage(sorted).sort).toEqual({ column: 'AMT', direction: 'DESC' });
    expect(resizedSqlPage(200, sorted).sort).toEqual({ column: 'AMT', direction: 'DESC' });
  });

  /** 换了顺序，第 3 页就不再是原来那批行 —— 停在原偏移等于把用户丢在结果集中间。 */
  it('改排序回到第一批，取消排序也一样', () => {
    expect(sortedSqlPage(sorted, 'ORDER_DATE', 'ASC'))
      .toEqual({ offset: 0, pageSize: 500, previousOffsets: [], sort: { column: 'ORDER_DATE', direction: 'ASC' } });
    expect(sortedSqlPage(sorted, null, 'ASC').sort).toBeNull();
  });
});
