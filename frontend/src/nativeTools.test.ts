import { describe, expect, it } from 'vitest';
import { nativeToolForBackup, nativeToolForRestore, requestedToolPath, restoreFormatForBackupMethod, restoreToolHint } from './nativeTools';

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

describe('PostgreSQL 恢复入口', () => {
  it('pg_dump 备份回推出 PG_DUMP 格式，而不是掉回 SQL', () => {
    // 掉回 SQL 的话，一份 custom 格式的 .dump 会被当成可转换 SQL 去解析，直接失败。
    expect(restoreFormatForBackupMethod('PG_DUMP')).toBe('PG_DUMP');
    expect(restoreFormatForBackupMethod('MYSQLDUMP')).toBe('MYSQLDUMP');
    expect(restoreFormatForBackupMethod('ORACLE_EXP')).toBe('ORACLE_DMP');
    expect(restoreFormatForBackupMethod(undefined)).toBe('SQL');
  });

  it('每种格式给出自己的工具名与路径示例', () => {
    expect(nativeToolForRestore('PG_DUMP')).toBe('PG_RESTORE');
    expect(restoreToolHint('PG_DUMP').name).toContain('pg_restore');
    expect(restoreToolHint('PG_DUMP').placeholder).toContain('pg_restore');
    expect(restoreToolHint('MYSQLDUMP').name).toContain('mysql');
    expect(restoreToolHint('ORACLE_DMP').name).toContain('imp');
  });
});
