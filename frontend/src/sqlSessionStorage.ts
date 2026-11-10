import { normalizeParameterDefinitions, type SqlParameterDefinition } from './sqlParameters';
import { workspaceIdentity } from './auth';
import { normalizeDelimiterSetting, type SqlDelimiterSetting } from './scriptDelimiter';
import { normalizeCommandOptions, type CommandWindowOptions } from './commandWindow';
import type { SqlTab } from './types';

export const MAX_STORED_SQL_TABS = 30;
const STORAGE_PREFIX = 'db-admin:sql-session:v2:';
/**
 * v3 是一份全局会话：所有连接的标签放在一起，每个标签自己带 connectionId。v2 是每连接一份，
 * 切连接就换一整套标签 —— 标签只能在同一条连接内部换 Schema，没法让一个标签开着 A 库、旁边一个开着 B 库。
 */
const GLOBAL_STORAGE_PREFIX = 'db-admin:sql-session:v3:';

type SessionStorageLike = Pick<Storage, 'getItem' | 'setItem'>;
/** 迁移时要遍历旧的按连接保存的键，需要 key/length。 */
type EnumerableStorageLike = SessionStorageLike & Pick<Storage, 'key' | 'length'>;

type StoredSqlTab = {
  connectionId?: number;
  kind?: 'command';
  commandOptions?: CommandWindowOptions;
  executionSchema?: string;
  delimiter?: SqlDelimiterSetting;
  parameters?: SqlParameterDefinition[];
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

export function sqlSessionStorageKey(connectionId?: number | null, owner = workspaceIdentity()) {
  return `${STORAGE_PREFIX}${encodeURIComponent(owner)}:${connectionId == null ? 'unselected' : connectionId}`;
}

export function readSqlSession(connectionId?: number | null, storage = browserSessionStorage(), owner = workspaceIdentity()): SqlSession | undefined {
  if (!storage) return undefined;
  try {
    const serialized = storage.getItem(sqlSessionStorageKey(connectionId, owner));
    return serialized ? normalizeSqlSession(JSON.parse(serialized) as unknown) : undefined;
  } catch {
    return undefined;
  }
}

export function writeSqlSession(
  connectionId: number | null | undefined,
  tabs: SqlTab[],
  activeTabId: string,
  storage = browserSessionStorage(),
  owner = workspaceIdentity()
) {
  if (!storage) return false;
  const session = serializeSqlSession(tabs, activeTabId);
  if (!session) return false;
  try {
    storage.setItem(sqlSessionStorageKey(connectionId, owner), JSON.stringify(session));
    return true;
  } catch {
    return false;
  }
}

function serializeSqlSession(tabs: SqlTab[], activeTabId: string): StoredSqlSession | undefined {
  const storedTabs = tabs.slice(0, MAX_STORED_SQL_TABS).map<StoredSqlTab>((tab) => ({
    ...(tab.connectionId != null ? { connectionId: tab.connectionId } : {}),
    id: tab.id,
    title: tab.title,
    sql: tab.sql,
    dirty: tab.dirty,
    executionSchema: tab.executionSchema,
    ...(tab.parameters ? { parameters: tab.parameters } : {}),
    ...(tab.delimiter && tab.delimiter.mode !== 'AUTO' ? { delimiter: tab.delimiter } : {}),
    ...(tab.kind === 'command' ? { kind: 'command' as const } : {}),
    ...(tab.commandOptions ? { commandOptions: tab.commandOptions } : {})
  }));
  if (storedTabs.length === 0) return undefined;
  return {
    version: 1,
    activeTabId: storedTabs.some((tab) => tab.id === activeTabId) ? activeTabId : storedTabs[0].id,
    tabs: storedTabs
  };
}

export function globalSqlSessionStorageKey(owner = workspaceIdentity()) {
  return `${GLOBAL_STORAGE_PREFIX}${encodeURIComponent(owner)}`;
}

export function readGlobalSqlSession(storage = browserSessionStorage(), owner = workspaceIdentity()): SqlSession | undefined {
  if (!storage) return undefined;
  try {
    const serialized = storage.getItem(globalSqlSessionStorageKey(owner));
    return serialized ? normalizeSqlSession(JSON.parse(serialized) as unknown) : undefined;
  } catch {
    return undefined;
  }
}

export function writeGlobalSqlSession(tabs: SqlTab[], activeTabId: string, storage = browserSessionStorage(), owner = workspaceIdentity()) {
  if (!storage) return false;
  const session = serializeSqlSession(tabs, activeTabId);
  if (!session) return false;
  try {
    storage.setItem(globalSqlSessionStorageKey(owner), JSON.stringify(session));
    return true;
  } catch {
    return false;
  }
}

/**
 * 把 v2 里每条连接各自的会话并成一份全局会话（第一次打开新版本时用一次）。
 *
 * <ul>
 *   <li>每个标签带上它原来所属的连接；「未选连接」那份不带，连上之后由 App 补。</li>
 *   <li>不同连接里的标签 id 会撞（默认标签都叫 query-1），一律加上连接前缀。</li>
 *   <li>上次所在连接的标签排最前、它的当前标签仍是当前标签；其余连接按 id 依次接上。</li>
 *   <li>合并后超过上限时，先保留上次所在连接的全部标签，再保留写过东西的；放不下的进各自连接的
 *       「找回关闭的 SQL」，不直接丢。</li>
 *   <li>v2 的键不删：万一迁移有问题，旧数据还在。</li>
 * </ul>
 */
export function migrateLegacySqlSessions(
  preferredConnectionId: number | null | undefined,
  limit: number,
  storage: EnumerableStorageLike | undefined = browserSessionStorage() as EnumerableStorageLike | undefined,
  owner = workspaceIdentity()
): SqlSession | undefined {
  if (!storage || typeof storage.key !== 'function') return undefined;
  const prefix = `${STORAGE_PREFIX}${encodeURIComponent(owner)}:`;
  const sessions: Array<{ connectionId: number | undefined; session: SqlSession }> = [];
  try {
    for (let index = 0; index < storage.length; index += 1) {
      const key = storage.key(index);
      if (!key || !key.startsWith(prefix)) continue;
      const suffix = key.slice(prefix.length);
      if (suffix.includes(':')) continue; // ':closed' 等附属键
      const connectionId = suffix === 'unselected' ? undefined : Number(suffix);
      if (connectionId !== undefined && !(Number.isInteger(connectionId) && connectionId > 0)) continue;
      const session = readSqlSession(connectionId ?? null, storage, owner);
      if (session) sessions.push({ connectionId, session });
    }
  } catch {
    return undefined;
  }
  if (sessions.length === 0) return undefined;
  const rank = (connectionId: number | undefined) => connectionId === undefined
    ? Number.MAX_SAFE_INTEGER
    : connectionId === preferredConnectionId ? -1 : connectionId;
  sessions.sort((left, right) => rank(left.connectionId) - rank(right.connectionId));
  const candidates = sessions.flatMap(({ connectionId, session }) => session.tabs.map((tab) => ({
    preferred: connectionId !== undefined && connectionId === preferredConnectionId,
    originalId: tab.id,
    sessionActiveId: session.activeTabId,
    tab: { ...tab, id: `${connectionId ?? 'u'}:${tab.id}`, ...(connectionId !== undefined ? { connectionId } : {}) }
  })));
  const written = (sql: string, dirty: boolean) => dirty || (sql.trim() !== '' && sql.trim() !== 'select 1 as val');
  const keep = new Set<string>();
  for (const candidate of candidates) if (candidate.preferred && keep.size < limit) keep.add(candidate.tab.id);
  for (const candidate of candidates) if (keep.size < limit && written(candidate.tab.sql, candidate.tab.dirty)) keep.add(candidate.tab.id);
  for (const candidate of candidates) if (keep.size < limit) keep.add(candidate.tab.id);
  const tabs = candidates.filter((candidate) => keep.has(candidate.tab.id)).map((candidate) => candidate.tab);
  for (const candidate of candidates) {
    if (!keep.has(candidate.tab.id) && written(candidate.tab.sql, candidate.tab.dirty)) {
      rememberClosedSqlTab(candidate.tab.connectionId ?? null, candidate.tab, owner, storage);
    }
  }
  const preferredActive = candidates.find((candidate) => candidate.preferred && candidate.originalId === candidate.sessionActiveId);
  const activeTabId = preferredActive && keep.has(preferredActive.tab.id) ? preferredActive.tab.id : tabs[0].id;
  return { tabs, activeTabId };
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
    const kind = candidate.kind === 'command' ? 'command' as const : undefined;
    const commandOptions = normalizeCommandOptions(candidate.commandOptions);
    const connectionId = typeof candidate.connectionId === 'number' && Number.isInteger(candidate.connectionId) && candidate.connectionId > 0
      ? candidate.connectionId
      : undefined;
    tabs.push({
      ...(connectionId !== undefined ? { connectionId } : {}),
      id,
      title: typeof candidate.title === 'string' && candidate.title.trim()
        ? candidate.title.trim().slice(0, 80)
        : `${kind ? '命令窗口' : '查询'} ${tabs.length + 1}`,
      sql: candidate.sql,
      dirty: candidate.dirty === true,
      executionSchema: typeof candidate.executionSchema === 'string' ? candidate.executionSchema : undefined,
      ...(candidate.parameters ? { parameters: normalizeParameterDefinitions(candidate.parameters) } : {}),
      ...(normalizeDelimiterSetting(candidate.delimiter) ? { delimiter: normalizeDelimiterSetting(candidate.delimiter) } : {}),
      ...(kind ? { kind } : {}),
      ...(commandOptions ? { commandOptions } : {}),
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
    return window.localStorage;
  } catch {
    return undefined;
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

/** 最近关闭的十个草稿同样只保存 SQL，不保留查询结果。 */
export function rememberClosedSqlTab(connectionId: number | null, tab: SqlTab, owner = workspaceIdentity(), storage = browserSessionStorage()) {
  if (!storage) return false;
  const key = sqlSessionStorageKey(connectionId, owner) + ':closed';
  try {
    const old = normalizeSqlSession(JSON.parse(storage.getItem(key) || 'null'))?.tabs || [];
    return writeSqlSession(connectionId, [tab, ...old.filter(item => item.id !== tab.id)].slice(0, 10), tab.id,
      { getItem: () => storage.getItem(key), setItem: (_, value) => storage.setItem(key, value) }, owner);
  } catch { return false; }
}

export function restoreClosedSqlTab(connectionId: number | null, owner = workspaceIdentity(), storage = browserSessionStorage()): SqlTab | undefined {
  if (!storage) return undefined;
  const key = sqlSessionStorageKey(connectionId, owner) + ':closed';
  try {
    const tabs = normalizeSqlSession(JSON.parse(storage.getItem(key) || 'null'))?.tabs || [];
    const [tab, ...rest] = tabs;
    storage.setItem(key, JSON.stringify({ version: 1, tabs: rest, activeTabId: rest[0]?.id || '' }));
    return tab;
  } catch { return undefined; }
}
