import { describe, expect, it } from 'vitest';
import type { SqlStatementResult } from './types';
import { nextResultPaneMode, sqlStatementResultLabel } from './sqlResultWorkspace';

function statement(overrides: Partial<SqlStatementResult> = {}): SqlStatementResult {
  return {
    index: 2,
    sql: 'select 1',
    startOffset: 0,
    endOffset: 23,
    status: 'SUCCESS',
    result: {
      columns: [],
      rows: [],
      affectedRows: 0,
      elapsedMs: 12,
      resultSet: true
    },
    ...overrides
  };
}

describe('SQL result labels', () => {
  it('includes the actual source table when one is available', () => {
    expect(sqlStatementResultLabel(statement({
      result: { columns: [], rows: [], affectedRows: 0, elapsedMs: 12, resultSet: true, sourceTable: { nameParts: ['APP', 'USERS'] } }
    }))).toBe('结果 2 · APP.USERS');
    expect(sqlStatementResultLabel(statement({ sql: 'select * from public.accounts where active = true' }))).toBe('结果 2 · public.accounts');
  });

  it('falls back for complex results, failures and affected rows', () => {
    expect(sqlStatementResultLabel(statement())).toBe('结果 2');
    expect(sqlStatementResultLabel(statement({ status: 'FAILED' }))).toBe('错误 2');
    expect(sqlStatementResultLabel(statement({
      result: { columns: [], rows: [], affectedRows: 4, elapsedMs: 8, resultSet: false }
    }))).toBe('影响 2');
  });
});

describe('SQL result pane modes', () => {
  it('toggles collapse and maximize back to the normal split view', () => {
    expect(nextResultPaneMode('normal', 'toggle-collapse')).toBe('collapsed');
    expect(nextResultPaneMode('maximized', 'toggle-collapse')).toBe('collapsed');
    expect(nextResultPaneMode('collapsed', 'toggle-collapse')).toBe('normal');
    expect(nextResultPaneMode('normal', 'toggle-maximize')).toBe('maximized');
    expect(nextResultPaneMode('collapsed', 'toggle-maximize')).toBe('maximized');
    expect(nextResultPaneMode('maximized', 'toggle-maximize')).toBe('normal');
  });

  it('restores a collapsed pane when a new result arrives', () => {
    expect(nextResultPaneMode('collapsed', 'new-result')).toBe('normal');
    expect(nextResultPaneMode('normal', 'new-result')).toBe('normal');
    expect(nextResultPaneMode('maximized', 'new-result')).toBe('maximized');
  });
});
