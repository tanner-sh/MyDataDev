import { describe, expect, it } from 'vitest';
import { currentSqlPage, firstSqlPage, nextSqlPage, previousSqlPage, resizedSqlPage, sqlResultRangeLabel } from './sqlResultPaging';
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
    expect(second).toEqual({ offset: 400, pageSize: 500, previousOffsets: [0] });
    expect(previousSqlPage(page({ offset: 400, previousOffsets: [0] }))).toEqual({ offset: 0, pageSize: 500, previousOffsets: [] });
  });

  it('resets history for first page and page-size changes', () => {
    expect(firstSqlPage(page({ offset: 500, previousOffsets: [0] }))).toEqual({ offset: 0, pageSize: 500, previousOffsets: [] });
    expect(resizedSqlPage(1000)).toEqual({ offset: 0, pageSize: 1000, previousOffsets: [] });
  });
});

describe('currentSqlPage', () => {
  it('原地重查：偏移与翻页历史都不变', () => {
    // 提交就地编辑后要重查，但不能把用户弹回第一页。
    const info = page({ offset: 1_000, requestedPageSize: 200, previousOffsets: [0, 500] });
    expect(currentSqlPage(info)).toEqual({ offset: 1_000, pageSize: 200, previousOffsets: [0, 500] });
  });

  it('没有翻页历史时给出空数组而不是 undefined', () => {
    const info = page({ offset: 0, previousOffsets: undefined });
    expect(currentSqlPage(info).previousOffsets).toEqual([]);
  });
});
