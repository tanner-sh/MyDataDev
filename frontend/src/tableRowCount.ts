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
  | { status: 'failed' };

export const IDLE_TABLE_ROW_COUNT: TableRowCountState = { status: 'idle' };

export function formatRowCount(total: number) {
  return total.toLocaleString('zh-CN');
}

export function tableRowCountLabel(state: TableRowCountState): string | undefined {
  if (state.status === 'loading') return '正在统计总行数…';
  if (state.status === 'ready') return `共 ${formatRowCount(state.total)} 行`;
  if (state.status === 'failed') return '总行数统计失败';
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
