/**
 * 查询结果的图表建模。
 *
 * 只负责「这份结果能画什么、画成什么形状」，不碰 DOM —— 渲染在 ResultChart.tsx。
 * 图表画的始终是当前已加载的这一批行，不会替用户重新查询：一次可视化不该悄悄再压一次库。
 */
import type { ResultColumn } from './types';

export type ChartType = 'bar' | 'line' | 'pie';

export type ChartConfig = {
  type: ChartType;
  /** 分类轴取哪一列，值是 ResultColumn.key。 */
  categoryKey: string;
  /** 数值轴取哪几列。 */
  valueKeys: string[];
};

/** 分类调色板只有 8 个色位，第 9 个颜色在色觉障碍下和已有色位分不开。 */
export const MAX_SERIES = 8;
/** 超过这个点数，柱子会细到看不清，读图不如读表。 */
export const MAX_CATEGORIES = 200;
/** 饼图超过 6 块就没法一眼读出占比，其余并入「其他」。 */
export const MAX_PIE_SLICES = 6;
/**
 * 两条序列的量级差到这个倍数以上就该拆开看。
 *
 * 绘图区高约 280px：差 20 倍时小的那条只剩十几像素，差 50 倍就只有几像素了。
 */
export const SCALE_MISMATCH_RATIO = 20;

/**
 * 「行号」这个分类轴的取值。
 *
 * 它不会匹配任何一列，buildChartModel 据此回落到行序号。有了它，只有一列数值的结果
 * （select count(*)、单指标时序）也能画图，而不是被判成「没有可用于绘图的数值列」。
 */
export const ROW_NUMBER_CATEGORY = '__row_number__';

