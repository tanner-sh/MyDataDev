import { describe, expect, it } from 'vitest';
import { compareResultValues, filterResultRows, matchesResultFilter } from './resultGridData';

describe('result grid sorting', () => {
  it('sorts numbers, booleans, natural text and null values', () => {
    expect([10, 2, 30].sort(compareResultValues)).toEqual([2, 10, 30]);
    expect([true, false].sort(compareResultValues)).toEqual([false, true]);
    expect(['item10', 'Item2', 'item1'].sort(compareResultValues)).toEqual(['item1', 'Item2', 'item10']);
    expect(['value', null].sort(compareResultValues)).toEqual([null, 'value']);
    expect(compareResultValues(undefined, 'value')).toBeLessThan(0);
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
