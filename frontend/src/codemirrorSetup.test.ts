import { describe, expect, it } from 'vitest';
import { syntaxTree } from '@codemirror/language';
import { EditorState } from '@codemirror/state';
import { sqlEditorExtensions } from './codemirrorSetup';

describe('CodeMirror SQL 扩展', () => {
  it('实际挂载 SQL 解析器而不是退化为纯文本', () => {
    const state = EditorState.create({
      doc: 'SELECT id FROM users WHERE active = true',
      extensions: sqlEditorExtensions({})
    });

    const tree = syntaxTree(state).toString();
    // @lezer/sql 有意使用通用的 Statement 节点，不按 SELECT/FROM/WHERE 再分层；关键是
    // SQL token 已被识别成 Keyword、Identifier、Operator 和 Bool，而不是一整段纯文本。
    expect(tree).toMatch(/^Script\(Statement\(/);
    expect(tree).toContain('Keyword');
    expect(tree).toContain('Identifier');
    expect(tree).toContain('Operator');
    expect(tree).toContain('Bool');
  });

  it('允许建立非空文本选区', () => {
    const state = EditorState.create({
      doc: 'SELECT id FROM users',
      extensions: sqlEditorExtensions({})
    });

    const selected = state.update({ selection: { anchor: 0, head: 9 } }).state.selection.main;
    expect(selected.empty).toBe(false);
    expect({ from: selected.from, to: selected.to }).toEqual({ from: 0, to: 9 });
  });
});
