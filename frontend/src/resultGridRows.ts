import type { ResultRow } from './types';
import type { ResultEditState } from './resultEditing';

/** 排序、筛选后的记录仍使用原始行号，编辑状态不能改变这一份行顺序。 */
export function buildResultRows(visibleRows: unknown[][], originalRows: unknown[][], offset: number): ResultRow[] {
  const indexes = new Map(originalRows.map((values, index) => [values, index]));
  return visibleRows.map((values) => {
    const rowIndex = indexes.get(values) ?? 0;
    return { values, key: String(offset + rowIndex), rowIndex };
  });
}

/** 只替换编辑内容或编辑焦点变化的行，让虚拟表的 shouldCellUpdate 保留作用。 */
export function reconcileResultRowEdits(
  rows: ResultRow[], state: ResultEditState, editingCell: string | null, previous: ResultRow[]
): ResultRow[] {
  const byRow = new Map<number, Record<string, unknown>>();
  for (const edit of Object.values(state.edits)) {
    const values = byRow.get(edit.rowIndex) ?? {};
    values[edit.column] = edit.value;
    byRow.set(edit.rowIndex, values);
  }
  const oldRows = new Map(previous.map((row) => [row.rowIndex, row]));
  return rows.map((row) => {
    const edits = byRow.get(row.rowIndex);
    const prefix = `${row.rowIndex}:`;
    const editingColumn = editingCell?.startsWith(prefix) ? editingCell.slice(prefix.length) : undefined;
    if (!edits && editingColumn === undefined) return row;
    const old = oldRows.get(row.rowIndex);
    if (old && old.values === row.values && old.key === row.key && old.editingColumn === editingColumn
      && sameEdits(old.edits, edits)) return old;
    return { ...row, edits, editingColumn };
  });
}

function sameEdits(left: ResultRow['edits'], right: ResultRow['edits']) {
  if (left === right) return true;
  if (!left || !right) return false;
  const keys = Object.keys(left);
  return keys.length === Object.keys(right).length
    && keys.every((key) => Object.prototype.hasOwnProperty.call(right, key) && Object.is(left[key], right[key]));
}
