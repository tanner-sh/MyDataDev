import { describe, expect, it } from 'vitest';
import { compareResultValues, filterResultRows, isNumericColumnType, matchesResultFilter, MIN_RESULT_COLUMN_WIDTH, sortResultRows, suggestedResultColumnWidth, textUnits } from './resultGridData';

describe('result grid sorting', () => {
  it('sorts numbers, booleans, natural text and null values', () => {
    expect([10, 2, 30].sort(compareResultValues)).toEqual([2, 10, 30]);
    expect([true, false].sort(compareResultValues)).toEqual([false, true]);
    expect(['item10', 'Item2', 'item1'].sort(compareResultValues)).toEqual(['item1', 'Item2', 'item10']);
    expect(['value', null].sort(compareResultValues)).toEqual([null, 'value']);
    expect(compareResultValues(undefined, 'value')).toBeLessThan(0);
  });

  it('sorts complete result rows without mutating their original order', () => {
    const columns = [{ key: 'c1', label: 'id', typeName: 'INTEGER' }];
    const rows = [[10], [2], [30]];
    expect(sortResultRows(rows, columns, { key: 'c1', order: 'descend' })).toEqual([[30], [10], [2]]);
    expect(rows).toEqual([[10], [2], [30]]);
  });
});

describe('result grid filtering', () => {
  it('supports text and empty-value operators', () => {
    expect(matchesResultFilter('Oracle Database', { operator: 'contains', value: 'database' })).toBe(true);
    expect(matchesResultFilter('Oracle', { operator: 'notContains', value: 'mysql' })).toBe(true);
    expect(matchesResultFilter('READY', { operator: 'equals', value: 'ready' })).toBe(true);
    expect(matchesResultFilter('READY ', { operator: 'notEquals', value: 'ready' })).toBe(true);
    expect(matchesResultFilter(null, { operator: 'empty', value: '' })).toBe(true);
    expect(matchesResultFilter('', { operator: 'empty', value: '' })).toBe(true);
    expect(matchesResultFilter(0, { operator: 'notEmpty', value: '' })).toBe(true);
  });

  it('combines filters from multiple columns', () => {
    const columns = [
      { key: 'c1', label: 'name', typeName: 'VARCHAR' },
      { key: 'c2', label: 'status', typeName: 'VARCHAR' }
    ];
    const rows = [
      ['Alice', 'READY'],
      ['Bob', 'READY'],
      ['Alice', 'FAILED']
    ];

    expect(filterResultRows(rows, columns, {
      c1: { operator: 'equals', value: 'alice' },
      c2: { operator: 'equals', value: 'ready' }
    })).toEqual([['Alice', 'READY']]);
  });
});

describe('result grid column widths', () => {
  it('窄内容的列不再和宽内容的列一样宽', () => {
    const id = { key: 'c1', label: 'ID', typeName: 'INTEGER' };
    const orderNo = { key: 'c2', label: '订单号', typeName: 'VARCHAR' };

    // 改之前两者都被类型下限顶到 124/148，实际渲染出来几乎一样宽。
    expect(suggestedResultColumnWidth(id, 0, [[1], [200]]))
      .toBeLessThan(suggestedResultColumnWidth(orderNo, 0, [['SO2025000001']]));
  });

  it('汉字按两倍宽度计算', () => {
    const status = { key: 'c1', label: 'x', typeName: 'VARCHAR' };
    expect(suggestedResultColumnWidth(status, 0, [['待付款处理中']]))
      .toBeGreaterThan(suggestedResultColumnWidth(status, 0, [['abcdef']]));
    expect(textUnits('待付款')).toBe(6);
    expect(textUnits('abc')).toBe(3);
  });

  it('整列为空时靠类型下限兜底，超长内容裁到上限', () => {
    const empty = { key: 'c1', label: 'note', typeName: 'VARCHAR' };
    expect(suggestedResultColumnWidth(empty, 0, [[null], [null]])).toBeGreaterThanOrEqual(MIN_RESULT_COLUMN_WIDTH);
    expect(suggestedResultColumnWidth(empty, 0, [['x'.repeat(1_000)]])).toBe(320);
  });

  it('识别数值列，且不把 INTERVAL 当成 INT', () => {
    expect(isNumericColumnType('BIGINT')).toBe(true);
    expect(isNumericColumnType('DECIMAL(14,2)')).toBe(true);
    expect(isNumericColumnType('NUMBER')).toBe(true);
    expect(isNumericColumnType('int unsigned')).toBe(true);
    expect(isNumericColumnType('INTERVAL DAY TO SECOND')).toBe(false);
    expect(isNumericColumnType('VARCHAR')).toBe(false);
    expect(isNumericColumnType(undefined)).toBe(false);
  });
});
