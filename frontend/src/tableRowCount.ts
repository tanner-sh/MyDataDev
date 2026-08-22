/**
 * State of the optional total-row count in the table workspace.
 *
 * Keyset pagination cannot know the total, and `SELECT COUNT(*)` is a full scan
 * on many engines — the backend caps it at a 15 second timeout. So the count is
 * requested explicitly rather than fetched with every page.
 */
export type TableRowCountState =
  | { status: 'idle' }
  | { status: 'loading' }
  | { status: 'ready'; total: number; elapsedMs: number }
  | { status: 'failed'; reason?: 'timeout' };

/** 后端在统计超时时返回的错误码。 */
export const ROW_COUNT_TIMEOUT_CODE = 'ROW_COUNT_TIMEOUT';

export const IDLE_TABLE_ROW_COUNT: TableRowCountState = { status: 'idle' };

export function formatRowCount(total: number) {
  return total.toLocaleString('zh-CN');
}

export function tableRowCountLabel(state: TableRowCountState): string | undefined {
  if (state.status === 'loading') return '正在统计总行数…';
  if (state.status === 'ready') return `共 ${formatRowCount(state.total)} 行`;
  if (state.status === 'failed') {
    return state.reason === 'timeout' ? '总行数统计超时' : '总行数统计失败';
  }
  return undefined;
}

export function tablePageSummary(page: number, pageRows: number, state: TableRowCountState) {
  const base = `第 ${page + 1} 页 · 本页 ${pageRows} 行`;
  const total = tableRowCountLabel(state);
  return total ? `${base} · ${total}` : base;
}

/**
 * The count reflects one moment in time, so it is only offered again once the
 * previous request has settled.
 */
export function canCountTableRows(state: TableRowCountState, hasTable: boolean, loading: boolean) {
  return hasTable && !loading && state.status !== 'loading';
}

/**
 * 统计失败时要显示的文案。
 *
 * 超时是大表上的预期结果，后端已经给出完整的中文说明；再套一层「统计总行数失败：」
 * 会读成「统计总行数失败：统计总行数超过 15 秒…」。
 */
export function rowCountErrorMessage(message: string, code?: string) {
  return code === ROW_COUNT_TIMEOUT_CODE ? message : `统计总行数失败：${message}`;
}

/** 把后端错误码翻译成失败状态，让状态栏能区分「超时」与「失败」。 */
export function rowCountFailure(code?: string): TableRowCountState {
  return code === ROW_COUNT_TIMEOUT_CODE ? { status: 'failed', reason: 'timeout' } : { status: 'failed' };
}
