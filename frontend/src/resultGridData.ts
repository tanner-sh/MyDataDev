import type { ResultColumn } from './types';

export type ResultFilterOperator = 'contains' | 'notContains' | 'equals' | 'notEquals' | 'empty' | 'notEmpty';

export type ResultColumnFilter = {
  operator: ResultFilterOperator;
  value: string;
};

export type ResultColumnFilters = Record<string, ResultColumnFilter>;

const naturalCollator = new Intl.Collator('zh-CN', { numeric: true, sensitivity: 'base' });

export function compareResultValues(left: unknown, right: unknown): number {
  if (left == null && right == null) return 0;
  if (left == null) return -1;
  if (right == null) return 1;
  if (typeof left === 'number' && typeof right === 'number') return left - right;
  if (typeof left === 'boolean' && typeof right === 'boolean') return Number(left) - Number(right);
  return naturalCollator.compare(String(left), String(right));
}

export function matchesResultFilter(value: unknown, filter: ResultColumnFilter): boolean {
  const empty = value == null || value === '';
  if (filter.operator === 'empty') return empty;
  if (filter.operator === 'notEmpty') return !empty;

  const candidate = value == null ? '' : String(value).toLocaleLowerCase('zh-CN');
  const expected = filter.value.toLocaleLowerCase('zh-CN');
  return switchFilter(filter.operator, candidate, expected);
}

export function filterResultRows(rows: unknown[][], columns: ResultColumn[], filters: ResultColumnFilters): unknown[][] {
  const activeFilters = columns.flatMap((column, index) => {
    const filter = filters[column.key];
    return filter ? [{ index, filter }] : [];
  });
  if (activeFilters.length === 0) return rows;
  return rows.filter((row) => activeFilters.every(({ index, filter }) => matchesResultFilter(row[index], filter)));
}

function switchFilter(operator: ResultFilterOperator, candidate: string, expected: string): boolean {
  switch (operator) {
    case 'contains': return candidate.includes(expected);
    case 'notContains': return !candidate.includes(expected);
    case 'equals': return candidate === expected;
    case 'notEquals': return candidate !== expected;
    case 'empty':
    case 'notEmpty':
      return false;
  }
}
