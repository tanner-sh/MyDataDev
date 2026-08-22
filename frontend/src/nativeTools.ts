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

export function requestedToolPath(mode: NativeToolMode, path?: string): string | undefined {
  if (mode === 'AUTO') return undefined;
  const normalized = path?.trim();
  return normalized || undefined;
}
