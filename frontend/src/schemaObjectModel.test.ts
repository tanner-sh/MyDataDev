import { describe, expect, it } from 'vitest';
import {
  compilationErrorSummary,
  explorerObjectCountLabel,
  explorerObjectKindLabel,
  explorerObjectKinds,
  formatCompilationError,
  normalizeExplorerObjectKind,
  schemaObjectCapabilities,
  schemaObjectConfirmationTarget
} from './schemaObjectModel';

describe('schema object model', () => {
  it('orders capabilities by object group', () => {
    const capabilities = schemaObjectCapabilities({
      tableBrowse: true,
      tableEdit: false,
      tableDesign: false,
      explain: false,
      nativeBackupMethods: [],
      schemaObjects: [
        { kind: 'FUNCTION', operations: ['LIST'] },
        { kind: 'VIEW', operations: ['LIST'] }
      ]
    });
    expect(capabilities.map((item) => item.kind)).toEqual(['VIEW', 'FUNCTION']);
  });

  it('uses the overload display name for strong confirmation', () => {
    expect(schemaObjectConfirmationTarget({ schemaName: 'public', name: 'calculate', displayName: 'calculate(integer)', kind: 'FUNCTION' }))
      .toBe('public.calculate(integer)');
    expect(schemaObjectConfirmationTarget({ schemaName: 'public', name: 'audit_insert', displayName: 'audit_insert · users', kind: 'TRIGGER' }))
      .toBe('public.audit_insert');
  });

  it('builds the explorer kinds from supported capabilities', () => {
    const capabilities = {
      tableBrowse: true,
      tableEdit: false,
      tableDesign: false,
      explain: false,
      nativeBackupMethods: [],
      schemaObjects: [
        { kind: 'FUNCTION' as const, operations: ['LIST' as const] },
        { kind: 'VIEW' as const, operations: ['LIST' as const] }
      ]
    };

    expect(explorerObjectKinds(capabilities)).toEqual(['TABLE', 'VIEW', 'FUNCTION']);
    expect(normalizeExplorerObjectKind('TRIGGER', capabilities)).toBe('TABLE');
    expect(normalizeExplorerObjectKind('VIEW', capabilities)).toBe('VIEW');
  });

  it('formats navigation labels and lazy counts', () => {
    expect(explorerObjectKindLabel('TABLE', true)).toBe('表');
    expect(explorerObjectKindLabel('TABLE', false)).toBe('表与视图');
    expect(explorerObjectCountLabel()).toBe('…');
    expect(explorerObjectCountLabel({ loaded: 0, loading: true })).toBe('…');
    expect(explorerObjectCountLabel({ loaded: 200, hasMore: true })).toBe('200+');
    expect(explorerObjectCountLabel({ loaded: 100, total: 257, hasMore: true })).toBe('257');
  });
});

describe('编译错误', () => {
  it('把位置写在原文前面，用户要找的是从哪一行开始改', () => {
    expect(formatCompilationError({ line: 3, position: 12, text: 'PLS-00103: 出现符号 END' }))
      .toBe('第 3 行 第 12 列：PLS-00103: 出现符号 END');
  });

  /** 位置缺失时编一个「第 0 行」会把人带到错的地方去。 */
  it('位置缺失时只给原文', () => {
    expect(formatCompilationError({ text: 'PLS-00103' })).toBe('PLS-00103');
    expect(formatCompilationError({ line: 3, text: 'PLS-00103' })).toBe('第 3 行：PLS-00103');
  });

  it('没有错误时不给结论', () => {
    expect(compilationErrorSummary([])).toBe('');
  });

  /** 「已创建」必须和「不可用」出现在同一句话里，否则前半句会被单独读走。 */
  it('结论同时说出「建出来了」和「不可用」', () => {
    const summary = compilationErrorSummary([{ line: 3, text: 'x' }]);
    expect(summary).toContain('已创建');
    expect(summary).toContain('不可用');
    expect(summary).toContain('第 3 行');
  });

  /** 一条语法错误往往级联出十几行，后面那些指的都是同一个原因。 */
  it('多条错误时给出条数并仍然指向第一条的位置', () => {
    expect(compilationErrorSummary([{ line: 3, text: 'x' }, { line: 9, text: 'y' }]))
      .toBe('对象已创建但编译未通过，共 2 条错误，从第 3 行开始，当前不可用。');
  });
});
