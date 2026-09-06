import { describe, expect, it } from 'vitest';
import { cellValidation, EMPTY_ROW_HISTORY, rebaseConflictRow, rowHistoryReducer } from './tableEditing';
describe('table edits', () => {
  it('undoes one edit and drops redo after another edit', () => {
    const first = rowHistoryReducer(EMPTY_ROW_HISTORY, { type: 'edit', value: [{ id: '1', values: { n: 1 } }] });
    const undone = rowHistoryReducer(first, { type: 'undo' });
    expect(rowHistoryReducer(undone, { type: 'redo' }).present).toEqual(first.present);
    expect(rowHistoryReducer(undone, { type: 'edit', value: [] }).future).toEqual([]);
  });
  it('checks types without rounding large integer identifiers', () => {
    const column = { name: 'id', typeName: 'BIGINT', jdbcType: -5, nullable: false };
    expect(cellValidation(column, '9223372036854775807')).toBeUndefined();
    expect(cellValidation(column, '1.5')).toContain('整数');
    expect(cellValidation(column, null)).toContain('为空');
    expect(cellValidation({ ...column, typeName: 'JSON', jdbcType: 12 }, '{bad')).toContain('JSON');
    expect(cellValidation({ ...column, jdbcType: 91 }, '2025-02-29')).toContain('日期');
  });
  it('keeps the user edit while rebasing its concurrency check', () => {
    const row = { id: '1', values: { amount: 12, label: 'x' }, original: { amount: 10, label: 'x' }, keyToken: 'signed' };
    expect(rebaseConflictRow(row, { amount: 11 }, true)).toEqual({ ...row, original: { amount: 11, label: 'x' }, deleted: false });
    expect(rebaseConflictRow(row, { amount: 11 }, false).values.amount).toBe(11);
    expect(rebaseConflictRow({ ...row, deleted: true }, { amount: 11 }, true).deleted).toBe(true);
    expect(rebaseConflictRow({ ...row, deleted: true }, { amount: 11 }, false).deleted).toBe(false);
  });
});
