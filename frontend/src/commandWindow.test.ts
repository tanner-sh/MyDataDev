import { describe, expect, it } from 'vitest';
import { commandLogText, commandOptionsLabel, commandProgressText, effectiveCommandOptions, isTerminalJob, lineRangeOffsets, mergeCommandLog } from './commandWindow';
import type { SqlCommandLogEntry, SqlFileExecution } from './types';

const entry = (seq: number, kind: SqlCommandLogEntry['kind'] = 'RESULT', text = `r${seq}`): SqlCommandLogEntry => ({ seq, kind, text });

describe('command window', () => {
  it('falls back to autocommit where script transactions are not available', () => {
    const script = { onError: 'CONTINUE' as const, transactionMode: 'SCRIPT' as const, endOfFileAction: 'COMMIT' as const };
    expect(effectiveCommandOptions(script, 'oceanbase-oracle')).toEqual(script);
    expect(effectiveCommandOptions(script, 'mysql').transactionMode).toBe('AUTOCOMMIT');
    expect(effectiveCommandOptions(undefined, 'h2')).toEqual({ onError: 'STOP', transactionMode: 'AUTOCOMMIT', endOfFileAction: 'ROLLBACK' });
    expect(commandOptionsLabel(script)).toBe('脚本控制事务，结束时提交 · 遇错继续');
  });

  it('merges overlapping polls without duplicates and keeps the newest lines', () => {
    const first = mergeCommandLog([], [entry(1), entry(2)]);
    expect(mergeCommandLog(first.entries, [entry(2), entry(3)]).entries.map((item) => item.seq)).toEqual([1, 2, 3]);
    const capped = mergeCommandLog([entry(1), entry(2)], [entry(3), entry(4)], 3);
    expect(capped.entries.map((item) => item.seq)).toEqual([2, 3, 4]);
    expect(capped.dropped).toBe(1);
  });

  it('turns script line numbers into editor offsets', () => {
    const text = 'select 1;\ninsert into t\n  values (1);\nselect 2;';
    expect(lineRangeOffsets(text, 2, 3)).toEqual({ start: 10, end: 37 });
    expect(text.slice(10, 37)).toBe('insert into t\n  values (1);');
    expect(lineRangeOffsets(text, 4, 4, 100)).toEqual({ start: 138, end: 147 });
    expect(lineRangeOffsets(text, 99, 99)).toEqual({ start: 38, end: 47 });
  });

  it('describes progress and outcome in one line', () => {
    const job = { status: 'RUNNING', statementCurrent: 12, statementTotal: 277, successCount: 11 } as SqlFileExecution;
    expect(commandProgressText(job, 1)).toBe('执行中 12 / 277 · 失败 1');
    expect(commandProgressText({ ...job, status: 'SUCCESS', successCount: 276 }, 1)).toBe('已完成（有失败） · 成功 276 / 277 · 失败 1');
    expect(commandProgressText({ ...job, status: 'FAILED' }, 1)).toBe('已停止 · 成功 11 / 277 · 失败 1');
    expect(isTerminalJob({ status: 'RUNNING' })).toBe(false);
    expect(isTerminalJob({ status: 'CANCELLED' })).toBe(true);
  });

  it('copies the log in a SQL*Plus-like shape', () => {
    expect(commandLogText([
      { seq: 1, kind: 'INFO', text: '开始执行' },
      { seq: 2, kind: 'STATEMENT', text: 'INSERT INTO t VALUES (1)' },
      { seq: 3, kind: 'RESULT', text: '已插入 1 行。' },
      { seq: 4, kind: 'ERROR', text: 'ORA-00942', startLine: 3, endLine: 4 }
    ])).toBe('-- 开始执行\nSQL> INSERT INTO t VALUES (1)\n已插入 1 行。\n错误（第 3–4 行）：ORA-00942');
  });
});
