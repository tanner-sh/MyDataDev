import { describe, expect, it } from 'vitest';
import {
  buildChartModel,
  canChartResult,
  chartableColumns,
  formatChartNumber,
  MAX_CATEGORIES,
  MAX_PIE_SLICES,
  niceTicks,
  ROW_NUMBER_CATEGORY,
  suggestChartConfig,
  toChartNumber
} from './resultChart';
import type { ResultColumn } from './types';

const columns: ResultColumn[] = [
  { key: 'c0', label: 'region', typeName: 'varchar' },
  { key: 'c1', label: 'orders', typeName: 'bigint' },
  { key: 'c2', label: 'revenue', typeName: 'decimal' }
];
const rows: unknown[][] = [
  ['华东', '120', '3400.5'],
  ['华北', '80', '2200'],
  ['华南', '45', '990.25']
];

describe('result chart', () => {
  it('数值解析容忍字符串，拒绝非数值', () => {
    expect(toChartNumber('3400.5')).toBe(3400.5);
    expect(toChartNumber(42)).toBe(42);
    expect(toChartNumber(null)).toBeNull();
    expect(toChartNumber('N/A')).toBeNull();
    expect(toChartNumber(true)).toBeNull();
    expect(toChartNumber(Number.NaN)).toBeNull();
  });

  it('类型名不可靠时抽查取值判断是不是数值列', () => {
    const untyped: ResultColumn[] = [
      { key: 'c0', label: 'name', typeName: 'varchar' },
      { key: 'c1', label: 'score', typeName: 'varchar' }
    ];
    const sample: unknown[][] = [['甲', '10'], ['乙', '20']];
    expect(chartableColumns(untyped, sample).values.map((column) => column.key)).toEqual(['c1']);
    // 混进一个非数值就不再算数值列。
    expect(chartableColumns(untyped, [...sample, ['丙', '未知']]).values).toHaveLength(0);
  });

  it('全是数值列时分类轴退回全部列，而不是无图可画', () => {
    const numeric: ResultColumn[] = [
      { key: 'c0', label: 'year', typeName: 'int' },
      { key: 'c1', label: 'total', typeName: 'int' }
    ];
    const result = chartableColumns(numeric, [[2024, 10]]);
    expect(result.categories.map((column) => column.key)).toEqual(['c0', 'c1']);
    expect(canChartResult(numeric, [[2024, 10]])).toBe(true);
  });

  it('没有数值列就不提供图表', () => {
    const textual: ResultColumn[] = [{ key: 'c0', label: 'name', typeName: 'varchar' }];
    expect(canChartResult(textual, [['甲']])).toBe(false);
    expect(suggestChartConfig(textual, [['甲']])).toBeNull();
  });

  it('默认只选一条数值列，避免量级悬殊的列叠在一起', () => {
    // 默认全选看起来「信息更多」，但订单数(百) 和 订单金额(百万) 叠在同一坐标轴上时，
    // 小的那条会直接贴在 0 线上看不见。
    expect(suggestChartConfig(columns, rows)).toEqual({ type: 'bar', categoryKey: 'c0', valueKeys: ['c1'] });
  });

  it('整张表都是数值时用行号当分类轴，而不是判定「没有数值列」', () => {
    const single: ResultColumn[] = [{ key: 'c0', label: 'val', typeName: 'INTEGER' }];
    const config = suggestChartConfig(single, [[1]]);
    expect(config).toEqual({ type: 'bar', categoryKey: ROW_NUMBER_CATEGORY, valueKeys: ['c0'] });

    // 行号分类轴不匹配任何一列，建模时回落成 1、2、3…
    const model = buildChartModel(single, [[10], [20]], config!);
    expect(model.categories).toEqual(['1', '2']);
    expect(model.series[0].values).toEqual([10, 20]);
  });

  it('两列都是数值时，第一列当分类轴的位置让给行号', () => {
    const numeric: ResultColumn[] = [
      { key: 'c0', label: 'year', typeName: 'INT' },
      { key: 'c1', label: 'total', typeName: 'INT' }
    ];
    const config = suggestChartConfig(numeric, [[2024, 10]]);
    expect(config?.categoryKey).toBe(ROW_NUMBER_CATEGORY);
    expect(config?.valueKeys).toEqual(['c0']);
  });

  it('柱状图基线固定在 0，有负值时向下延伸', () => {
    const model = buildChartModel(columns, rows, { type: 'bar', categoryKey: 'c0', valueKeys: ['c1'] });
    expect(model.categories).toEqual(['华东', '华北', '华南']);
    expect(model.series[0].values).toEqual([120, 80, 45]);
    expect(model.min).toBe(0);
    expect(model.max).toBe(120);

    const negative = buildChartModel(columns, [['亏损', '-30', '0']], { type: 'bar', categoryKey: 'c0', valueKeys: ['c1'] });
    expect(negative.min).toBe(-30);
    expect(negative.max).toBe(0);
  });

  it('空值保留为缺口而不是当作 0', () => {
    const model = buildChartModel(columns, [['甲', null, '1'], ['乙', '5', '2']], {
      type: 'bar', categoryKey: 'c0', valueKeys: ['c1']
    });
    expect(model.series[0].values).toEqual([null, 5]);
  });

  it('色位跟着所选列的顺序走，与当前排序无关', () => {
    const model = buildChartModel(columns, rows, { type: 'bar', categoryKey: 'c0', valueKeys: ['c2', 'c1'] });
    expect(model.series.map((item) => [item.label, item.slot])).toEqual([['revenue', 0], ['orders', 1]]);
  });

  it('超过上限的行被截断并给出提示', () => {
    const many = Array.from({ length: MAX_CATEGORIES + 5 }, (_value, index) => [`c${index}`, String(index), '0']);
    const model = buildChartModel(columns, many, { type: 'bar', categoryKey: 'c0', valueKeys: ['c1'] });
    expect(model.categories).toHaveLength(MAX_CATEGORIES);
    expect(model.droppedRows).toBe(5);
    expect(model.notices.join()).toContain(`仅显示前 ${MAX_CATEGORIES} 行`);
  });

  it('饼图只画一条序列，超出的块并入其他', () => {
    const pieRows = Array.from({ length: MAX_PIE_SLICES + 3 }, (_value, index) => [`分类${index}`, String(10 - index), '0']);
    const model = buildChartModel(columns, pieRows, { type: 'pie', categoryKey: 'c0', valueKeys: ['c1', 'c2'] });

    expect(model.categories).toHaveLength(MAX_PIE_SLICES + 1);
    expect(model.categories[model.categories.length - 1]).toContain('其他（3 项）');
    // 10+9+…，尾部三项 4+3+2 合并。
    expect(model.series[0].values[model.series[0].values.length - 1]).toBe(9);
    expect(model.notices.join()).toContain('只显示第一条序列');
  });

  it('饼图拒绝负值而不是画出无意义的扇区', () => {
    const model = buildChartModel(columns, [['甲', '-1', '0']], { type: 'pie', categoryKey: 'c0', valueKeys: ['c1'] });
    expect(model.series).toHaveLength(0);
    expect(model.notices.join()).toContain('包含负值');
  });

  it('量级悬殊时给出提示，而不是偷偷加第二个坐标轴', () => {
    const model = buildChartModel(columns, rows, { type: 'bar', categoryKey: 'c0', valueKeys: ['c1', 'c2'] });
    expect(model.notices.join()).toContain('量级相差约');
    // 量级接近时不该打扰用户。
    const close = buildChartModel(columns, [['甲', '100', '120']], {
      type: 'bar', categoryKey: 'c0', valueKeys: ['c1', 'c2']
    });
    expect(close.notices).toEqual([]);
    // 单序列没有可比对象。
    const single = buildChartModel(columns, rows, { type: 'bar', categoryKey: 'c0', valueKeys: ['c1'] });
    expect(single.notices).toEqual([]);
  });

  it('刻度落在 1/2/5 的整数倍上并覆盖数据区间', () => {
    const ticks = niceTicks(0, 120);
    expect(ticks[0]).toBe(0);
    expect(ticks[ticks.length - 1]).toBeGreaterThanOrEqual(120);
    expect(new Set(ticks.map((value, index) => (index === 0 ? 0 : value - ticks[index - 1])))).toHaveProperty('size', 2);
    expect(niceTicks(0, 0)).toEqual([0, 1]);
    expect(niceTicks(-30, 0)[0]).toBeLessThanOrEqual(-30);
  });

  it('大数压成紧凑写法', () => {
    expect(formatChartNumber(950)).toBe('950');
    expect(formatChartNumber(12_400)).toBe('12.4k');
    expect(formatChartNumber(3_400_000)).toBe('3.4M');
    expect(formatChartNumber(2_100_000_000)).toBe('2.1B');
    expect(formatChartNumber(3.14159)).toBe('3.142');
  });
});
