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
