import type { NativeToolStatus, RestoreFileFormat } from './types';

export type NativeToolMode = 'AUTO' | 'MANUAL';

export function nativeToolForBackup(method: string): NativeToolStatus['tool'] {
  if (method === 'ORACLE_EXP') return 'ORACLE_EXP';
  if (method === 'PG_DUMP') return 'PG_DUMP';
  return 'MYSQLDUMP';
}

export function nativeToolForRestore(format: RestoreFileFormat): NativeToolStatus['tool'] {
  if (format === 'ORACLE_DMP') return 'ORACLE_IMP';
  // pg_dump 的 custom 格式只能用 pg_restore 还原，不能喂给 psql。
  if (format === 'PG_DUMP') return 'PG_RESTORE';
  return 'MYSQL';
}

/**
 * 恢复工具在界面上的名字与路径示例。
 *
 * <p>集中在这里而不是散在组件的三目里 —— 之前 PostgreSQL 就是因为漏在那串三目之外，
 * 界面提示「未发现 Oracle imp」，路径占位也给的是 imp。</p>
 */
export function restoreToolHint(format: RestoreFileFormat): { name: string; placeholder: string } {
  if (format === 'ORACLE_DMP') return { name: 'Oracle imp', placeholder: '/opt/oracle/bin/imp' };
  if (format === 'PG_DUMP') return { name: 'PostgreSQL pg_restore', placeholder: '/usr/local/bin/pg_restore' };
  return { name: 'MySQL mysql', placeholder: '/usr/local/bin/mysql' };
}

/** 备份方式对应的恢复文件格式。历史记录没带 fileFormat 时按备份方式回推。 */
export function restoreFormatForBackupMethod(method?: string): RestoreFileFormat {
  if (method === 'MYSQLDUMP') return 'MYSQLDUMP';
  if (method === 'ORACLE_EXP') return 'ORACLE_DMP';
  if (method === 'PG_DUMP') return 'PG_DUMP';
  return 'SQL';
}

export function requestedToolPath(mode: NativeToolMode, path?: string): string | undefined {
  if (mode === 'AUTO') return undefined;
  const normalized = path?.trim();
  return normalized || undefined;
}
