import { describe, expect, it } from 'vitest';
import type { SqlTab } from './types';
import { MAX_STORED_SQL_TABS, normalizeSqlSession, readSqlSession, sqlSessionStorageKey, writeSqlSession } from './sqlSessionStorage';

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
    value: (key: string) => values.get(key)
  };
}

describe('SQL session storage', () => {
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
