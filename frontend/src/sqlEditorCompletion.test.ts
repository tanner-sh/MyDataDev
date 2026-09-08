import { describe, expect, it } from 'vitest';
import { completionTriggerCharacter, completionValidFor, conditionKeywordInsertion, isConditionKeywordCompletion, toEditorCompletion } from './sqlEditorCompletion';
import type { SqlCompletionResult } from './sqlEditorTypes';
import { EditorSelection, EditorState, type TransactionSpec } from '@codemirror/state';
import type { EditorView } from '@codemirror/view';

describe('补全触发字符', () => {
  it('识别点号并统一空白触发字符', () => {
    expect(completionTriggerCharacter('select * from t.', 16)).toBe('.');
    expect(completionTriggerCharacter('select id ', 10)).toBe(' ');
    expect(completionTriggerCharacter('select id', 9)).toBeUndefined();
    expect(completionTriggerCharacter('where\n', 6)).toBe(' ');
    expect(completionTriggerCharacter('where\t', 6)).toBe(' ');
  });

  it('文档开头和越界偏移不算触发', () => {
    expect(completionTriggerCharacter('abc', 0)).toBeUndefined();
    expect(completionTriggerCharacter('abc', 99)).toBeUndefined();
    expect(completionTriggerCharacter('', 0)).toBeUndefined();
  });

  it('显式补全不继承光标前的触发字符', () => {
    expect(completionTriggerCharacter('select * from ', 14, true)).toBeUndefined();
    expect(completionTriggerCharacter('select * from t.', 16, true)).toBeUndefined();
  });
});

describe('本地过滤规则', () => {
  it('结果不完整时必须重新问，不能在本地过滤', () => {
    expect(completionValidFor(true)).toBeUndefined();
  });

  it('引号标识符可以本地过滤，点号必须重新解析字段上下文', () => {
    const pattern = completionValidFor(false)!;
    expect(pattern.test('order')).toBe(true);
    expect(pattern.test('"orders"')).toBe(true);
    expect(pattern.test('`user`')).toBe(true);
    expect(pattern.test('[orders]')).toBe(true);
    expect(pattern.test('public.orders')).toBe(false);
    expect(pattern.test('a.')).toBe(false);
    // 逗号意味着已经换到下一个词了，弹窗该关掉重来。
    expect(pattern.test('id,')).toBe(false);
    // 空格同理。带空格的标识符（"order by"）会因此重新查询一次 —— 少一次本地过滤，
    // 但让弹窗跨空格存活的代价更大：正常输入里空格几乎总是意味着换词。
    expect(pattern.test('"order by"')).toBe(false);
  });
});

describe('补全项翻译', () => {
  const result: SqlCompletionResult = {
    range: { start: 10, end: 13 },
    incomplete: false,
    items: [
      { label: 'id', kind: 'column', insertText: 'id', detail: 'orders 字段 · BIGINT', sortText: '0-id' },
      { label: 'orders', kind: 'table', insertText: 'orders', detail: 'public · 表', sortText: '1-orders' },
      { label: 'SELECT', kind: 'keyword', insertText: 'SELECT', sortText: '2-select' }
    ]
  };

  it('区间原样传递，种类映射到编辑器的图标类型', () => {
    const translated = toEditorCompletion(result);
    expect(translated.from).toBe(10);
    expect(translated.to).toBe(13);
    expect(translated.options.map((option) => option.type)).toEqual(['property', 'class', 'keyword']);
    expect(translated.options[0].apply).toBe('id');
  });

  it('列排在表和关键字前面：查询里正缺的通常是列名', () => {
    const translated = toEditorCompletion(result);
    expect(translated.options[0].boost).toBe(1);
    expect(translated.options[1].boost).toBe(0);
    expect(translated.options[2].boost).toBe(0);
  });

  it('incomplete 会一路传到 validFor', () => {
    expect(toEditorCompletion({ ...result, incomplete: true }).validFor).toBeUndefined();
    expect(toEditorCompletion(result).validFor).toBeInstanceOf(RegExp);
  });

  it('注释只用于摘要和详情，不改变名称及插入值', () => {
    const translated = toEditorCompletion({ ...result, items: [{ label: 'CODE', kind: 'column', insertText: 'CODE', remarks: '账户编码\n完整备注', detail: 'BD_ACCOUNT · VARCHAR2' }] });
    expect(translated.options[0]).toMatchObject({ label: 'CODE', apply: 'CODE', remarks: '账户编码\n完整备注' });
    expect(translated.options[0].info).toContain('账户编码\n完整备注');
  });

  it('条件关键字补全保留已有空白，自动激活字段候选', () => {
    expect(conditionKeywordInsertion('whe', 3, 'WHERE')).toEqual({ insert: 'WHERE ', followingWhitespace: 0 });
    expect(conditionKeywordInsertion('whe\n  code', 3, 'WHERE')).toEqual({ insert: 'WHERE', followingWhitespace: 3 });
    expect(isConditionKeywordCompletion({ label: 'WHERE', type: 'keyword' })).toBe(true);
    expect(isConditionKeywordCompletion({ label: 'WHERE', type: 'property' })).toBe(false);
    expect(isConditionKeywordCompletion({ label: 'FROM', type: 'keyword' })).toBe(false);
  });

  it('条件关键字在多个光标处补全，分别复用换行或补空格', () => {
    let state = EditorState.create({ doc: 'whe\n  code\nwhe',
      extensions: EditorState.allowMultipleSelections.of(true),
      selection: EditorSelection.create([EditorSelection.cursor(3), EditorSelection.cursor(14)]) });
    const option = toEditorCompletion({ range: { start: 0, end: 3 }, incomplete: false,
      items: [{ label: 'WHERE', kind: 'keyword', insertText: 'WHERE' }] }).options[0];
    if (typeof option.apply !== 'function') throw new Error('条件补全必须使用插入函数');
    option.apply({ state, dispatch: (spec: TransactionSpec) => { state = state.update(spec).state; } } as EditorView, option, 0, 3);
    expect(state.doc.toString()).toBe('WHERE\n  code\nWHERE ');
    expect(state.selection.ranges.map(range => range.head)).toEqual([8, 19]);
  });
});
