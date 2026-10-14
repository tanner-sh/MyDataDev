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
/**
 * 把临时键下的文档换成规范键：从 SQL 里点进来的名字是用户写的（Oracle 上常是小写），
 * 服务端按大小写不敏感解析后回的是字典里的规范名。键不跟着换的话，活动对象算出的键
 * 和文档键对不上，没有一个面板是 active 的，工作区就白了。
 * 原位替换保持标签顺序；规范键已经开着时丢掉临时项、合并到那一项上。
 */
export function rekeyDocument(documents: ResourceDocument[], fromKey: string, document: ResourceDocument) {
  if (fromKey === document.key) return updateDocument(documents, document);
  if (documents.some(item => item.key === document.key)) {
    return updateDocument(documents.filter(item => item.key !== fromKey), document);
  }
  const index = documents.findIndex(item => item.key === fromKey);
  if (index < 0) return updateDocument(documents, document);
  return documents.map((item, at) => at === index ? document : item);
}
