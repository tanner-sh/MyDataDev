import { describe, expect, it } from 'vitest';
import { resolveSqlEditorShortcut, shouldLoadSqlEditor } from './sqlEditorSurfaceModel';

describe('SQL editor surface model', () => {
  it('loads the advanced editor only after the user requests it', () => {
    expect(shouldLoadSqlEditor(false)).toBe(false);
    expect(shouldLoadSqlEditor(true)).toBe(true);
  });

  it('resolves supported fallback editor shortcuts', () => {
    expect(resolveSqlEditorShortcut({ key: 'Enter', ctrlKey: true, metaKey: false, shiftKey: false })).toBe('execute');
    expect(resolveSqlEditorShortcut({ key: 'F', ctrlKey: false, metaKey: true, shiftKey: true })).toBe('format');
    expect(resolveSqlEditorShortcut({ key: 'Enter', ctrlKey: false, metaKey: false, shiftKey: false })).toBeUndefined();
    expect(resolveSqlEditorShortcut({ key: 'f', ctrlKey: true, metaKey: false, shiftKey: false })).toBeUndefined();
  });
});
