import { describe, expect, it } from 'vitest';
import { closeSqlTabIn, duplicateSqlTabOf, normalizeSqlTabTitle, patchSqlTab, withLiveSql } from './sqlTabs';
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

  it('duplicates kind, options and delimiter, but not results', () => {
    const source = tab('a', { kind: 'command', title: '迁移脚本', results: [{} as never], delimiter: { mode: 'SLASH' } as never });
    const copy = duplicateSqlTabOf(source, 7, 'select 3');
    expect(copy).toMatchObject({ kind: 'command', title: '迁移脚本 副本', sql: 'select 3', dirty: true, delimiter: { mode: 'SLASH' } });
    expect(copy.results).toEqual([]);
    expect(copy.id).not.toBe(source.id);
  });

  it('rejects blank titles', () => {
    expect(normalizeSqlTabTitle('   ')).toBeNull();
    expect(normalizeSqlTabTitle('  报表 ')).toBe('报表');
  });
});
