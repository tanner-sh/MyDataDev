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

/**
 * 数值类型的列名，用于右对齐。
 *
 * <p>只认类型名开头的那个词，且要求词边界：{@code INTERVAL} 不能被 {@code INT} 匹配上。
 * BIGINT 与 DECIMAL 的值是以字符串传过来的（后端为了保精度），照样算数值列。</p>
 */
const NUMERIC_COLUMN_TYPE =
  /^\s*(?:TINYINT|SMALLINT|MEDIUMINT|BIGINT|INTEGER|INT|DECIMAL|NUMERIC|NUMBER|REAL|FLOAT|DOUBLE|MONEY|SMALLMONEY|BIGSERIAL|SMALLSERIAL|SERIAL)\b/i;

export function isNumericColumnType(typeName: string | undefined): boolean {
  return Boolean(typeName) && NUMERIC_COLUMN_TYPE.test(typeName as string);
}

/**
 * 文本的宽度单位：CJK 与全角标点按 2 算，其余按 1。
 *
 * 之前按 `length` 估宽，于是「状态」这种两个汉字的列被算成 2 个字符、宽度被类型下限兜住，
 * 而 12 个字符的订单号也拿到差不多的宽度 —— 结果是窄内容和宽内容的列一样宽。
 */
export function textUnits(value: string): number {
  let units = 0;
  for (const character of value) {
    const code = character.codePointAt(0) ?? 0;
    units += code > 0x2e80 && code < 0xff61 || code >= 0xffe0 && code <= 0xffe6 ? 2 : 1;
  }
  return units;
}

/**
 * 按内容估一个列宽。
 *
 * <p>类型下限只是「整列都是 NULL 时别缩成一条缝」的兜底，真正决定宽度的是内容 ——
 * 一位数的 ID 不该和 12 位的订单号一样宽。</p>
 */
export function suggestedColumnWidth(label: string, typeName: string, values: unknown[]): number {
  const type = (typeName || '').toLocaleUpperCase('en-US');
  const typeFloor = /BOOL|BIT/.test(type) ? 80
    : /DATE|TIME/.test(type) ? 140
      : isNumericColumnType(type) ? 92
        : 96;
  const units = values.slice(0, 30).reduce(
    (longest: number, value) => Math.max(longest, value == null ? 4 : textUnits(String(value))),
    textUnits(label)
  );
  return Math.max(MIN_RESULT_COLUMN_WIDTH, Math.min(320, Math.max(typeFloor, units * 7 + 26)));
}

export function suggestedResultColumnWidth(column: ResultColumn, columnIndex: number, rows: unknown[][]): number {
  return suggestedColumnWidth(column.label, column.typeName, rows.map((row) => row[columnIndex]));
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
