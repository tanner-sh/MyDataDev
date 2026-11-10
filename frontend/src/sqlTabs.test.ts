import { describe, expect, it } from 'vitest';
import { bindTabConnections, closeSqlTabIn, duplicateSqlTabOf, lastSqlTabOf, normalizeSqlTabTitle, patchSqlTab, pinTabSchemas, rebindSqlTab, withLiveSql } from './sqlTabs';
import type { SqlTab } from './types';

const tab = (id: string, extra: Partial<SqlTab> = {}): SqlTab => ({ id, title: id, sql: `select '${id}'`, dirty: false, results: [], message: '', ...extra });

describe('sql tabs', () => {
  it('writes the live editor value back only when it changed', () => {
    const tabs = [tab('a'), tab('b')];
    expect(withLiveSql(tabs, 'a', undefined)).toBe(tabs);
    expect(withLiveSql(tabs, 'a', "select 'a'")).toBe(tabs);
    expect(withLiveSql(tabs, 'a', 'select 2')[0]).toMatchObject({ sql: 'select 2', dirty: true });
    expect(withLiveSql(tabs, 'missing', 'select 2')).toBe(tabs);
  });

  it('marks a tab dirty when its SQL changes unless told otherwise', () => {
    expect(patchSqlTab(tab('a'), { sql: 'select 2' }).dirty).toBe(true);
    expect(patchSqlTab(tab('a'), { message: 'ok' }).dirty).toBe(false);
    expect(patchSqlTab(tab('a', { dirty: true }), { sql: 'x', dirty: false }).dirty).toBe(false);
  });

  it('falls back to the tab on the left when the active tab closes', () => {
    const tabs = [tab('a'), tab('b'), tab('c')];
    expect(closeSqlTabIn(tabs, 'b', 'b')).toMatchObject({ activeId: 'a' });
    expect(closeSqlTabIn(tabs, 'a', 'a')).toMatchObject({ activeId: 'b' });
    expect(closeSqlTabIn(tabs, 'c', 'a').tabs.map((item) => item.id)).toEqual(['a', 'b']);
    expect(closeSqlTabIn([tab('a')], 'a', 'a').tabs).toHaveLength(1);
  });

  it('duplicates kind, options, delimiter and schema, but not results', () => {
    const source = tab('a', { kind: 'command', title: '迁移脚本', results: [{} as never], delimiter: { mode: 'SLASH' } as never, executionSchema: 'ETL' });
    const copy = duplicateSqlTabOf(source, 7, 'select 3');
    expect(copy).toMatchObject({ kind: 'command', title: '迁移脚本 副本', sql: 'select 3', dirty: true, delimiter: { mode: 'SLASH' }, executionSchema: 'ETL' });
    expect(copy.results).toEqual([]);
    expect(copy.id).not.toBe(source.id);
  });

  it('rejects blank titles', () => {
    expect(normalizeSqlTabTitle('   ')).toBeNull();
    expect(normalizeSqlTabTitle('  报表 ')).toBe('报表');
  });

  it('pins tabs without a schema so the explorer no longer moves them', () => {
    const tabs = [tab('a'), tab('b', { executionSchema: 'S2', dirty: true }), tab('c')];
    const pinned = pinTabSchemas(tabs, 'PUBLIC');
    expect(pinned.map((item) => item.executionSchema)).toEqual(['PUBLIC', 'S2', 'PUBLIC']);
    expect(pinned.map((item) => item.dirty)).toEqual([false, true, false]);
    expect(pinned[1]).toBe(tabs[1]);
  });

  it('returns the same array when there is nothing to pin', () => {
    const tabs = [tab('a', { executionSchema: 'S2' })];
    expect(pinTabSchemas(tabs, 'PUBLIC')).toBe(tabs);
    const unpinned = [tab('a')];
    expect(pinTabSchemas(unpinned, undefined)).toBe(unpinned);
  });

  it('lands on a neighbour of the same connection when closing the active tab', () => {
    const tabs = [tab('a', { connectionId: 1 }), tab('b', { connectionId: 2 }), tab('c', { connectionId: 1 }), tab('d', { connectionId: 2 })];
    expect(closeSqlTabIn(tabs, 'c', 'c').activeId).toBe('a');
    expect(closeSqlTabIn(tabs, 'b', 'b').activeId).toBe('d');
    expect(closeSqlTabIn([tab('a', { connectionId: 1 }), tab('b', { connectionId: 2 })], 'b', 'b').activeId).toBe('a');
  });

  it('binds only the tabs that have no connection yet', () => {
    const tabs = [tab('a'), tab('b', { connectionId: 2, dirty: true })];
    const bound = bindTabConnections(tabs, 1);
    expect(bound.map((item) => item.connectionId)).toEqual([1, 2]);
    expect(bound[1]).toBe(tabs[1]);
    expect(bindTabConnections(bound, 3)).toBe(bound);
    expect(bindTabConnections(tabs, undefined)).toBe(tabs);
  });

  it('rebinding keeps the SQL but drops the old connection\'s schema and results', () => {
    const source = tab('a', { connectionId: 1, executionSchema: 'S2', results: [{} as never], activeResultKey: 'r', message: 'done', statusKind: 'success', dirty: true });
    const rebound = rebindSqlTab(source, 2);
    expect(rebound).toMatchObject({ connectionId: 2, sql: source.sql, dirty: true, results: [], message: '' });
    expect(rebound.executionSchema).toBeUndefined();
    expect(rebound.activeResultKey).toBeUndefined();
    expect(rebindSqlTab(source, 1)).toBe(source);
  });

  it('finds the right-most tab of a connection and pins schemas per connection', () => {
    const tabs = [tab('a', { connectionId: 1 }), tab('b', { connectionId: 2 }), tab('c', { connectionId: 1 })];
    expect(lastSqlTabOf(tabs, 1)?.id).toBe('c');
    expect(lastSqlTabOf(tabs, 9)).toBeUndefined();
    expect(pinTabSchemas(tabs, 'PUBLIC', 1).map((item) => item.executionSchema)).toEqual(['PUBLIC', undefined, 'PUBLIC']);
  });
});
