import { describe, expect, it } from 'vitest';
import { chartCandidateText, MAX_PREVIEW_ROWS, resultPreviewText } from './aiResultPreview';
import type { ResultColumn } from './types';

const COLUMNS: ResultColumn[] = [
  { key: 'c0', label: 'status', typeName: 'VARCHAR' },
  { key: 'c1', label: 'amount', typeName: 'DECIMAL' }
];

describe('结果预览文本', () => {
  it('用制表符表格，空值写 NULL', () => {
    expect(resultPreviewText(COLUMNS, [['PAID', 12], [null, 0]])).toBe('status\tamount\nPAID\t12\nNULL\t0');
  });

  /** 发多少真实数据是这个功能最敏感的一件事，行数上限必须被测到。 */
  it('超过上限只发前几行，并说明总行数', () => {
    const rows = Array.from({ length: MAX_PREVIEW_ROWS + 5 }, (_, index) => [`s${index}`, index]);

    const text = resultPreviewText(COLUMNS, rows);

    expect(text.split('\n')).toHaveLength(MAX_PREVIEW_ROWS + 2);
    expect(text).toContain(`共 ${rows.length} 行`);
  });

  it('超长单元格会被截断', () => {
    expect(resultPreviewText(COLUMNS, [['x'.repeat(500), 1]])).toContain('…');
  });

  it('没有列时返回空串', () => {
    expect(resultPreviewText([], [[1]])).toBe('');
  });
});

describe('图表候选描述', () => {
  it('画不出图时返回空串，提示词里那一段整段省掉', () => {
    const textColumns: ResultColumn[] = [{ key: 'c0', label: 'name', typeName: 'VARCHAR' }];

    expect(chartCandidateText(textColumns, [['a'], ['b']])).toBe('');
  });

  it('列出默认推荐与可选列，并限定图表类型', () => {
    const text = chartCandidateText(COLUMNS, [['PAID', 12], ['NEW', 8]]);

    expect(text).toContain('默认推荐');
    expect(text).toContain('status');
    expect(text).toContain('amount');
    expect(text).toContain('支持的图表类型：柱状图、折线图、饼图');
  });
});
