import type { ActiveTable, DbObject, ObjectDetail, ObjectRelations, TableData } from './types';
import type { RowHistory } from './tableEditing';
import type { TableQuery } from './tableQuery';
import type { TableRowCountState } from './tableRowCount';

export type ResourceDocument = { key: string; connectionId: number; kind: 'table' | 'object'; object: DbObject; detail?: ObjectDetail | null; dirty: boolean };
export type TableSnapshot = { table: ActiveTable; data: TableData | null; edits: RowHistory; query: TableQuery; page: number; pageSize: number; cursors: Array<string | null>; preview: string[]; rowCount: TableRowCountState; relations: ObjectRelations | null; scrollTop: number };
export const MAX_RESOURCE_DOCUMENTS = 20;
export function resourceDocumentKey(connectionId: number, kind: 'table' | 'object', schema: string | undefined, name: string) {
  return JSON.stringify([connectionId, kind, schema || '', name]);
}
export function updateDocument(documents: ResourceDocument[], document: ResourceDocument) {
  const index = documents.findIndex(item => item.key === document.key);
  if (index < 0) return documents.length < MAX_RESOURCE_DOCUMENTS ? [...documents, document] : documents;
  return documents.map((item, at) => at === index ? { ...item, ...document } : item);
}
