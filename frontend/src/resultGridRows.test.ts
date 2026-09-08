import { describe, expect, it } from 'vitest';
import { buildResultRows, reconcileResultRowEdits } from './resultGridRows';
import { applyResultCellEdit, EMPTY_RESULT_EDIT_STATE } from './resultEditing';

describe('结果表格增量编辑', () => {
  const values = [[1, 'A'], [2, 'B'], [3, 'C']];
  it('进入编辑和切换编辑行时保留其余记录，且不改变用于滚动的基础行', () => {
    const base = buildResultRows(values, values, 500);
    const first = reconcileResultRowEdits(base, EMPTY_RESULT_EDIT_STATE, '1:NAME', []);
    expect(first[0]).toBe(base[0]);
    expect(first[1]).not.toBe(base[1]);
    expect(first[2]).toBe(base[2]);
    const next = reconcileResultRowEdits(base, EMPTY_RESULT_EDIT_STATE, '2:NAME', first);
    expect(next[0]).toBe(first[0]);
    expect(next[1]).toBe(base[1]);
    expect(next[2].editingColumn).toBe('NAME');
    expect(base.every((row) => row.editingColumn === undefined)).toBe(true);
  });
  it('保留已有修改行的引用，直到该行的值再次变化或撤销', () => {
    const base = buildResultRows(values, values, 0);
    const state = applyResultCellEdit(EMPTY_RESULT_EDIT_STATE, 0, 'NAME', 'new', 'A');
    const first = reconcileResultRowEdits(base, state, null, []);
    const more = applyResultCellEdit(state, 2, 'NAME', 'third', 'C');
    const next = reconcileResultRowEdits(base, more, null, first);
    expect(next[0]).toBe(first[0]);
    expect(next[1]).toBe(base[1]);
    expect(next[2]).not.toBe(first[2]);
    expect(next[0].edits).toEqual({ NAME: 'new' });
    expect(reconcileResultRowEdits(base, EMPTY_RESULT_EDIT_STATE, null, next)).toEqual(base);
  });
  it('排序筛选后仍按原始行号编辑，翻页不复用旧值', () => {
    const base = buildResultRows([values[2], values[0]], values, 500);
    const state = applyResultCellEdit(EMPTY_RESULT_EDIT_STATE, 2, 'NAME:LABEL', 'new', 'C');
    const edited = reconcileResultRowEdits(base, state, '2:NAME:LABEL', []);
    expect(edited[0]).toMatchObject({ key: '502', rowIndex: 2, editingColumn: 'NAME:LABEL', edits: { 'NAME:LABEL': 'new' } });
    const replacement = [[4, 'D']];
    const next = buildResultRows(replacement, replacement, 1000);
    expect(reconcileResultRowEdits(next, EMPTY_RESULT_EDIT_STATE, null, edited)[0]).toBe(next[0]);
  });
});
