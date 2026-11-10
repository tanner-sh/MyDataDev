import { describe, expect, it } from 'vitest';
import type { SqlTab } from './types';
import { MAX_STORED_SQL_TABS, migrateLegacySqlSessions, normalizeSqlSession, readGlobalSqlSession, readSqlSession, sqlSessionStorageKey, writeGlobalSqlSession, writeSqlSession, rememberClosedSqlTab, restoreClosedSqlTab } from './sqlSessionStorage';

function tab(id: string, sql = `select '${id}'`): SqlTab {
  return {
    id,
    title: `标签 ${id}`,
    sql,
    dirty: true,
    results: [{ index: 1, sql, startOffset: 0, endOffset: sql.length, status: 'SUCCESS', result: { columns: [], rows: [], resultSet: false, affectedRows: 1, elapsedMs: 3, truncated: false } }],
    activeResultKey: 'result-1',
    message: '执行成功',
    statusKind: 'success'
  };
}

function memoryStorage(initial: Record<string, string> = {}) {
  const values = new Map(Object.entries(initial));
  return {
    getItem: (key: string) => values.get(key) ?? null,
    setItem: (key: string, value: string) => { values.set(key, value); },
    key: (index: number) => [...values.keys()][index] ?? null,
    get length() { return values.size; },
    value: (key: string) => values.get(key)
  };
}

function plain(id: string, sql: string, dirty = false): SqlTab {
  return { id, title: id, sql, dirty, results: [], message: '' };
}

describe('SQL session storage', () => {
  it('isolates drafts and recently closed tabs by user and connection', () => {
    const storage = memoryStorage();
    writeSqlSession(1, [tab('alice')], 'alice', storage, 'alice');
    expect(readSqlSession(1, storage, 'bob')).toBeUndefined();
    rememberClosedSqlTab(1, tab('closed'), 'alice', storage);
    expect(restoreClosedSqlTab(2, 'alice', storage)).toBeUndefined();
    expect(restoreClosedSqlTab(1, 'bob', storage)).toBeUndefined();
    expect(restoreClosedSqlTab(1, 'alice', storage)?.sql).toBe("select 'closed'");
    expect(restoreClosedSqlTab(1, 'alice', storage)).toBeUndefined();
    expect(readSqlSession(1, storage, 'alice')?.tabs[0].id).toBe('alice');
  });

  it('keeps the ten most recently closed drafts and handles quota errors', () => {
    const storage = memoryStorage();
    for (let i = 0; i < 12; i++) rememberClosedSqlTab(1, tab(String(i)), 'alice', storage);
    for (let i = 11; i >= 2; i--) expect(restoreClosedSqlTab(1, 'alice', storage)?.id).toBe(String(i));
    expect(restoreClosedSqlTab(1, 'alice', storage)).toBeUndefined();
    expect(rememberClosedSqlTab(1, tab('a'), 'alice', { getItem: () => null, setItem: () => { throw new Error('quota'); } })).toBe(false);
  });

  it('isolates sessions by connection and excludes result data', () => {
    const storage = memoryStorage();
    expect(writeSqlSession(7, [tab('a')], 'a', storage)).toBe(true);
    expect(readSqlSession(8, storage)).toBeUndefined();

    const restored = readSqlSession(7, storage);
    expect(restored).toEqual({
      activeTabId: 'a',
      tabs: [{ id: 'a', title: '标签 a', sql: "select 'a'", dirty: true, results: [], message: '' }]
    });
    expect(storage.value(sqlSessionStorageKey(7))).not.toContain('执行成功');
  });

  it('falls back safely for corrupt or invalid data', () => {
    const storage = memoryStorage({ [sqlSessionStorageKey(1)]: '{broken' });
    expect(readSqlSession(1, storage)).toBeUndefined();
    expect(normalizeSqlSession({ version: 1, activeTabId: 'x', tabs: [] })).toBeUndefined();
  });

  it('deduplicates ids, limits tab count, and repairs the active tab', () => {
    const candidates = Array.from({ length: MAX_STORED_SQL_TABS + 3 }, (_, index) => ({
      id: index === 1 ? 'query-0' : `query-${index}`,
      title: index === 2 ? '   ' : `查询 ${index}`,
      sql: `select ${index}`,
      dirty: index % 2 === 0
    }));
    const restored = normalizeSqlSession({ version: 1, activeTabId: 'missing', tabs: candidates });
    expect(restored?.tabs).toHaveLength(MAX_STORED_SQL_TABS);
    expect(restored?.activeTabId).toBe('query-0');
    expect(restored?.tabs[1].title).toBe('查询 2');
  });

  it('does not throw when browser storage rejects a write', () => {
    const storage = { getItem: () => null, setItem: () => { throw new Error('quota'); } };
    expect(writeSqlSession(null, [tab('a')], 'a', storage)).toBe(false);
  });
});

 describe('migration namespace persistence', () => {
  it('retains an explicit target schema when restoring its connection session', () => {
    const session = normalizeSqlSession({version: 1, activeTabId: 'migration', tabs: [{id: 'migration', sql: 'UPDATE B.T SET V=1', executionSchema: 'TARGET', dirty: true}]});
    expect(session?.tabs[0].executionSchema).toBe('TARGET');
  });
});

