import { describe, expect, it } from 'vitest';
import { buildChanges, localizeError, localizeMessage, sameCellValue, summarizeRowChanges } from './utils';
import type { TableRow } from './types';

describe('error localization', () => {
  it('prefers the backend error code over the raw message', () => {
    expect(localizeMessage('Communications link failure', 'TARGET_DATABASE_UNAVAILABLE'))
      .toBe('无法连接目标数据库，请检查数据库状态与网络后重试。');
  });

  /*
    INTERNAL_ERROR 刻意不在映射表里。后端只有 ApiExceptionHandler.generic() 一处产出这个码，
    给的是一句中文加上一个错误编号，而那个编号是去服务端日志里搜堆栈唯一的线索 —— 以前被
    映射表里同样开头但没有编号的那一句整句盖掉了。
  */
  it('keeps the trace id the backend attaches to an internal error', () => {
    expect(localizeMessage('服务器内部错误，请稍后重试。如需反馈请提供错误编号 a1b2c3d4。', 'INTERNAL_ERROR'))
      .toContain('a1b2c3d4');
  });

  it('falls back to legacy substring matching when no code is present', () => {
    expect(localizeMessage('Connection not found: 3')).toBe('未找到数据库连接。');
  });

  it('reads the code off a thrown ApiError', () => {
    const error = Object.assign(new Error('Connection is referenced by 2 backup task(s).'), {
      code: 'CONNECTION_HAS_BACKUP_TASKS'
    });
    expect(localizeError(error))
      .toBe('该连接存在关联备份任务，请先切换到“备份任务”删除相关任务后再删除连接。');
  });

  it('keeps an unmapped message rather than losing it', () => {
    expect(localizeError(new Error('Unknown column \'nope\' in \'field list\'')))
      .toBe('Unknown column \'nope\' in \'field list\'');
  });
});

describe('table data changes', () => {
  it('preserves NULL and empty strings while omitting untouched database defaults', () => {
    const rows: TableRow[] = [{
      id: 'new-1',
      inserted: true,
      values: { generated_id: 100, nullable_note: null, empty_note: '' },
      touchedColumns: ['nullable_note', 'empty_note']
    }];

    expect(buildChanges(rows, ['generated_id'])).toEqual([{
      type: 'INSERT',
      values: { nullable_note: null, empty_note: '' }
    }]);
  });

  it('sends only changed values with optimistic original predicates', () => {
    const rows: TableRow[] = [{
      id: 'row-1',
      keyToken: 'signed-row-token',
      original: { id: 1, amount: 10, note: null },
      values: { id: 1, amount: '20', note: '' }
    }];

    expect(buildChanges(rows, ['id'])).toEqual([{
      type: 'UPDATE',
      keyToken: 'signed-row-token',
      values: { amount: '20', note: '' },
      originalValues: { amount: 10, note: null }
    }]);
  });

  it('does not confuse numeric display strings with changed numeric values', () => {
    expect(sameCellValue(10, '10')).toBe(true);
    expect(sameCellValue(null, '')).toBe(false);
  });
});

describe('summarizeRowChanges', () => {
  it('counts each kind in one pass', () => {
    expect(summarizeRowChanges([
      { type: 'INSERT', values: { a: 1 } },
      { type: 'UPDATE', keyToken: 't', values: { a: 2 } },
      { type: 'UPDATE', keyToken: 'u', values: { a: 3 } },
      { type: 'DELETE', keyToken: 'v' }
    ])).toEqual({ inserts: 1, updates: 2, deletes: 1 });
  });

  it('returns zeroes for an empty change list', () => {
    expect(summarizeRowChanges([])).toEqual({ inserts: 0, updates: 0, deletes: 0 });
  });
});
