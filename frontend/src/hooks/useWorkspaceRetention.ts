import { useEffect, useRef, type MutableRefObject, type Dispatch, type SetStateAction } from 'react';
import { enforceWorkspaceBudget } from '../workspaceRetention';
import type { ResourceDocument, TableSnapshot } from '../resourceWorkspaces';
import type { RowHistory } from '../tableEditing';
import type { SqlTab, TableData } from '../types';

export function useWorkspaceRetention({ tabs, setTabs, snapshots, documents, activeSqlTabId, tableKey, mode, history, tableData }: {
  tabs: SqlTab[];
  setTabs: Dispatch<SetStateAction<SqlTab[]>>;
  snapshots: MutableRefObject<Map<string, TableSnapshot>>;
  documents: ResourceDocument[];
  activeSqlTabId: string;
  tableKey: string;
  mode: string;
  history: RowHistory;
  tableData: TableData | null;
}) {
  const lastAccess = useRef(new Map<string, number>());
  useEffect(() => {
    const key = mode === 'sql' ? `sql:${activeSqlTabId}` : mode === 'table' ? `table:${tableKey}` : '';
    if (key) lastAccess.current.set(key, performance.now());
  }, [activeSqlTabId, tableKey, mode]);
  useEffect(() => {
    const valid = new Set([...tabs.map((tab) => `sql:${tab.id}`), ...documents.map((document) => `table:${document.key}`)]);
    for (const key of lastAccess.current.keys()) if (!valid.has(key)) lastAccess.current.delete(key);
    const retained = enforceWorkspaceBudget(tabs, snapshots.current, documents, activeSqlTabId, tableKey, lastAccess.current);
    snapshots.current = retained.snapshots;
    if (retained.tabs !== tabs) setTabs((current) => current === tabs ? retained.tabs : current);
  }, [tabs, setTabs, snapshots, documents, activeSqlTabId, tableKey, mode, history, tableData]);
}
