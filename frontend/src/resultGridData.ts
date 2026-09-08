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
export function textUnits(value: string, limit = Infinity): number {
  let units = 0;
  for (const character of value) {
    const code = character.codePointAt(0) ?? 0;
    units += code > 0x2e80 && code < 0xff61 || code >= 0xffe0 && code <= 0xffe6 ? 2 : 1;
    if (units >= limit) break;
  }
  return units;
}

/**
 * 每个宽度单位按多少像素算。
 *
 * <p>列名比值给得宽一格：库里的列名基本都是大写英文（`CUSTOMER_NO`、`ORDER_NO`），
 * 而大写字母比数字宽，按同一个系数算就会差出最后一两个字符 —— 于是在一张右边还空着
 * 半屏的表里，列名被截成「CUSTOMER…」。列名截断比值截断更糟：它是读懂整列的钥匙，
 * 而且列名很短，给宽一点的代价有上限。值仍按窄的算，长文本本来就该省略。</p>
 */
const VALUE_UNIT_WIDTH = 7;
const LABEL_UNIT_WIDTH = 8;

/**
 * 按内容估一个列宽。
 *
 * <p>类型下限只是「整列都是 NULL 时别缩成一条缝」的兜底，真正决定宽度的是内容 ——
 * 一位数的 ID 不该和 12 位的订单号一样宽。</p>
 */
export function suggestedColumnWidth(label: string, typeName: string, values: unknown[], headerControlsWidth = 0): number {
  const type = (typeName || '').toLocaleUpperCase('en-US');
  const typeFloor = /BOOL|BIT/.test(type) ? 80
    : /DATE|TIME/.test(type) ? 140
      : isNumericColumnType(type) ? 92
        : 96;
  const valueUnits = values.slice(0, 30).reduce(
    (longest: number, value) => Math.max(longest, value == null ? 4 : textUnits(String(value), Math.ceil((320 - 26) / VALUE_UNIT_WIDTH))),
    0
  );
  // 表头里的排序与筛选按钮占的是列宽，不是额外的地方 —— 不把它们算进去，「CUSTOMER」这种
  // 长度普通的列名会在一张还空着半屏的表里被截成「CUSTOM…」。
  const width = Math.max(
    valueUnits * VALUE_UNIT_WIDTH + 26,
    textUnits(label, Math.ceil((320 - 26) / LABEL_UNIT_WIDTH)) * LABEL_UNIT_WIDTH + 26 + headerControlsWidth
  );
  return Math.max(MIN_RESULT_COLUMN_WIDTH, Math.min(320, Math.max(typeFloor, width)));
}

/** 结果表头里排序 + 筛选两个按钮连同分隔线占掉的宽度。 */
const RESULT_HEADER_CONTROLS_WIDTH = 46;

export function suggestedResultColumnWidth(column: ResultColumn, columnIndex: number, rows: unknown[][]): number {
  return suggestedColumnWidth(column.label, column.typeName, rows.slice(0, 30).map((row) => row[columnIndex]), RESULT_HEADER_CONTROLS_WIDTH);
}

/** 竖向滚动条留出的余量：表头没有滚动条，正文有，宽度顶满会让两边差出这么多。 */
const FILLER_SCROLLBAR_RESERVE = 18;
/** 比这更窄的尾列不值得存在：多加一列的成本大于收益，剩下这点宽度均摊给各列也看不出来。 */
const MIN_FILLER_COLUMN_WIDTH = 24;

/**
 * 列宽之和填不满视口时，尾部那一列空白该有多宽。
 *
 * <p>表格用的是 `table-layout: fixed`，表宽被撑到容器宽度，于是多出来的宽度会被浏览器
 * **均摊给每一列** —— 两位数的 ID 列跟着被拉到 300px，一屏三列的表看上去像张空表，
 * 值与值之间隔着一大片空白。把多余的宽度显式交给一个空白尾列，各列就能保持按内容估出的
 * 宽度，剩余空间统一落在右边。</p>
 *
 * <p>返回 0 表示不需要这一列（视口还没量出来，或者内容本来就撑满了）。</p>
 */
export function fillerColumnWidth(contentWidth: number, viewportWidth?: number): number {
  if (!viewportWidth || !Number.isFinite(viewportWidth)) return 0;
  const spare = Math.floor(viewportWidth - contentWidth - FILLER_SCROLLBAR_RESERVE);
  return spare >= MIN_FILLER_COLUMN_WIDTH ? spare : 0;
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
