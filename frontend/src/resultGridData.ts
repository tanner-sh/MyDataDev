import type { ResultColumn } from './types';

export type ResultFilterOperator = 'contains' | 'notContains' | 'equals' | 'notEquals' | 'empty' | 'notEmpty';

export type ResultColumnFilter = {
  operator: ResultFilterOperator;
  value: string;
};

export type ResultColumnFilters = Record<string, ResultColumnFilter>;
export type ResultSortState = { key: string; order: 'ascend' | 'descend' };

export const MIN_RESULT_COLUMN_WIDTH = 88;
export const MAX_RESULT_COLUMN_WIDTH = 520;

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

export function sortResultRows(rows: unknown[][], columns: ResultColumn[], sort?: ResultSortState): unknown[][] {
  if (!sort) return rows;
  const columnIndex = columns.findIndex((column) => column.key === sort.key);
  if (columnIndex < 0) return rows;
  const direction = sort.order === 'ascend' ? 1 : -1;
  return rows.map((row, index) => ({ row, index }))
    .sort((left, right) => compareResultValues(left.row[columnIndex], right.row[columnIndex]) * direction || left.index - right.index)
    .map(({ row }) => row);
}

export function suggestedResultColumnWidth(column: ResultColumn, columnIndex: number, rows: unknown[][]): number {
  const typeName = column.typeName.toLocaleUpperCase('en-US');
  const typeWidth = /BOOL|BIT/.test(typeName) ? 96
    : /DATE|TIME/.test(typeName) ? 168
      : /INT|NUMBER|DECIMAL|FLOAT|DOUBLE|REAL/.test(typeName) ? 124
        : 148;
  const longestValue = rows.slice(0, 30).reduce((longest, row) => {
    const value = row[columnIndex];
    return Math.max(longest, value == null ? 4 : String(value).length);
  }, Math.max(column.label.length, column.typeName.length));
  const contentWidth = longestValue * 8 + 44;
  return Math.max(MIN_RESULT_COLUMN_WIDTH, Math.min(320, Math.max(typeWidth, contentWidth)));
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
