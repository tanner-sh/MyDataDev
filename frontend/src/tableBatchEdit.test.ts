import { describe, expect, it } from 'vitest';
import {
  batchValueModes,
  canBatchEditColumn,
  duplicateRowsAsInserts,
  liveSelection,
  markRowsDeleted,
  selectedRowsCopyText,
  setColumnForRows,
  summarizeSelection
} from './tableBatchEdit';
import type { TableColumn, TableRow } from './types';

const columns: TableColumn[] = [
  { name: 'ID', typeName: 'NUMBER', jdbcType: 2, nullable: false },
  { name: 'NAME', typeName: 'VARCHAR2', jdbcType: 12, nullable: true },
  { name: 'NOTE', typeName: 'CLOB', jdbcType: 2005, nullable: true, truncated: true }
];

const stored = (id: string, values: Record<string, unknown>, extra: Partial<TableRow> = {}): TableRow =>
  ({ id, values: { ...values }, original: { ...values }, keyToken: `k-${id}`, ...extra });

const rows: TableRow[] = [
  stored('a', { ID: 1, NAME: 'x', NOTE: 'n' }),
  stored('b', { ID: 2, NAME: 'y', NOTE: null }, { deleted: true }),
  { id: 'new', values: { NAME: 'z' }, touchedColumns: ['NAME'], inserted: true },
  stored('c', { ID: 3, NAME: null, NOTE: null })
];

describe('table batch edit', () => {
  it('only offers NULL for nullable columns and empty string for text columns', () => {
    expect(batchValueModes(columns[0])).toEqual({ value: undefined, null: 'ID 不允许为空', empty: 'ID 不是字符类型' });
    expect(batchValueModes(columns[1])).toEqual({ value: undefined, null: undefined, empty: undefined });
    expect(batchValueModes(columns[2]).empty).toBeUndefined();
    expect(batchValueModes({ name: 'CODE', typeName: 'nvarchar', jdbcType: -9, nullable: false }))
      .toEqual({ value: undefined, null: 'CODE 不允许为空', empty: undefined });
    expect(batchValueModes(undefined).null).toBeUndefined();
  });

  it('drops selections that are no longer on screen', () => {
    expect(liveSelection(rows, ['a', 'gone', 'new'])).toEqual(['a', 'new']);
    expect(summarizeSelection(rows, ['a', 'b', 'new'])).toEqual({ total: 3, deleted: 1, inserted: 1 });
  });

  it('marks every selected row deleted instead of toggling, and removes inserted rows', () => {
    const next = markRowsDeleted(rows, ['a', 'b', 'new'], true);
    expect(next.map((row) => row.id)).toEqual(['a', 'b', 'c']);
    expect(next.find((row) => row.id === 'a')?.deleted).toBe(true);
    expect(next.find((row) => row.id === 'b')).toBe(rows[1]);
    const restored = markRowsDeleted(rows, ['b', 'new'], false);
    expect(restored.find((row) => row.id === 'b')?.deleted).toBe(false);
    expect(restored.some((row) => row.id === 'new')).toBe(true);
  });

  it('sets a column on selected rows, skipping deleted rows and marking inserted rows touched', () => {
    const result = setColumnForRows(rows, ['a', 'b', 'new'], 'NAME', null, { editable: true });
    expect(result.changed).toBe(2);
    expect(result.skipped).toBe(1);
    expect(result.rows[0].values.NAME).toBeNull();
    expect(result.rows[1]).toBe(rows[1]);
    expect(result.rows[2].touchedColumns).toEqual(['NAME']);
    const idOnly = setColumnForRows(rows, ['new'], 'ID', '9', { editable: true });
    expect(idOnly.rows[2].touchedColumns).toEqual(['NAME', 'ID']);
  });

  it('only edits inserted rows when the table has no row locator', () => {
    const result = setColumnForRows(rows, ['a', 'c', 'new'], 'NAME', 'q', { editable: false });
    expect(result.changed).toBe(1);
    expect(result.skipped).toBe(2);
    const untouched = setColumnForRows(rows, ['a'], 'NAME', 'q', { editable: false });
    expect(untouched.rows).toBe(rows);
  });

  it('refuses truncated and key columns for batch assignment', () => {
    expect(canBatchEditColumn(columns[1])).toBe(true);
    expect(canBatchEditColumn(columns[2])).toBe(false);
    expect(canBatchEditColumn({ ...columns[1], editable: false })).toBe(false);
    expect(canBatchEditColumn(columns[0], ['ID'])).toBe(false);
  });

  it('duplicates rows as inserts without key or truncated columns', () => {
    const { rows: next, added } = duplicateRowsAsInserts(rows, ['a', 'b', 'new'], columns, ['ID'], (index) => `dup-${index}`);
    expect(added.map((row) => row.id)).toEqual(['dup-0', 'dup-1']);
    expect(added[0]).toEqual({ id: 'dup-0', values: { NAME: 'x' }, touchedColumns: ['NAME'], inserted: true });
    expect(added[1].values).toEqual({ NAME: 'z' });
    expect(next.slice(0, 2)).toEqual(added);
    expect(next).toHaveLength(rows.length + 2);
  });

  it('copies selected rows as pipe text with a header, or as one INSERT per row', () => {
    expect(selectedRowsCopyText('pipe', columns.slice(0, 2), rows, ['c', 'a'])).toBe('ID|NAME\n1|x\n3|NULL');
    const sql = selectedRowsCopyText('sql', columns.slice(0, 2), rows, ['a', 'new'], { dbType: 'oracle', targetTableParts: ['S', 'T'] });
    const lines = sql.split('\n');
    expect(lines).toHaveLength(2);
    expect(lines[0]).toMatch(/^INSERT INTO .*"ID", "NAME"/);
    expect(lines[1]).toMatch(/\("NAME"\) VALUES \('z'\)/);
    expect(() => selectedRowsCopyText('pipe', columns, rows, [])).toThrow('没有选中任何行');
  });
});
