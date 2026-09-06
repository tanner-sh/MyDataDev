import { useCallback, useReducer, type SetStateAction } from 'react';
import { EMPTY_ROW_HISTORY, rowHistoryReducer, type RowHistory } from '../tableEditing';
import type { TableRow } from '../types';
export function useEditableRows() {
  const [history, dispatch] = useReducer(rowHistoryReducer, EMPTY_ROW_HISTORY);
  const setRows = useCallback((value: SetStateAction<TableRow[]>) => dispatch({ type: 'reset', value }), []);
  const editRows = useCallback((value: SetStateAction<TableRow[]>) => dispatch({ type: 'edit', value }), []);
  const restore = useCallback((value: RowHistory) => dispatch({ type: 'restore', value }), []);
  return { rows: history.present, history, setRows, editRows, restore, undo: () => dispatch({ type: 'undo' }), redo: () => dispatch({ type: 'redo' }) };
}
