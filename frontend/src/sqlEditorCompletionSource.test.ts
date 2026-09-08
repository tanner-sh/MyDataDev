import { describe, expect, it, vi } from 'vitest';
import { CompletionContext } from '@codemirror/autocomplete';
import { EditorState } from '@codemirror/state';
import { createSqlEditorCompletionSource } from './sqlEditorCompletionSource';
import type { SqlCompletionResult } from './sqlEditorTypes';

const sql = 'select * from accounts where\n  ';
const context = () => new CompletionContext(EditorState.create({ doc: sql }), sql.length, false);
const result: SqlCompletionResult = { range: { start: sql.length, end: sql.length }, incomplete: false,
  items: [{ label: 'CODE', kind: 'column', insertText: 'CODE', remarks: '编码' }] };

describe('编辑器异步补全生命周期', () => {
  it('把真实 CodeMirror 上下文中的换行、缩进传到条件补全', async () => {
    const provider = vi.fn(async () => result);
    const bridge = createSqlEditorCompletionSource(() => provider, vi.fn());
    const completion = await bridge.source(context());
    expect(provider).toHaveBeenCalledWith(expect.objectContaining({ text: sql, offset: sql.length, triggerCharacter: ' ', explicit: false }));
    expect(completion?.options[0]).toMatchObject({ label: 'CODE', apply: 'CODE', remarks: '编码' });
  });

  it('慢请求显示加载状态，文档切换后不显示旧候选及错误', async () => {
    vi.useFakeTimers();
    try {
      let reject!: (error: Error) => void;
      const status = vi.fn();
      const bridge = createSqlEditorCompletionSource(() => () => new Promise((_resolve, fail) => { reject = fail; }), status);
      const pending = bridge.source(context());
      await vi.advanceTimersByTimeAsync(200);
      expect(status).toHaveBeenLastCalledWith({ loading: true });
      bridge.reset();
      reject(new Error('old failure'));
      expect(await pending).toBeNull();
      expect(status).toHaveBeenLastCalledWith(undefined);
    } finally { vi.useRealTimers(); }
  });

  it('元数据失败可再次触发，失败状态不会覆盖重试结果', async () => {
    const provider = vi.fn().mockRejectedValueOnce(new Error('offline')).mockResolvedValue(result);
    const status = vi.fn();
    const bridge = createSqlEditorCompletionSource(() => provider, status);
    expect(await bridge.source(context())).toBeNull();
    expect(status).toHaveBeenLastCalledWith({ error: '补全加载失败' });
    expect((await bridge.source(context()))?.options[0].label).toBe('CODE');
    expect(status).toHaveBeenLastCalledWith(undefined);
  });
});
