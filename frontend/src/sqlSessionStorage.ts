import type { SqlTab } from './types';

export const MAX_STORED_SQL_TABS = 20;
const STORAGE_PREFIX = 'db-admin:sql-session:v1:';

type SessionStorageLike = Pick<Storage, 'getItem' | 'setItem'>;

type StoredSqlTab = {
  id: string;
  title: string;
  sql: string;
  dirty: boolean;
};

type StoredSqlSession = {
  version: 1;
  activeTabId: string;
  tabs: StoredSqlTab[];
};

export type SqlSession = {
  activeTabId: string;
  tabs: SqlTab[];
};

export function sqlSessionStorageKey(connectionId?: number | null) {
  return `${STORAGE_PREFIX}${connectionId == null ? 'unselected' : connectionId}`;
}

export function readSqlSession(connectionId?: number | null, storage = browserSessionStorage()): SqlSession | undefined {
  if (!storage) return undefined;
  try {
    const serialized = storage.getItem(sqlSessionStorageKey(connectionId));
    return serialized ? normalizeSqlSession(JSON.parse(serialized) as unknown) : undefined;
  } catch {
    return undefined;
  }
}

export function writeSqlSession(
  connectionId: number | null | undefined,
  tabs: SqlTab[],
  activeTabId: string,
  storage = browserSessionStorage()
) {
  if (!storage) return false;
  const storedTabs = tabs.slice(0, MAX_STORED_SQL_TABS).map<StoredSqlTab>((tab) => ({
    id: tab.id,
    title: tab.title,
    sql: tab.sql,
    dirty: tab.dirty
  }));
  if (storedTabs.length === 0) return false;
  const session: StoredSqlSession = {
    version: 1,
    activeTabId: storedTabs.some((tab) => tab.id === activeTabId) ? activeTabId : storedTabs[0].id,
    tabs: storedTabs
  };
  try {
    storage.setItem(sqlSessionStorageKey(connectionId), JSON.stringify(session));
    return true;
  } catch {
    return false;
  }
}

export function normalizeSqlSession(value: unknown): SqlSession | undefined {
  if (!isRecord(value) || value.version !== 1 || !Array.isArray(value.tabs)) return undefined;
  const seen = new Set<string>();
  const tabs: SqlTab[] = [];
  for (const candidate of value.tabs) {
    if (tabs.length >= MAX_STORED_SQL_TABS) break;
    if (!isRecord(candidate)) continue;
    const id = typeof candidate.id === 'string' ? candidate.id.trim() : '';
    if (!id || seen.has(id) || typeof candidate.sql !== 'string') continue;
    seen.add(id);
    tabs.push({
      id,
      title: typeof candidate.title === 'string' && candidate.title.trim()
        ? candidate.title.trim().slice(0, 80)
        : `查询 ${tabs.length + 1}`,
      sql: candidate.sql,
      dirty: candidate.dirty === true,
      results: [],
      message: ''
    });
  }
  if (tabs.length === 0) return undefined;
  const requestedActiveId = typeof value.activeTabId === 'string' ? value.activeTabId : '';
  return {
    tabs,
    activeTabId: tabs.some((tab) => tab.id === requestedActiveId) ? requestedActiveId : tabs[0].id
  };
}

function browserSessionStorage(): SessionStorageLike | undefined {
  if (typeof window === 'undefined') return undefined;
  try {
    return window.sessionStorage;
  } catch {
    return undefined;
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}
