import { describe, expect, it } from 'vitest';
import {
  buildEditableDisplayRows,
  editableCellKey,
  editableRowClassName,
  isEditableCellDisabled,
  shouldEditableCellUpdate,
  splitEditableCellKey
} from './editableTableRows';
import type { EditableRow, TableColumn } from './types';

const column: TableColumn = { name: 'name', typeName: 'VARCHAR', jdbcType: 12, nullable: true };

function row(overrides: Partial<EditableRow> = {}): EditableRow {
  return { id: 'row-0-0', values: { name: 'a' }, original: { name: 'a' }, ...overrides };
}

function build(
  rows: EditableRow[],
  options: { readonly?: boolean; loading?: boolean; activeCell?: string | null; editable?: boolean } = {}
) {
  return buildEditableDisplayRows({
    rows,
    data: { editable: options.editable ?? true },
    readonly: options.readonly ?? false,
    loading: options.loading ?? false,
    activeCell: options.activeCell ?? null
  });
}

describe('editable cell keys', () => {
  it('round-trips a row id and column name', () => {
    expect(splitEditableCellKey(editableCellKey('row-1-2', 'name'))).toEqual({ rowId: 'row-1-2', columnName: 'name' });
  });

  it('keeps a column name that itself contains the separator character', () => {
    expect(splitEditableCellKey(editableCellKey('row-1-2', 'order date'))).toEqual({ rowId: 'row-1-2', columnName: 'order date' });
  });

  it('rejects a key without a separator', () => {
    expect(splitEditableCellKey('row-1-2')).toBeUndefined();
  });
});

describe('editableRowClassName', () => {
  it('marks deleted, inserted and edited rows', () => {
    expect(editableRowClassName(row({ deleted: true }))).toBe('deleted-row');
    expect(editableRowClassName(row({ inserted: true, original: undefined }))).toBe('inserted-row');
    expect(editableRowClassName(row({ values: { name: 'b' } }))).toBe('updated-row');
    expect(editableRowClassName(row())).toBe('');
  });
});

describe('buildEditableDisplayRows', () => {
  it('opens editing only on the active row', () => {
    const rows = build([row(), row({ id: 'row-0-1' })], { activeCell: editableCellKey('row-0-1', 'name') });
    expect(rows[0].editingColumn).toBeUndefined();
    expect(rows[1].editingColumn).toBe('name');
  });

  it('disables rows of a read-only or busy grid', () => {
    expect(build([row()], { readonly: true })[0].rowDisabled).toBe(true);
    expect(build([row()], { loading: true })[0].rowDisabled).toBe(true);
    expect(build([row()])[0].rowDisabled).toBe(false);
  });

  it('keeps locally inserted rows editable in a table without a row identity', () => {
    expect(build([row({ inserted: true })], { editable: false })[0].rowDisabled).toBe(false);
    expect(build([row()], { editable: false })[0].rowDisabled).toBe(true);
  });
});

describe('isEditableCellDisabled', () => {
  it('disables cells of a deleted row and non-editable columns', () => {
    expect(isEditableCellDisabled(build([row({ deleted: true })])[0], column)).toBe(true);
    expect(isEditableCellDisabled(build([row()])[0], column)).toBe(false);
    expect(isEditableCellDisabled(build([row()])[0], { ...column, editable: false })).toBe(true);
  });
});

describe('shouldEditableCellUpdate', () => {
  // App.tsx keeps row objects (and their `values`) stable across renders and
  // replaces only the ones an edit touched, which is what this mirrors.
  const source = [row(), row({ id: 'row-0-1' })];
  const base = build(source);

  it('skips rows that a rebuild left unchanged', () => {
    const rebuilt = build(source);
    // Every rebuild allocates fresh display rows, so identity must not decide this.
    expect(rebuilt[0]).not.toBe(base[0]);
    expect(shouldEditableCellUpdate(rebuilt[0], base[0])).toBe(false);
  });

  it('re-renders the row whose value, edit state or disabled state changed', () => {
    const edited = [{ ...source[0], values: { name: 'b' } }, source[1]];
    expect(shouldEditableCellUpdate(build(edited)[0], base[0])).toBe(true);
    expect(shouldEditableCellUpdate(build(source, { activeCell: editableCellKey('row-0-0', 'name') })[0], base[0])).toBe(true);
    expect(shouldEditableCellUpdate(build(source, { loading: true })[0], base[0])).toBe(true);
  });

  it('leaves untouched rows alone when another row is edited', () => {
    const edited = [{ ...source[0], values: { name: 'b' } }, source[1]];
    expect(shouldEditableCellUpdate(build(edited)[1], base[1])).toBe(false);
  });

  it('re-renders when a row is marked deleted or newly inserted', () => {
    expect(shouldEditableCellUpdate(build([{ ...source[0], deleted: true }])[0], base[0])).toBe(true);
    expect(shouldEditableCellUpdate(build([{ ...source[0], inserted: true, touchedColumns: ['name'] }])[0], base[0])).toBe(true);
  });

  it('re-renders both the row losing and the row gaining the active cell', () => {
    const before = build(source, { activeCell: editableCellKey('row-0-0', 'name') });
    const after = build(source, { activeCell: editableCellKey('row-0-1', 'name') });
    expect(shouldEditableCellUpdate(after[0], before[0])).toBe(true);
    expect(shouldEditableCellUpdate(after[1], before[1])).toBe(true);
  });
});
