import { describe, expect, it } from 'vitest';
import { nativeToolForBackup, nativeToolForRestore, requestedToolPath } from './nativeTools';

describe('native tool selection', () => {
  it('maps backup and restore operations to their executable type', () => {
    expect(nativeToolForBackup('MYSQLDUMP')).toBe('MYSQLDUMP');
    expect(nativeToolForBackup('ORACLE_EXP')).toBe('ORACLE_EXP');
    expect(nativeToolForRestore('MYSQLDUMP')).toBe('MYSQL');
    expect(nativeToolForRestore('ORACLE_DMP')).toBe('ORACLE_IMP');
  });

  it('omits the path in automatic mode and normalizes a manual override', () => {
    expect(requestedToolPath('AUTO', '/custom/mysql')).toBeUndefined();
    expect(requestedToolPath('MANUAL', ' /custom/mysql ')).toBe('/custom/mysql');
    expect(requestedToolPath('MANUAL', '   ')).toBeUndefined();
  });
});

describe('PostgreSQL 原生工具', () => {
  it('备份走 pg_dump，恢复走 pg_restore 而不是 psql', () => {
    expect(nativeToolForBackup('PG_DUMP')).toBe('PG_DUMP');
    // custom 格式只能用 pg_restore 还原。
    expect(nativeToolForRestore('PG_DUMP')).toBe('PG_RESTORE');
  });

  it('不影响原有的 MySQL 与 Oracle 映射', () => {
    expect(nativeToolForBackup('MYSQLDUMP')).toBe('MYSQLDUMP');
    expect(nativeToolForBackup('ORACLE_EXP')).toBe('ORACLE_EXP');
    expect(nativeToolForRestore('MYSQLDUMP')).toBe('MYSQL');
    expect(nativeToolForRestore('ORACLE_DMP')).toBe('ORACLE_IMP');
  });
});
