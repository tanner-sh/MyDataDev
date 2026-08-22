import { describe, expect, it } from 'vitest';
import { resolveSqlEditorShortcut, shouldLoadSqlEditor, toggleSqlLineComment } from './sqlEditorSurfaceModel';

describe('SQL editor surface model', () => {
  it('loads the advanced editor only after the user requests it', () => {
    expect(shouldLoadSqlEditor(false)).toBe(false);
    expect(shouldLoadSqlEditor(true)).toBe(true);
  });

  it('resolves supported fallback editor shortcuts', () => {
    expect(resolveSqlEditorShortcut({ key: 'Enter', ctrlKey: true, metaKey: false, shiftKey: false })).toBe('execute');
    expect(resolveSqlEditorShortcut({ key: 'F', ctrlKey: false, metaKey: true, shiftKey: true })).toBe('format');
    expect(resolveSqlEditorShortcut({ key: '/', ctrlKey: true, metaKey: false, shiftKey: false })).toBe('comment');
    expect(resolveSqlEditorShortcut({ key: '/', ctrlKey: false, metaKey: true, shiftKey: false })).toBe('comment');
    expect(resolveSqlEditorShortcut({ key: 'Enter', ctrlKey: false, metaKey: false, shiftKey: false })).toBeUndefined();
    expect(resolveSqlEditorShortcut({ key: 'f', ctrlKey: true, metaKey: false, shiftKey: false })).toBeUndefined();
    expect(resolveSqlEditorShortcut({ key: '/', ctrlKey: false, metaKey: false, shiftKey: false })).toBeUndefined();
  });

  it('toggles the current SQL line while preserving indentation', () => {
    const commented = toggleSqlLineComment('  select 1;', 4, 4);
    expect(commented.value).toBe('  -- select 1;');
    expect(toggleSqlLineComment(commented.value, commented.selectionStart, commented.selectionEnd).value).toBe('  select 1;');
  });

  it('comments selected lines, ignores empty lines, and excludes a trailing unselected line', () => {
    const sql = 'select 1;\n\n  select 2;';
    const result = toggleSqlLineComment(sql, 0, 'select 1;\n'.length);

    expect(result.value).toBe('-- select 1;\n\n  select 2;');
  });

  it('comments a mixed selection before toggling the whole selection off', () => {
    const sql = '-- select 1;\nselect 2;';
    const commented = toggleSqlLineComment(sql, 0, sql.length);
    expect(commented.value).toBe('-- -- select 1;\n-- select 2;');

    const uncommented = toggleSqlLineComment(commented.value, 0, commented.value.length);
    expect(uncommented.value).toBe('-- select 1;\nselect 2;');
  });
});

describe('shouldLoadSqlEditor', () => {
  it('挂载于用户聚焦或首屏空闲自动加载', () => {
    expect(shouldLoadSqlEditor(false)).toBe(false);
    expect(shouldLoadSqlEditor(true)).toBe(true);
    expect(shouldLoadSqlEditor(false, true)).toBe(true);
    expect(shouldLoadSqlEditor(true, true)).toBe(true);
  });
});
