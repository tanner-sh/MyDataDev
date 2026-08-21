import { describe, expect, it } from 'vitest';
import {
  backgroundTaskCompletionMessage,
  backgroundTaskLabel,
  EMPTY_BACKGROUND_TASK_SUMMARY,
  sameBackgroundTaskSummary,
  summarizeBackgroundTasks
} from './backgroundTasks';

function summary(backups: number, restores: number, sqlFiles: number) {
  return { backups, restores, sqlFiles, total: backups + restores + sqlFiles };
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
      .toBe('1 个备份任务已结束，可在备份与恢复中查看结果。');
    expect(backgroundTaskCompletionMessage(summary(2, 1, 0), summary(1, 1, 0)))
      .toBe('1 个备份任务已结束。');
  });

  it('stays quiet when nothing finished', () => {
    expect(backgroundTaskCompletionMessage(summary(0, 0, 0), summary(1, 0, 0))).toBeUndefined();
    expect(backgroundTaskCompletionMessage(summary(1, 0, 0), summary(1, 0, 0))).toBeUndefined();
  });
});