const NUMERIC_TYPES = /(^|\s)(tinyint|smallint|mediumint|int|integer|bigint|decimal|numeric|number|real|float|double|serial|bigserial|money)(\s|$|\()/i;
const NUMERIC_VALUE = /^[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?$/;
/** 判定「这列是不是数值」时抽查的行数，全表扫描没有必要。 */
const SAMPLE_ROWS = 50;

export type ChartSeries = {
  key: string;
  label: string;
  /** 色位下标，跟着列走而不是跟着当前排序走 —— 筛掉一条序列不该让其余的换色。 */
  slot: number;
  values: Array<number | null>;
};

export type ChartModel = {
  type: ChartType;
  categories: string[];
  series: ChartSeries[];
  min: number;
  max: number;
  /** 因为超过 MAX_CATEGORIES 被截掉的行数。 */
  droppedRows: number;
  /** 需要在图上方告诉用户的事，例如截断、负值不适合饼图。 */
  notices: string[];
};

export function toChartNumber(value: unknown): number | null {
  if (value == null || typeof value === 'boolean') return null;
  if (typeof value === 'number') return Number.isFinite(value) ? value : null;
  if (typeof value === 'bigint') return Number(value);
  const text = String(value).trim();
  if (!text || !NUMERIC_VALUE.test(text)) return null;
  const parsed = Number(text);
  return Number.isFinite(parsed) ? parsed : null;
}

/**
 * 哪些列能当数值轴。
 *
 * 类型名是首选依据，但很多驱动把数值报成 VARCHAR，所以类型名不匹配时再抽查若干行：
 * 有非空值且全部能解析成数字才算数值列。
 */
export function isValueColumn(column: ResultColumn, rows: unknown[][], columnIndex: number): boolean {
  if (column.typeName && NUMERIC_TYPES.test(column.typeName)) return true;
  let seen = 0;
  for (const row of rows.slice(0, SAMPLE_ROWS)) {
    const value = row[columnIndex];
    if (value == null || String(value).trim() === '') continue;
    if (toChartNumber(value) == null) return false;
    seen += 1;
  }
  return seen > 0;
}

export function chartableColumns(columns: ResultColumn[], rows: unknown[][]) {
  const values = columns.filter((column, index) => isValueColumn(column, rows, index));
  const valueKeys = new Set(values.map((column) => column.key));
  // 分类轴优先用非数值列；整张表都是数值时（比如两列指标），退回全部列，
  // 让用户自己选一列当横轴。
  const categories = columns.filter((column) => !valueKeys.has(column.key));
  return { categories: categories.length > 0 ? categories : columns, values };
}

/** 结果集能不能画图：至少要有一列数值。 */
export function canChartResult(columns: ResultColumn[], rows: unknown[][]): boolean {
  return chartableColumns(columns, rows).values.length > 0;
}

/**
 * 猜一份初始配置：第一列非数值列当分类轴，前若干列数值列当序列。
 *
 * 猜错的成本很低（用户改一下选择框），猜对的收益是打开图表就能看到东西。
 */
export function suggestChartConfig(columns: ResultColumn[], rows: unknown[][]): ChartConfig | null {
  const { categories, values } = chartableColumns(columns, rows);
  if (values.length === 0) return null;
  // 分类轴优先用非数值列；整张表都是数值时用行号，而不是拿一列数值去当横轴。
  const category = categories.find((column) => !values.some((value) => value.key === column.key));
  const categoryKey = category ? category.key : ROW_NUMBER_CATEGORY;
  const remaining = values.filter((column) => column.key !== categoryKey);
  if (remaining.length === 0) return null;
  // 只默认选第一条数值列。默认全选看起来「信息更多」，但多列指标的量级往往差几个数量级
  // （订单数 vs 订单金额），叠在同一个坐标轴上小的那条会直接贴在 0 线上看不见。
  return { type: 'bar', categoryKey, valueKeys: [remaining[0].key] };
}

export function buildChartModel(columns: ResultColumn[], rows: unknown[][], config: ChartConfig): ChartModel {
  const notices: string[] = [];
  const categoryIndex = columns.findIndex((column) => column.key === config.categoryKey);
  const selected = config.valueKeys
    .map((key) => ({ key, index: columns.findIndex((column) => column.key === key) }))
    .filter((entry) => entry.index >= 0);

  let valueColumns = selected;
  if (valueColumns.length > MAX_SERIES) {
    valueColumns = valueColumns.slice(0, MAX_SERIES);
    notices.push(`最多同时显示 ${MAX_SERIES} 条序列，其余已忽略。`);
  }

  const visibleRows = rows.slice(0, MAX_CATEGORIES);
  const droppedRows = rows.length - visibleRows.length;
  if (droppedRows > 0) notices.push(`仅显示前 ${MAX_CATEGORIES} 行，另有 ${droppedRows} 行未参与绘制。`);

  const categories = visibleRows.map((row, rowIndex) => {
    if (categoryIndex < 0) return String(rowIndex + 1);
    const value = row[categoryIndex];
    return value == null ? '(null)' : String(value);
  });

  let series: ChartSeries[] = valueColumns.map((entry, slot) => ({
    key: entry.key,
    label: columns[entry.index].label,
    slot,
    values: visibleRows.map((row) => toChartNumber(row[entry.index]))
  }));

  if (config.type === 'pie') {
    return pieModel(categories, series, notices);
  }

  const numbers = series.flatMap((item) => item.values).filter((value): value is number => value != null);
  // 有负值时基线必须落在 0 上，否则柱子的长度不再等比于数值。
  const max = numbers.length === 0 ? 0 : Math.max(0, ...numbers);
  const min = numbers.length === 0 ? 0 : Math.min(0, ...numbers);
  if (numbers.length === 0) notices.push('所选列在当前批次里没有可用的数值。');
  const mismatch = scaleMismatch(series);
  if (mismatch) notices.push(mismatch);
  return { type: config.type, categories, series, min, max, droppedRows, notices };
}

/**
 * 量级悬殊时给一句提示，而不是偷偷加第二个坐标轴。
 *
 * 两个 Y 轴的对齐方式是任意的，等于凭空造出一段并不存在的相关性 —— 这是图表里最常见的
 * 误导。正确的做法是分开画，所以这里只把问题说清楚，让用户自己拆。
 */
function scaleMismatch(series: ChartSeries[]): string | null {
  if (series.length < 2) return null;
  const scales = series.map((item) => ({
    label: item.label,
    magnitude: Math.max(0, ...item.values.filter((value): value is number => value != null).map(Math.abs))
  })).filter((item) => item.magnitude > 0);
  if (scales.length < 2) return null;
  const largest = scales.reduce((left, right) => (right.magnitude > left.magnitude ? right : left));
  const smallest = scales.reduce((left, right) => (right.magnitude < left.magnitude ? right : left));
  if (largest.magnitude / smallest.magnitude < SCALE_MISMATCH_RATIO) return null;
  return `「${largest.label}」与「${smallest.label}」量级相差约 ${Math.round(largest.magnitude / smallest.magnitude)} 倍，`
    + '放在同一个坐标轴上小的那条几乎看不见。建议分别查看，而不是叠在一起读。';
}

/**
 * 饼图只画一条序列，且只在「部分对整体」成立时才有意义。
 *
 * 负值会让扇区角度失去含义，直接拒绝；块数超过上限的并入「其他」，因为超过六块就没法
 * 一眼读出占比了。
 */
function pieModel(categories: string[], series: ChartSeries[], notices: string[]): ChartModel {
  const first = series[0];
  if (!first) {
    return { type: 'pie', categories: [], series: [], min: 0, max: 0, droppedRows: 0, notices: ['请选择一列数值。'] };
  }
  if (series.length > 1) notices.push('饼图只显示第一条序列，其余已忽略。');
  const pairs = categories
    .map((name, index) => ({ name, value: first.values[index] }))
    .filter((entry): entry is { name: string; value: number } => entry.value != null);
  if (pairs.some((entry) => entry.value < 0)) {
    return {
      type: 'pie',
      categories: [],
      series: [],
      min: 0,
      max: 0,
      droppedRows: 0,
      notices: [...notices, '所选列包含负值，饼图无法表达，请改用柱状图。']
    };
  }
  const sorted = [...pairs].sort((left, right) => right.value - left.value);
  const head = sorted.slice(0, MAX_PIE_SLICES);
  const tail = sorted.slice(MAX_PIE_SLICES);
  if (tail.length > 0) {
    head.push({ name: `其他（${tail.length} 项）`, value: tail.reduce((total, entry) => total + entry.value, 0) });
  }
  return {
    type: 'pie',
    categories: head.map((entry) => entry.name),
    series: [{ ...first, slot: 0, values: head.map((entry) => entry.value) }],
    min: 0,
    max: head.reduce((total, entry) => total + entry.value, 0),
    droppedRows: 0,
    notices
  };
}

/**
 * 一组“好看的”刻度：步长取 1/2/5 乘以 10 的幂，端点落在步长的整数倍上。
 */
export function niceTicks(min: number, max: number, count = 4): number[] {
  if (!Number.isFinite(min) || !Number.isFinite(max)) return [0];
  if (min === max) return min === 0 ? [0, 1] : [Math.min(0, min), Math.max(0, max)];
  const rawStep = (max - min) / Math.max(1, count);
  const magnitude = 10 ** Math.floor(Math.log10(rawStep));
  const normalized = rawStep / magnitude;
  const step = (normalized <= 1 ? 1 : normalized <= 2 ? 2 : normalized <= 5 ? 5 : 10) * magnitude;
  const start = Math.floor(min / step) * step;
  const end = Math.ceil(max / step) * step;
  const ticks: number[] = [];
  // 浮点累加会漂移，用步数乘回去；末尾加一点余量以容忍最后一格的舍入。
  for (let index = 0; start + index * step <= end + step / 1000; index += 1) {
    ticks.push(Number((start + index * step).toPrecision(12)));
  }
  return ticks;
}

/** 轴刻度与提示里的数字：大数压成 1.2k / 3.4M，其余保留最多 3 位小数。 */
export function formatChartNumber(value: number): string {
  if (!Number.isFinite(value)) return '';
  const absolute = Math.abs(value);
  if (absolute >= 1_000_000_000) return `${trimZeros(value / 1_000_000_000)}B`;
  if (absolute >= 1_000_000) return `${trimZeros(value / 1_000_000)}M`;
  if (absolute >= 10_000) return `${trimZeros(value / 1_000)}k`;
  if (Number.isInteger(value)) return String(value);
  return trimZeros(value, 3);
}

function trimZeros(value: number, digits = 1): string {
  return Number(value.toFixed(digits)).toString();
}
