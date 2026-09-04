/**
 * 把查询结果整理成发给模型的输入。
 *
 * 结果解读是唯一会把真实数据发出去的功能，所以「发多少、发什么」这件事必须是一段能测的
 * 纯逻辑，而不是散在组件里的字符串拼接。行数上限在这里，后端还会再按连接的样本档位拦一次。
 */

import type { ResultColumn } from './types';
import { chartableColumns, ROW_NUMBER_CATEGORY, suggestChartConfig } from './resultChart';

/** 预览最多发多少行。再多不会让结论更准，只是把更多真实数据送出网。 */
export const MAX_PREVIEW_ROWS = 20;
/** 单元格上限：结果里可能有一整段 JSON 或日志文本。 */
export const MAX_PREVIEW_CELL_CHARS = 120;

function cell(value: unknown): string {
  if (value == null) return 'NULL';
  const text = String(value).replace(/\s+/g, ' ').trim();
  return text.length > MAX_PREVIEW_CELL_CHARS ? `${text.slice(0, MAX_PREVIEW_CELL_CHARS)}…` : text;
}

/** 制表符表格，与执行计划那边保持同一种写法。 */
export function resultPreviewText(columns: ResultColumn[], rows: unknown[][], maxRows = MAX_PREVIEW_ROWS): string {
  if (columns.length === 0) return '';
  const limited = rows.slice(0, Math.max(0, maxRows));
  const lines = [columns.map((column) => column.label).join('\t'), ...limited.map((row) => row.map(cell).join('\t'))];
  if (rows.length > limited.length) lines.push(`…（共 ${rows.length} 行，以上为前 ${limited.length} 行）`);
  return lines.join('\n');
}

/**
 * 描述工作台真能画出来的图表候选。
 *
 * 模型只能在这些候选里挑 —— 它看不到图表模块的能力边界，凭空推荐一个画不出来的图，
 * 用户照着做只会发现按钮是灰的。
 */
export function chartCandidateText(columns: ResultColumn[], rows: unknown[][]): string {
  const suggestion = suggestChartConfig(columns, rows);
  if (!suggestion) return '';
  const { categories, values } = chartableColumns(columns, rows);
  const labelOf = (key: string) => (key === ROW_NUMBER_CATEGORY ? '行号' : columns.find((column) => column.key === key)?.label ?? key);
  const parts = [
    `默认推荐：${chartTypeLabel(suggestion.type)}，分类轴 ${labelOf(suggestion.categoryKey)}，`
      + `数值列 ${suggestion.valueKeys.map(labelOf).join('、')}`
  ];
  if (categories.length > 0) parts.push(`可选分类轴：${categories.map((column) => column.label).join('、')}`);
  if (values.length > 0) parts.push(`可选数值列：${values.map((column) => column.label).join('、')}`);
  parts.push('支持的图表类型：柱状图、折线图、饼图');
  return parts.join('\n');
}

function chartTypeLabel(type: 'bar' | 'line' | 'pie'): string {
  if (type === 'line') return '折线图';
  if (type === 'pie') return '饼图';
  return '柱状图';
}
