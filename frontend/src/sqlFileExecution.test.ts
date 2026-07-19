import { describe, expect, it } from 'vitest';
import type { SqlFileExecution } from './types';
import { formatSqlFileBytes, sqlFileStatusLabel, sqlFileTaskPercent } from './sqlFileExecution';

function job(patch: Partial<SqlFileExecution>): SqlFileExecution {
  return {
    id: 1, connectionId: 1, connectionName: 'dev', targetDbType: 'mysql', fileName: 'demo.sql',
    fileSize: 1000, checksumSha256: 'a', status: 'ANALYZING', processedBytes: 0, statementCurrent: 0,
    queryCount: 0, mutationCount: 0, ddlCount: 0, unknownCount: 0, successCount: 0, queryRowCount: 0,
    metadataChanged: false, sessionChanged: false, cancelRequested: false, expiresAt: '', createdAt: '', ...patch
  };
}

describe('SQL file execution presentation', () => {
  it('maps analysis and execution progress without showing a terminal value early', () => {
    expect(sqlFileTaskPercent(job({ processedBytes: 500 }))).toBe(50);
    expect(sqlFileTaskPercent(job({ status: 'RUNNING', statementTotal: 10, statementCurrent: 9 }))).toBe(90);
    expect(sqlFileTaskPercent(job({ status: 'RUNNING', statementTotal: 10, statementCurrent: 10 }))).toBe(99);
    expect(sqlFileTaskPercent(job({ status: 'SUCCESS' }))).toBe(100);
  });

  it('formats statuses and file sizes', () => {
    expect(sqlFileStatusLabel('READY')).toBe('等待确认');
    expect(formatSqlFileBytes(1024 * 1024)).toBe('1.0 MB');
  });
});
