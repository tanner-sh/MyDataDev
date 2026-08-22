/**
 * 查询结果的就地编辑。
 *
 * 表数据工作区早就能改单元格，SQL 查询结果却一律只读 —— 两者的差别只在于「这段结果是不是
 * 来自单张表、那张表有没有稳定行定位字段、这些字段在不在结果集里」。三条都成立时后端会随
 * 结果下发行定位令牌，用的就是同一套令牌和同一个提交接口（/data/preview、/data/commit）。
 */

import type { RowChange } from './types';
import { sameCellValue } from './utils';

export type ResultEditInfo = {
  editable: boolean;
  schemaName?: string | null;
  tableName?: string | null;
  keyColumns: string[];
  rowKeyTokens: string[];
  reason?: string | null;
};

/** 一次未提交的单元格修改：行在本批结果里的下标 + 列名 + 新值。 */
export type ResultCellEdit = { rowIndex: number; column: string; value: unknown };

export type ResultEditState = {
  /** 用 `行下标:列名` 作键，同一个格子改多次只保留最后一次。 */
  edits: Record<string, ResultCellEdit>;
};

export const EMPTY_RESULT_EDIT_STATE: ResultEditState = { edits: {} };

export function resultEditKey(rowIndex: number, column: string): string {
  return `${rowIndex}:${column}`;
}

export function isResultEditable(edit?: ResultEditInfo | null): boolean {
  return Boolean(edit?.editable && edit.rowKeyTokens.length > 0);
}

/** 行定位字段本身不允许改：改了就没法再定位这一行。 */
export function isResultColumnEditable(edit: ResultEditInfo | null | undefined, column: string): boolean {
  if (!edit?.editable) return false;
  return !edit.keyColumns.some((key) => key.toLowerCase() === column.toLowerCase());
}

export function applyResultCellEdit(
  state: ResultEditState,
  rowIndex: number,
  column: string,
  value: unknown,
  originalValue: unknown
): ResultEditState {
  const key = resultEditKey(rowIndex, column);
  const edits = { ...state.edits };
  // 改回原值等于没改，直接把这条记录去掉，避免提交里出现空更新。
  if (sameCellValue(originalValue, value)) delete edits[key];
  else edits[key] = { rowIndex, column, value };
  return { edits };
}

export function resultEditedValue(state: ResultEditState, rowIndex: number, column: string): ResultCellEdit | undefined {
  return state.edits[resultEditKey(rowIndex, column)];
}

/** 某一行的全部未提交修改，折到记录上供表格的 shouldCellUpdate 比较。 */
export function resultRowEdits(state: ResultEditState, rowIndex: number): Record<string, unknown> | undefined {
  let result: Record<string, unknown> | undefined;
  for (const edit of Object.values(state.edits)) {
    if (edit.rowIndex !== rowIndex) continue;
    result = result || {};
    result[edit.column] = edit.value;
  }
  return result;
}

export function countResultEdits(state: ResultEditState): number {
  return Object.keys(state.edits).length;
}

export function countEditedResultRows(state: ResultEditState): number {
  return new Set(Object.values(state.edits).map((edit) => edit.rowIndex)).size;
}

/**
 * 把未提交的修改折成提交用的变更列表。
 *
 * 每一行合成一条 UPDATE，携带该行的行定位令牌与原值（后端用原值做乐观校验）。
 * 缺令牌的行会被跳过 —— 那说明结果与令牌数量不一致，宁可少提交也不能改错行。
 */
export function buildResultChanges(
  state: ResultEditState,
  rows: unknown[][],
  columns: Array<{ label: string }>,
  rowKeyTokens: string[]
): RowChange[] {
  const byRow = new Map<number, ResultCellEdit[]>();
  for (const edit of Object.values(state.edits)) {
    const bucket = byRow.get(edit.rowIndex);
    if (bucket) bucket.push(edit);
    else byRow.set(edit.rowIndex, [edit]);
  }
  const changes: RowChange[] = [];
  for (const rowIndex of [...byRow.keys()].sort((left, right) => left - right)) {
    const keyToken = rowKeyTokens[rowIndex];
    const row = rows[rowIndex];
    if (!keyToken || !row) continue;
    const values: Record<string, unknown> = {};
    const originalValues: Record<string, unknown> = {};
    for (const edit of byRow.get(rowIndex) || []) {
      const columnIndex = columns.findIndex((column) => column.label === edit.column);
      if (columnIndex < 0) continue;
      values[edit.column] = edit.value;
      originalValues[edit.column] = row[columnIndex];
    }
    if (Object.keys(values).length === 0) continue;
    changes.push({ type: 'UPDATE', keyToken, values, originalValues });
  }
  return changes;
}

/** 提交请求：复用表数据的 /data/preview 与 /data/commit。 */
export type ResultEditCommit = {
  schemaName?: string | null;
  tableName: string;
  changes: RowChange[];
};

/** 不可编辑时的解释文案，后端给了原因就用后端的。 */
export function resultEditDisabledReason(edit?: ResultEditInfo | null): string {
  if (!edit) return '当前结果不支持就地编辑';
  if (edit.reason) return edit.reason;
  if (!edit.editable) return '当前结果不支持就地编辑';
  return '当前结果没有可用的行定位令牌';
}

export function resultEditSummary(state: ResultEditState): string {
  const cells = countResultEdits(state);
  if (cells === 0) return '无待提交修改';
  return `待提交 ${cells} 处修改 · 涉及 ${countEditedResultRows(state)} 行`;
}
