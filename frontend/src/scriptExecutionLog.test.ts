import { describe, expect, it } from 'vitest';
import { buildScriptLog, hiddenResultTabCount, MAX_RESULT_TABS, needsScriptLog, resolveResultTabKey, statementSummary, summarizeScriptLog, tabbedResults } from './scriptExecutionLog';
import type { SqlStatementResult } from './types';

function statement(index: number, sql: string, kind: 'dml' | 'query' | 'failed', affected = 1): SqlStatementResult {
  return {
    index, sql, startOffset: 0, endOffset: sql.length,
    status: kind === 'failed' ? 'FAILED' : 'SUCCESS',
    errorMessage: kind === 'failed' ? 'ORA-00942: 表或视图不存在' : null,
    result: { columns: [], rows: kind === 'query' ? [[1], [2]] : [], affectedRows: kind === 'dml' ? affected : -1, elapsedMs: 3, resultSet: kind === 'query' }
  };
}

describe('script execution log', () => {
  it('summarises a statement by its first real line', () => {
    expect(statementSummary('-- 第 002 条\nINSERT INTO t\n  VALUES (1)')).toBe('INSERT INTO t VALUES (1)');
    expect(statementSummary('x'.repeat(300), 10)).toHaveLength(10);
  });

  it('lists every statement and keeps only queries and the failure as tabs', () => {
    const results = [statement(1, 'insert 1', 'dml'), statement(2, 'select 1', 'query'), statement(3, 'update', 'dml', 4), statement(4, 'bad', 'failed')];
    expect(needsScriptLog(results)).toBe(true);
    expect(needsScriptLog(results.slice(0, 1))).toBe(false);
    expect(tabbedResults(results).map((item) => item.index)).toEqual([2, 4]);
    const log = buildScriptLog(results);
    expect(log.map((entry) => [entry.index, entry.affectedRows, entry.rows])).toEqual([[1, 1, null], [2, null, 2], [3, 4, null], [4, null, null]]);
    expect(summarizeScriptLog(log)).toEqual({ total: 4, succeeded: 3, failed: 1, queries: 1, affectedRows: 5, elapsedMs: 12 });
  });

  it('caps query tabs so the tab strip stays usable', () => {
    const results = Array.from({ length: MAX_RESULT_TABS + 5 }, (_, i) => statement(i + 1, `select ${i}`, 'query'));
    expect(tabbedResults(results)).toHaveLength(MAX_RESULT_TABS);
    expect(hiddenResultTabCount(results)).toBe(5);
  });

  it('falls back to the first tab when the remembered one was merged into the log', () => {
    expect(resolveResultTabKey('stmt-3', ['script-log', 'stmt-2'])).toBe('script-log');
    expect(resolveResultTabKey('stmt-2', ['script-log', 'stmt-2'])).toBe('stmt-2');
    expect(resolveResultTabKey(undefined, [])).toBeUndefined();
  });
});
