import { describe, expect, it } from 'vitest';
import { alreadyInGlossary, mergeSuggestions } from './aiGlossary';
import type { AiGlossaryEntry, AiGlossarySuggestion } from './types';

const existing: AiGlossaryEntry[] = [
  { id: 1, term: '客户', aliases: ['会员'], objectNames: ['T_CRM_0021'], description: '客户主档' }
];

function suggestion(term: string, objectNames: string[] = ['X']): AiGlossarySuggestion {
  return { term, aliases: [`${term}表`], objectNames, description: `${term}说明`, usageCount: 3 };
}

describe('mergeSuggestions', () => {
  it('把候选并进词典并保留别名、对象与说明', () => {
    const merged = mergeSuggestions(existing, [suggestion('订单', ['SALES_ORDER', 'SALES_ORDER_ITEM'])]);

    expect(merged).toHaveLength(2);
    expect(merged[1]).toMatchObject({
      term: '订单',
      aliases: ['订单表'],
      objectNames: ['SALES_ORDER', 'SALES_ORDER_ITEM'],
      description: '订单说明'
    });
    expect(merged[1].id).toBeLessThan(0);
  });

  /** 后端对业务词有唯一约束，重名会让整批保存失败 —— 那时用户已经改了半天。 */
  it('跳过已存在的业务词，大小写和首尾空白都不算差异', () => {
    expect(mergeSuggestions(existing, [suggestion('客户'), suggestion(' 客户 ')])).toHaveLength(1);
  });

  it('同一批里重复的候选只并入一次', () => {
    expect(mergeSuggestions(existing, [suggestion('订单'), suggestion('订单')])).toHaveLength(2);
  });

  it('每条新词条拿到互不相同的临时 id，否则表格会串行', () => {
    const merged = mergeSuggestions([], [suggestion('订单'), suggestion('商品'), suggestion('支付')]);

    expect(new Set(merged.map((entry) => entry.id)).size).toBe(3);
  });

  it('空词或空选择不改变现有词典', () => {
    expect(mergeSuggestions(existing, [])).toEqual(existing);
    expect(mergeSuggestions(existing, [suggestion('  ')])).toEqual(existing);
  });
});

describe('alreadyInGlossary', () => {
  it('标出哪些候选已经在词典里', () => {
    const marked = alreadyInGlossary(existing, [suggestion('客户'), suggestion('订单')]);

    expect(marked.has('客户')).toBe(true);
    expect(marked.has('订单')).toBe(false);
  });
});