describe('sql session delimiter', () => {
  it('keeps a non-default delimiter per tab and drops invalid ones', () => {
    const session = normalizeSqlSession({ version: 1, activeTabId: 'a', tabs: [
      { id: 'a', sql: 'select 1', delimiter: { mode: 'CUSTOM', token: '!' } },
      { id: 'b', sql: 'select 2', delimiter: { mode: 'CUSTOM', token: ';' } }
    ] });
    expect(session?.tabs[0].delimiter).toEqual({ mode: 'CUSTOM', token: '!' });
    expect(session?.tabs[1].delimiter).toBeUndefined();
  });

  it('keeps a standalone command window and its options across reloads', () => {
    const storage = memoryStorage();
    const options = { onError: 'CONTINUE' as const, transactionMode: 'SCRIPT' as const, endOfFileAction: 'COMMIT' as const };
    writeSqlSession(9, [tab('q'), { ...tab('c', 'begin null; end;'), kind: 'command', commandOptions: options }], 'c', storage, 'owner');
    const session = readSqlSession(9, storage, 'owner');
    expect(session?.activeTabId).toBe('c');
    expect(session?.tabs[0].kind).toBeUndefined();
    expect(session?.tabs[1]).toMatchObject({ kind: 'command', commandOptions: options, sql: 'begin null; end;', results: [] });
  });

  it('drops unknown tab kinds and malformed command options', () => {
    const session = normalizeSqlSession({ version: 1, activeTabId: 'a', tabs: [
      { id: 'a', title: '', sql: 'x', kind: 'shell', commandOptions: { onError: 'IGNORE', transactionMode: 'AUTOCOMMIT', endOfFileAction: 'COMMIT' } },
      { id: 'b', title: '', sql: 'y', kind: 'command' }
    ] });
    expect(session?.tabs[0].kind).toBeUndefined();
    expect(session?.tabs[0].commandOptions).toBeUndefined();
    expect(session?.tabs[1]).toMatchObject({ kind: 'command', title: '命令窗口 2' });
  });

  it('keeps each tab bound to its own connection in the global session', () => {
    const storage = memoryStorage();
    writeGlobalSqlSession([{ ...tab('a'), connectionId: 1, executionSchema: 'S2' }, { ...tab('b'), connectionId: 2 }], 'b', storage, 'alice');
    const restored = readGlobalSqlSession(storage, 'alice');
    expect(restored?.tabs.map((item) => [item.id, item.connectionId, item.executionSchema])).toEqual([['a', 1, 'S2'], ['b', 2, undefined]]);
    expect(restored?.activeTabId).toBe('b');
    expect(readGlobalSqlSession(storage, 'bob')).toBeUndefined();
  });

  it('merges the per-connection sessions of older versions, preferred connection first', () => {
    const storage = memoryStorage();
    writeSqlSession(2, [plain('query-1', 'select 2'), plain('query-2', 'select 22', true)], 'query-2', storage, 'alice');
    writeSqlSession(1, [plain('query-1', 'select 1')], 'query-1', storage, 'alice');
    writeSqlSession(null, [plain('query-1', 'select 0')], 'query-1', storage, 'alice');
    writeSqlSession(3, [plain('other-user', 'select 3')], 'other-user', storage, 'bob');
    rememberClosedSqlTab(2, plain('closed', 'select closed'), 'alice', storage);

    const migrated = migrateLegacySqlSessions(2, 30, storage, 'alice');
    expect(migrated?.tabs.map((item) => [item.id, item.connectionId, item.sql])).toEqual([
      ['2:query-1', 2, 'select 2'],
      ['2:query-2', 2, 'select 22'],
      ['1:query-1', 1, 'select 1'],
      ['u:query-1', undefined, 'select 0']
    ]);
    expect(migrated?.activeTabId).toBe('2:query-2');
    // 旧数据保留，迁移出问题还能找回。
    expect(readSqlSession(2, storage, 'alice')?.tabs).toHaveLength(2);
  });

  it('moves overflowing drafts to recently closed instead of dropping them', () => {
    const storage = memoryStorage();
    writeSqlSession(1, [plain('keep', 'select 1 as val'), plain('mine', 'select mine')], 'keep', storage, 'alice');
    writeSqlSession(2, [plain('blank', 'select 1 as val'), plain('draft', 'select draft', true)], 'draft', storage, 'alice');
    const migrated = migrateLegacySqlSessions(1, 3, storage, 'alice');
    expect(migrated?.tabs.map((item) => item.id)).toEqual(['1:keep', '1:mine', '2:draft']);
    expect(migrated?.activeTabId).toBe('1:keep');
    expect(restoreClosedSqlTab(2, 'alice', storage)).toBeUndefined();

    const tight = memoryStorage();
    writeSqlSession(1, [plain('a', 'select a')], 'a', tight, 'alice');
    writeSqlSession(2, [plain('b', 'select b')], 'b', tight, 'alice');
    expect(migrateLegacySqlSessions(1, 1, tight, 'alice')?.tabs.map((item) => item.id)).toEqual(['1:a']);
    expect(restoreClosedSqlTab(2, 'alice', tight)?.sql).toBe('select b');
  });

  it('returns nothing to migrate when no older session exists', () => {
    expect(migrateLegacySqlSessions(1, 30, memoryStorage(), 'alice')).toBeUndefined();
  });
});
