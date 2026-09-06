import type { TableColumn, TableRow } from './types';
export type RowHistory = { past: TableRow[][]; present: TableRow[]; future: TableRow[][] };
export type RowHistoryAction = { type: 'reset' | 'edit'; value: TableRow[] | ((rows: TableRow[]) => TableRow[]) } | { type: 'undo' } | { type: 'redo' } | { type: 'restore'; value: RowHistory };
export const EMPTY_ROW_HISTORY: RowHistory = { past: [], present: [], future: [] };
export function rowHistoryReducer(state: RowHistory, action: RowHistoryAction): RowHistory {
  if (action.type === 'restore') return action.value;
  if (action.type === 'undo') return state.past.length ? { past: state.past.slice(0, -1), present: state.past[state.past.length - 1], future: [state.present, ...state.future] } : state;
  if (action.type === 'redo') return state.future.length ? { past: [...state.past, state.present], present: state.future[0], future: state.future.slice(1) } : state;
  const present = typeof action.value === 'function' ? action.value(state.present) : action.value;
  if (action.type === 'reset') return { past: [], present, future: [] };
  return present === state.present ? state : { past: [...state.past.slice(-49), state.present], present, future: [] };
}

export function cellValidation(column: TableColumn, value: unknown): string | undefined {
  if (value === undefined) return undefined;
  if (value === null) return column.nullable ? undefined : `${column.name} 不允许为空`;
  const text = String(value).trim();
  if (/json/i.test(column.typeName)) {
    try { JSON.parse(text); } catch { return `${column.name} 需要合法的 JSON`; }
  } else if ([-6, 5, 4, -5].includes(column.jdbcType)) {
    if (!/^[+-]?\d+$/.test(text)) return `${column.name} 需要整数`;
  } else if ([2, 3, 6, 7, 8].includes(column.jdbcType)) {
    if (!/^[+-]?(?:\d+\.?\d*|\.\d+)(?:e[+-]?\d+)?$/i.test(text)) return `${column.name} 需要有效数字`;
  } else if ([16, -7].includes(column.jdbcType)) {
    if (!/^(true|false|0|1)$/i.test(text)) return `${column.name} 只接受 true、false、0 或 1`;
  } else if (column.jdbcType === 91) {
    const date = /^\d{4}-\d{2}-\d{2}$/.test(text) ? new Date(`${text}T00:00:00Z`) : undefined;
    if (!date || !Number.isFinite(date.getTime()) || date.toISOString().slice(0, 10) !== text) return `${column.name} 需要有效日期 YYYY-MM-DD`;
  }
  return undefined;
}

/** 更新乐观校验基线，保留用户修改；后续仍需手动提交，并接受新一轮并发检查。 */
export function rebaseConflictRow(row: TableRow, current: Record<string, unknown>, keepMine: boolean): TableRow {
  return { ...row, original: { ...row.original, ...current }, values: keepMine ? row.values : { ...row.values, ...current }, deleted: keepMine && Boolean(row.deleted) };
}
