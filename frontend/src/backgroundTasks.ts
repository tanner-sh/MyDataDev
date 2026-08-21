import type { ActiveOperations } from './types';

/**
 * Counts of the background jobs still in flight for the selected connection.
 *
 * The header indicator needs nothing but these numbers, which is why
 * `/restores/operations/active` returns all three kinds in one response: a
 * backup, restore or SQL-file task started from a drawer keeps running after
 * the drawer is closed, and scheduled backups start with no drawer open at all.
 */
export type BackgroundTaskSummary = {
  backups: number;
  restores: number;
  sqlFiles: number;
  total: number;
};

export const EMPTY_BACKGROUND_TASK_SUMMARY: BackgroundTaskSummary = {
  backups: 0,
  restores: 0,
  sqlFiles: 0,
  total: 0
};

export function summarizeBackgroundTasks(operations: Partial<ActiveOperations> | null | undefined): BackgroundTaskSummary {
  const backups = operations?.backups?.length || 0;
  const restores = operations?.restores?.length || 0;
  const sqlFiles = operations?.sqlFiles?.length || 0;
  return { backups, restores, sqlFiles, total: backups + restores + sqlFiles };
}

export function sameBackgroundTaskSummary(left: BackgroundTaskSummary, right: BackgroundTaskSummary) {
  return left.backups === right.backups
    && left.restores === right.restores
    && left.sqlFiles === right.sqlFiles;
}

// Each kind carries its finished phrase rather than a bare noun, so Chinese
// text keeps its space before a Latin word ("1 个 SQL 文件任务").
const KIND_PHRASES = [
  { key: 'backups', phrase: (count: number) => `${count} 个备份任务` },
  { key: 'restores', phrase: (count: number) => `${count} 个恢复任务` },
  { key: 'sqlFiles', phrase: (count: number) => `${count} 个 SQL 文件任务` }
] as const;

/** Tooltip text for the header indicator, e.g. "2 个备份任务、1 个 SQL 文件任务进行中". */
export function backgroundTaskLabel(summary: BackgroundTaskSummary) {
  const parts = KIND_PHRASES
    .filter((kind) => summary[kind.key] > 0)
    .map((kind) => kind.phrase(summary[kind.key]));
  return parts.length === 0 ? '没有进行中的后台任务' : `${parts.join('、')}进行中`;
}

/**
 * Announces jobs that left the active set since the previous poll.
 *
 * A job leaving that set only means it stopped — it may have succeeded, failed
 * or been cancelled — so the wording stays at "已结束" and points the user at
 * the drawer for the outcome.
 */
export function backgroundTaskCompletionMessage(
  previous: BackgroundTaskSummary,
  next: BackgroundTaskSummary
): string | undefined {
  const finished = KIND_PHRASES
    .map((kind) => ({ phrase: kind.phrase, count: previous[kind.key] - next[kind.key] }))
    .filter((kind) => kind.count > 0);
  if (finished.length === 0) return undefined;
  const detail = finished.map((kind) => kind.phrase(kind.count)).join('、');
  return next.total === 0
    ? `${detail}已结束，可在备份与恢复中查看结果。`
    : `${detail}已结束。`;
}
