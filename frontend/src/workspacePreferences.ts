import type { DbObject } from './types';

const FAVORITE_CONNECTIONS_KEY = 'db-admin-favorite-connections-v1';
const FAVORITE_OBJECTS_KEY = 'db-admin-favorite-objects-v1';
const RECENT_OBJECTS_KEY = 'db-admin-recent-objects-v1';
const RECENT_COMMANDS_KEY = 'db-admin-recent-commands-v1';
const MAX_RECENT_OBJECTS = 16;

type PreferenceStorage = Pick<Storage, 'getItem' | 'setItem'>;

export type RecentObjectPreference = {
  key: string;
  connectionId: number;
  schemaName: string;
  name: string;
  type: string;
  visitedAt: number;
};

function defaultStorage(): PreferenceStorage | null {
  if (typeof localStorage === 'undefined') return null;
  return localStorage;
}

function readArray<T>(key: string, storage: PreferenceStorage | null): T[] {
  if (!storage) return [];
  try {
    const value: unknown = JSON.parse(storage.getItem(key) || '[]');
    return Array.isArray(value) ? value as T[] : [];
  } catch {
    return [];
  }
}

function writeArray(key: string, value: unknown[], storage: PreferenceStorage | null) {
  if (!storage) return;
  try {
    storage.setItem(key, JSON.stringify(value));
  } catch {
    // 浏览器禁用或耗尽本地存储时，偏好降级为当前会话内状态。
  }
}

export function readFavoriteConnectionIds(storage: PreferenceStorage | null = defaultStorage()) {
  return readArray<unknown>(FAVORITE_CONNECTIONS_KEY, storage)
    .filter((value): value is number => Number.isInteger(value) && Number(value) > 0);
}

export function writeFavoriteConnectionIds(ids: number[], storage: PreferenceStorage | null = defaultStorage()) {
  writeArray(FAVORITE_CONNECTIONS_KEY, [...new Set(ids.filter((id) => Number.isInteger(id) && id > 0))], storage);
}

/** 命令面板里最近用过的命令 id，最新的在前。上限由 commandPalette.ts 的 RECENT_COMMAND_LIMIT 定。 */
export function readRecentCommandIds(storage: PreferenceStorage | null = defaultStorage()) {
  return readArray<unknown>(RECENT_COMMANDS_KEY, storage)
    .filter((value): value is string => typeof value === 'string' && value.length > 0);
}

export function writeRecentCommandIds(ids: string[], storage: PreferenceStorage | null = defaultStorage()) {
  writeArray(RECENT_COMMANDS_KEY, ids.filter(Boolean), storage);
}

export function objectPreferenceKey(connectionId: number, object: Pick<DbObject, 'schemaName' | 'name' | 'type'>) {
  return [connectionId, object.schemaName || '', object.type || '', object.name].map((part) => encodeURIComponent(String(part))).join(':');
}

export function readFavoriteObjectKeys(storage: PreferenceStorage | null = defaultStorage()) {
  return readArray<unknown>(FAVORITE_OBJECTS_KEY, storage)
    .filter((value): value is string => typeof value === 'string' && value.length > 0);
}

export function writeFavoriteObjectKeys(keys: string[], storage: PreferenceStorage | null = defaultStorage()) {
  writeArray(FAVORITE_OBJECTS_KEY, [...new Set(keys.filter(Boolean))], storage);
}

export function readRecentObjects(storage: PreferenceStorage | null = defaultStorage()) {
  return readArray<RecentObjectPreference>(RECENT_OBJECTS_KEY, storage)
    .filter((entry) => entry && typeof entry.key === 'string' && Number.isInteger(entry.connectionId) && typeof entry.name === 'string')
    .sort((left, right) => right.visitedAt - left.visitedAt)
    .slice(0, MAX_RECENT_OBJECTS);
}

export function rememberRecentObject(
  current: RecentObjectPreference[],
  connectionId: number,
  object: Pick<DbObject, 'schemaName' | 'name' | 'type'>,
  visitedAt = Date.now(),
  storage: PreferenceStorage | null = defaultStorage()
) {
  const entry: RecentObjectPreference = {
    key: objectPreferenceKey(connectionId, object),
    connectionId,
    schemaName: object.schemaName || '',
    name: object.name,
    type: object.type,
    visitedAt
  };
  const next = [entry, ...current.filter((item) => item.key !== entry.key)]
    .sort((left, right) => right.visitedAt - left.visitedAt)
    .slice(0, MAX_RECENT_OBJECTS);
  writeArray(RECENT_OBJECTS_KEY, next, storage);
  return next;
}
