import type { NativeToolStatus, RestoreFileFormat } from './types';

export type NativeToolMode = 'AUTO' | 'MANUAL';

export function nativeToolForBackup(method: string): NativeToolStatus['tool'] {
  return method === 'ORACLE_EXP' ? 'ORACLE_EXP' : 'MYSQLDUMP';
}

export function nativeToolForRestore(format: RestoreFileFormat): NativeToolStatus['tool'] {
  return format === 'ORACLE_DMP' ? 'ORACLE_IMP' : 'MYSQL';
}

export function requestedToolPath(mode: NativeToolMode, path?: string): string | undefined {
  if (mode === 'AUTO') return undefined;
  const normalized = path?.trim();
  return normalized || undefined;
}
