import { describe, expect, it } from 'vitest';
import { tableCellMenuItems, tableRowCopyText } from './tableCellMenu';
import type { TableColumn } from './types';

const columns: TableColumn[] = [
  { name: 'ID', typeName: 'NUMBER', jdbcType: 2, nullable: false },
  { name: 'NAME', typeName: 'VARCHAR2', jdbcType: 12, nullable: true },
  { name: 'NOTE', typeName: 'VARCHAR2', jdbcType: 12, nullable: true }
];

const actions = (items: ReturnType<typeof tableCellMenuItems>) => items.map((item) => item.type === 'divider' ? '---' : item.action);

describe('table cell menu', () => {
  it('always offers copying, even on read-only connections', () => {
    expect(actions(tableCellMenuItems({ editable: false, isDefault: false, displayKind: 'value', canInsert: true })))
      .toEqual(['copy', 'copy-row', 'copy-row-insert', 'copy-column-name']);
  });

  it('adds the NULL / empty-string entries for editable cells, with the Oracle caveat', () => {
    const items = tableCellMenuItems({ editable: true, isDefault: false, displayKind: 'null', dbType: 'oceanbase-oracle', canInsert: true });
    expect(actions(items)).toEqual(['copy', 'copy-row', 'copy-row-insert', 'copy-column-name', '---', 'set-null', 'set-empty']);
    const setNull = items.find((item) => item.type !== 'divider' && item.action === 'set-null');
    const setEmpty = items.find((item) => item.type !== 'divider' && item.action === 'set-empty');
    expect(setNull).toMatchObject({ disabled: true });
    expect(setEmpty).toMatchObject({ hint: expect.stringContaining('NULL') });
  });

  it('disables copying a DEFAULT cell and INSERT without a target table', () => {
    const items = tableCellMenuItems({ editable: false, isDefault: true, displayKind: 'null', canInsert: false });
    expect(items[0]).toMatchObject({ action: 'copy', disabled: true });
    expect(items[2]).toMatchObject({ action: 'copy-row-insert', disabled: true });
  });

  it('copies a row in the same pipe format as the result grid', () => {
    expect(tableRowCopyText('pipe', columns, { values: { ID: 1, NAME: 'A|B', NOTE: null } })).toBe('1|A\\|B|NULL');
  });

  it('copies a row as an INSERT against the qualified table', () => {
    expect(tableRowCopyText('sql', columns, { values: { ID: 7, NAME: "O'Neil", NOTE: null } }, { dbType: 'oracle', targetTableParts: ['APP', 'T_USER'] }))
      .toBe(`INSERT INTO "APP"."T_USER" ("ID", "NAME", "NOTE") VALUES (7, 'O''Neil', NULL);`);
  });

  it('leaves untouched columns of an inserted row to the database default', () => {
    expect(tableRowCopyText('sql', columns, { values: { ID: 9, NAME: undefined, NOTE: 'x' }, inserted: true, touchedColumns: ['ID', 'NOTE'] }, { dbType: 'oracle', targetTableParts: ['T'] }))
      .toBe(`INSERT INTO "T" ("ID", "NOTE") VALUES (9, 'x');`);
    expect(() => tableRowCopyText('sql', columns, { values: {}, inserted: true, touchedColumns: [] }, { targetTableParts: ['T'] })).toThrow();
  });
});
