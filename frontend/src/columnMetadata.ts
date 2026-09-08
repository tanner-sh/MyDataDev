import { api } from './api';
import { AsyncResourceCache } from './asyncResourceCache';
import type { ObjectColumns } from './types';

type ObjectIdentity = { schemaName?: string | null; name: string };

/** 补全与表头共用，取消某一次输入不会中止其他调用者需要的请求。 */
export function createColumnMetadataLoader(fetchColumns: (connectionId: number, object: ObjectIdentity) => Promise<ObjectColumns>) {
  const cache = new AsyncResourceCache<string, ObjectColumns>({ ttlMs: 10 * 60_000, maxEntries: 500 });
  return {
    load(connectionId: number, object: ObjectIdentity) {
      const key = JSON.stringify([connectionId, object.schemaName || '', object.name]);
      return cache.load(key, () => fetchColumns(connectionId, object));
    },
    clear: () => cache.clear()
  };
}

const loader = createColumnMetadataLoader((connectionId, object) => {
  const params = new URLSearchParams({ objectName: object.name });
  if (object.schemaName) params.set('schemaName', object.schemaName);
  return api<ObjectColumns>(`/metadata/${connectionId}/objects/columns?${params}`);
});

export const loadObjectColumns = loader.load;
export const clearColumnMetadataCache = loader.clear;

export function findColumnMetadata(columns: ObjectColumns['columns'], name: string) {
  const exact = columns.find(column => column.name === name);
  if (exact) return exact;
  const matches = columns.filter(column => column.name.toLowerCase() === name.toLowerCase());
  return matches.length === 1 ? matches[0] : undefined;
}
