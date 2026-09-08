import { MAX_RETAINED_RESULT_UNITS, retainedResultUnits } from './resultRetention';
import { EMPTY_ROW_HISTORY, type RowHistory } from './tableEditing';
import type { ResourceDocument, TableSnapshot } from './resourceWorkspaces';
import type { SqlTab, TableRow } from './types';
import { buildChanges } from './utils';

/** 与 SQL 结果使用同一单位：单元格 + 每百字符。只用于预算，不声称等于实际堆字节数。 */
export function createTableSnapshotCounter() {
  const cache = new WeakMap<RowHistory, { data: TableSnapshot['data']; units: number }>();
  const recordUnits = new WeakMap<Record<string, unknown>, number>();
  return (snapshot: TableSnapshot): number => {
    const cached = cache.get(snapshot.edits);
    if (cached?.data === snapshot.data) return cached.units;
    const records = new Set<Record<string, unknown>>();
    const rows = new Set<TableRow>();
    const arrays = new Set<TableRow[]>();
    let units = 0;
    const addRecord = (record: Record<string, unknown> | undefined) => {
      if (!record || records.has(record)) return;
      records.add(record);
      let size = recordUnits.get(record);
      if (size === undefined) {
        size = Object.values(record).reduce<number>((total, value) => total + 1
          + (typeof value === 'string' ? Math.ceil(value.length / 100) : 0), 0);
        recordUnits.set(record, size);
      }
      units += size;
    };
    for (const record of snapshot.data?.rows ?? []) addRecord(record);
    for (const history of [snapshot.edits.present, ...snapshot.edits.past, ...snapshot.edits.future]) {
      if (arrays.has(history)) continue;
      arrays.add(history);
      units += Math.ceil(history.length / 8); // 历史数组本身仍保留行引用。
      for (const row of history) {
        if (rows.has(row)) continue;
        rows.add(row);
        addRecord(row.values);
        addRecord(row.original);
      }
    }
    cache.set(snapshot.edits, { data: snapshot.data, units });
    return units;
  };
}

const tableUnits = createTableSnapshotCounter();

export function enforceWorkspaceBudget(
  tabs: SqlTab[], snapshots: Map<string, TableSnapshot>, documents: ResourceDocument[],
  protectedSqlTabId: string, protectedTableKey: string, lastAccess: ReadonlyMap<string, number>,
  maxUnits = MAX_RETAINED_RESULT_UNITS
) {
  const candidates: Array<{ id: string; kind: 'sql' | 'table'; units: number; accessed: number }> = [];
  let retainedUnits = 0;
  for (const tab of tabs) {
    const units = retainedResultUnits(tab.results);
    retainedUnits += units;
    if (tab.id !== protectedSqlTabId && units > 0) candidates.push({ id: tab.id, kind: 'sql', units, accessed: lastAccess.get(`sql:${tab.id}`) ?? 0 });
  }
  for (const [key, snapshot] of snapshots) {
    const units = tableUnits(snapshot);
    retainedUnits += units;
    if (key !== protectedTableKey && units > 0 && !documents.some((document) => document.key === key && document.dirty)) {
      candidates.push({ id: key, kind: 'table', units, accessed: lastAccess.get(`table:${key}`) ?? 0 });
    }
  }
  let nextSnapshots = snapshots;
  const releasedSql = new Set<string>();
  for (const candidate of candidates.sort((left, right) => left.accessed - right.accessed)) {
    if (retainedUnits <= maxUnits) break;
    const snapshot = candidate.kind === 'table' ? snapshots.get(candidate.id) : undefined;
    // 只在需要淘汰时检查真实修改，防止脏标记尚未同步时误丢数据。
    if (snapshot && buildChanges(snapshot.edits.present, snapshot.data?.keyColumns ?? []).length > 0) continue;
    retainedUnits -= candidate.units;
    if (candidate.kind === 'sql') releasedSql.add(candidate.id);
    else {
      if (nextSnapshots === snapshots) nextSnapshots = new Map(snapshots);
      // 保留筛选、排序和页大小；下次打开自动重取第一页，不丢弃文档标签。
      nextSnapshots.set(candidate.id, { ...snapshot!, data: null, edits: EMPTY_ROW_HISTORY, preview: [], scrollTop: 0 });
    }
  }
  const nextTabs = releasedSql.size ? tabs.map((tab) => releasedSql.has(tab.id) ? {
    ...tab, results: [], activeResultKey: undefined,
    message: '为控制浏览器内存，已释放该标签页的旧结果；SQL 文本仍保留，可重新执行。'
  } : tab) : tabs;
  return { tabs: nextTabs, snapshots: nextSnapshots, retainedUnits };
}
