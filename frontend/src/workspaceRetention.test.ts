import { describe, expect, it } from 'vitest';
import { createTableSnapshotCounter, enforceWorkspaceBudget } from './workspaceRetention';
import type { TableSnapshot } from './resourceWorkspaces';
import { EMPTY_ROW_HISTORY } from './tableEditing';
import { EMPTY_TABLE_QUERY } from './tableQuery';
import { IDLE_TABLE_ROW_COUNT } from './tableRowCount';
import type { SqlTab, TableRow } from './types';

function snapshot(): TableSnapshot {
  const values = { ID: 1, NOTE: 'x'.repeat(1000) };
  return {
    table: { tableName: 'T' }, data: { columns: [], rows: [values], keyColumns: ['ID'], editable: true, navigationMode: 'KEYSET', hasMore: false },
    edits: { past: [], present: [{ id: 'r1', values, original: values }], future: [] },
    query: { ...EMPTY_TABLE_QUERY, sorts: [{ column: 'ID', direction: 'DESC' }] }, page: 3, pageSize: 100, cursors: [null],
    preview: [], rowCount: IDLE_TABLE_ROW_COUNT, relations: null, scrollTop: 200
  };
}
function tab(id: string): SqlTab {
  return { id, title: id, sql: 'select 1', dirty: false, message: '', results: [{ index: 0, sql: 'select 1', startOffset: 0, endOffset: 8, status: 'SUCCESS', result: {
    columns: [{ key: 'c', label: 'C', typeName: 'VARCHAR' }], rows: [['x'.repeat(1000)]], elapsedMs: 1, affectedRows: 0, resultSet: true
  } }] };
}

describe('工作区统一缓存预算', () => {
  it('预算内返回原对象，不因保存草稿不断重建缓存', () => {
    const tabs = [tab('a')];
    const snapshots = new Map([['t', snapshot()]]);
    const next = enforceWorkspaceBudget(tabs, snapshots, [], 'a', 't', new Map());
    expect(next.tabs).toBe(tabs);
    expect(next.snapshots).toBe(snapshots);
  });
  it('按访问时间跨 SQL 与表缓存释放，保留表的筛选、排序与页大小', () => {
    const cached = snapshot();
    const sql = tab('a');
    const next = enforceWorkspaceBudget([sql], new Map([['t', cached]]), [], '', '', new Map([['table:t', 1], ['sql:a', 2]]), 11);
    expect(next.tabs[0]).toBe(sql);
    expect(next.snapshots.get('t')).toMatchObject({ data: null, edits: EMPTY_ROW_HISTORY, pageSize: cached.pageSize });
    expect(next.snapshots.get('t')?.query).toBe(cached.query);
    expect(cached.data).not.toBeNull();
  });
  it('保护活动数据和未提交修改，脏标记未同步时也不能丢失修改', () => {
    const clean = snapshot();
    const dirty = snapshot();
    dirty.edits.present = [{ ...dirty.edits.present[0], values: { ID: 1, NOTE: 'changed' }, touchedColumns: ['NOTE'] }];
    const sql = tab('active');
    const next = enforceWorkspaceBudget([sql], new Map([['active-table', clean], ['dirty', dirty]]), [], 'active', 'active-table', new Map(), 0);
    expect(next.tabs[0]).toBe(sql);
    expect(next.snapshots.get('active-table')).toBe(clean);
    expect(next.snapshots.get('dirty')).toBe(dirty);
    expect(next.retainedUnits).toBeGreaterThan(0);
  });
  it('把撤销历史计入预算，但共享行和值不按历史次数重复计费', () => {
    const counter = createTableSnapshotCounter();
    const base = snapshot();
    const initial = counter(base);
    const history = { ...base, edits: { ...base.edits, past: Array.from({ length: 50 }, () => [...base.edits.present]) } };
    expect(counter(history)).toBe(initial + 50);
    const previous: TableRow = { id: 'r1', values: { NOTE: 'y'.repeat(10_000) } };
    const largeHistory = { ...base, edits: { ...base.edits, past: [[previous]] } };
    expect(counter(largeHistory)).toBeGreaterThan(initial + 100);
    expect(counter({ ...largeHistory, scrollTop: 0 })).toBe(counter(largeHistory));
  });
  it('释放旧 SQL 结果时保留 SQL 草稿和当前结果', () => {
    const active = tab('active');
    const old = tab('old');
    const next = enforceWorkspaceBudget([old, active], new Map(), [], 'active', '', new Map(), 11);
    expect(next.tabs[0].results).toEqual([]);
    expect(next.tabs[0].sql).toBe(old.sql);
    expect(next.tabs[1]).toBe(active);
  });
});
