import { describe, expect, it } from 'vitest';
import {
  buildDataSearchRequest,
  canRunDataSearch,
  describeHitRows,
  EMPTY_DATA_SEARCH_FORM,
  incompleteNotice,
  summarizeDataSearch,
  tableHitLabel,
  type DataSearchForm
} from './dataSearch';
import type { DataSearchResponse, DataSearchTableHit } from './types';

function form(overrides: Partial<DataSearchForm> = {}): DataSearchForm {
  return { ...EMPTY_DATA_SEARCH_FORM, connectionId: 1, keyword: '13800001111', ...overrides };
}

function response(overrides: Partial<DataSearchResponse> = {}): DataSearchResponse {
  return {
    keyword: '13800001111',
    scannedTables: 12,
    totalTables: 12,
    matchedTables: 2,
    complete: true,
    tables: [],
    warnings: [],
    ...overrides
  };
}

describe('canRunDataSearch', () => {
  it('要求连接与非空关键字', () => {
    expect(canRunDataSearch(form())).toBe(true);
    expect(canRunDataSearch(form({ connectionId: undefined }))).toBe(false);
    expect(canRunDataSearch(form({ keyword: '   ' }))).toBe(false);
  });
});

describe('buildDataSearchRequest', () => {
  it('去掉关键字两端空白，空 Schema 发 undefined', () => {
    const request = buildDataSearchRequest(form({ keyword: '  abc  ', schemaName: '  ' }));
    expect(request.keyword).toBe('abc');
    expect(request.schemaName).toBeUndefined();
  });

  it('带上三个上限，让服务端知道这次要扫多大', () => {
    const request = buildDataSearchRequest(form({ maxTables: 50, rowsPerTable: 10, budgetSeconds: 5 }));
    expect(request).toMatchObject({ maxTables: 50, rowsPerTable: 10, budgetSeconds: 5 });
  });
});

describe('summarizeDataSearch', () => {
  it('扫完了才说「全部」', () => {
    expect(summarizeDataSearch(response())).toBe('扫描了全部 12 张表，在 2 张表里找到「13800001111」。');
  });

  /** 「在 3 张表里找到」听起来像全库的结论；没扫完时必须先说清楚范围。 */
  it('没扫完时把范围写在前面并标出未扫完', () => {
    expect(summarizeDataSearch(response({ complete: false, scannedTables: 40 })))
      .toBe('已扫描 40 张表（未扫完），在 2 张表里找到「13800001111」。');
  });

  it('一个都没找到时不假装扫过了全部', () => {
    expect(summarizeDataSearch(response({ complete: false, matchedTables: 0, scannedTables: 3 })))
      .toContain('未扫完');
  });
});

describe('incompleteNotice', () => {
  it('扫完了不提示', () => {
    expect(incompleteNotice(response())).toBe('');
  });

  it('带上服务端给的停止原因，并说明这不能当成全库结论', () => {
    const notice = incompleteNotice(response({ complete: false, stopReason: '达到 20 秒的时间上限' }));
    expect(notice).toContain('达到 20 秒的时间上限');
    expect(notice).toContain('不能当成全库的结论');
  });

  it('没有原因时也照样提示未扫完', () => {
    expect(incompleteNotice(response({ complete: false }))).toContain('还有表没有扫到');
  });
});

describe('表命中', () => {
  const hit: DataSearchTableHit = {
    schemaName: 'shop', tableName: 'orders', matchedRows: 5, truncated: false, columns: [], sql: 'SELECT 1'
  };

  it('带 Schema 时限定显示', () => {
    expect(tableHitLabel(hit)).toBe('shop.orders');
    expect(tableHitLabel({ ...hit, schemaName: undefined })).toBe('orders');
  });

  /** 样本之外还有更多时报一个准确数字，就是在撒谎。 */
  it('样本被截断时写成「至少」', () => {
    expect(describeHitRows(hit)).toBe('5 行');
    expect(describeHitRows({ ...hit, truncated: true })).toBe('至少 5 行');
  });
});
