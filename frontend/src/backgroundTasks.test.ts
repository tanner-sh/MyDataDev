import { describe, expect, it } from 'vitest';
import {
  backgroundTaskCompletionMessage,
  backgroundTaskDestinations,
  backgroundTaskLabel,
  EMPTY_BACKGROUND_TASK_SUMMARY,
  sameBackgroundTaskSummary,
  summarizeBackgroundTasks
} from './backgroundTasks';

function summary(backups: number, restores: number, sqlFiles: number) {
  return { backups, restores, sqlFiles, exports: 0, total: backups + restores + sqlFiles };
}

describe('summarizeBackgroundTasks', () => {
  it('counts every kind of in-flight job', () => {
    expect(summarizeBackgroundTasks({
      backups: [{ id: 1 }, { id: 2 }],
      restores: [{ id: 3 }],
      sqlFiles: []
    } as never)).toEqual(summary(2, 1, 0));
  });

  it('tolerates a response without the sqlFiles field', () => {
    expect(summarizeBackgroundTasks({ backups: [], restores: [] } as never)).toEqual(EMPTY_BACKGROUND_TASK_SUMMARY);
    expect(summarizeBackgroundTasks(null)).toEqual(EMPTY_BACKGROUND_TASK_SUMMARY);
  });
});

describe('sameBackgroundTaskSummary', () => {
  it('compares counts rather than object identity', () => {
    expect(sameBackgroundTaskSummary(summary(1, 0, 2), summary(1, 0, 2))).toBe(true);
    expect(sameBackgroundTaskSummary(summary(1, 0, 2), summary(1, 1, 2))).toBe(false);
  });
});

describe('backgroundTaskLabel', () => {
  it('lists only the kinds that have work in flight', () => {
    expect(backgroundTaskLabel(summary(2, 0, 1))).toBe('2 个备份任务、1 个 SQL 文件任务进行中');
    expect(backgroundTaskLabel(EMPTY_BACKGROUND_TASK_SUMMARY)).toBe('没有进行中的后台任务');
  });
});

describe('backgroundTaskCompletionMessage', () => {
  it('reports the kinds that stopped', () => {
    expect(backgroundTaskCompletionMessage(summary(1, 0, 0), summary(0, 0, 0)))
      .toBe('1 个备份任务已结束，可在管理中的备份与恢复或定时导出中查看结果。');
    expect(backgroundTaskCompletionMessage(summary(2, 1, 0), summary(1, 1, 0)))
      .toBe('1 个备份任务已结束。');
  });

  it('stays quiet when nothing finished', () => {
    expect(backgroundTaskCompletionMessage(summary(0, 0, 0), summary(1, 0, 0))).toBeUndefined();
    expect(backgroundTaskCompletionMessage(summary(1, 0, 0), summary(1, 0, 0))).toBeUndefined();
  });
});

describe('backgroundTaskDestinations', () => {
  it.each(['backups', 'restores', 'sqlFiles', 'exports'] as const)('offers the actual task kind for %s', kind => {
    const destinations = backgroundTaskDestinations({ ...EMPTY_BACKGROUND_TASK_SUMMARY, [kind]: 1, total: 1 });
    expect(destinations).toHaveLength(1);
    expect(destinations[0].key).toBe(kind);
  });

  it('keeps all running kinds accessible when exports and other jobs coexist', () => {
    expect(backgroundTaskDestinations({ backups: 2, restores: 1, sqlFiles: 3, exports: 1, total: 7 })).toEqual([
      { key: 'backups', label: '2 个备份任务' },
      { key: 'restores', label: '1 个恢复任务' },
      { key: 'exports', label: '1 个导出任务' },
      { key: 'sqlFiles', label: '3 个 SQL 文件任务' }
    ]);
  });

  it('omits completed jobs and tolerates older summaries without exports', () => {
    expect(backgroundTaskDestinations(EMPTY_BACKGROUND_TASK_SUMMARY)).toEqual([]);
    expect(backgroundTaskDestinations({ backups: 0, restores: 0, sqlFiles: 1, total: 1 }))
      .toEqual([{ key: 'sqlFiles', label: '1 个 SQL 文件任务' }]);
  });
});
