import type { ActiveTable, DbObject, ObjectDetail, ObjectRelations, SchemaObjectSummary, SchemaObjectTemplate, TableData } from './types';
import type { RowHistory } from './tableEditing';
import type { TableQuery } from './tableQuery';
import type { TableRowCountState } from './tableRowCount';

export type ResourceDocumentKind = 'table' | 'object' | 'schemaObject';
/**
 * 视图、存储过程、函数等对象（schemaObject）的页签带着它是怎么打开的：库里已有的对象，或者新建的源码模板。
 * object 字段只是给标签条和关闭确认用的名字与 Schema。
 */
export type SchemaObjectDocumentSource = { object?: SchemaObjectSummary; creation?: SchemaObjectTemplate };
export type ResourceDocument = { key: string; connectionId: number; kind: ResourceDocumentKind; object: DbObject; detail?: ObjectDetail | null; schemaObject?: SchemaObjectDocumentSource; dirty: boolean };
export type TableSnapshot = { table: ActiveTable; data: TableData | null; edits: RowHistory; query: TableQuery; page: number; pageSize: number; cursors: Array<string | null>; preview: string[]; rowCount: TableRowCountState; relations: ObjectRelations | null; scrollTop: number };
export const MAX_RESOURCE_DOCUMENTS = 20;
export function resourceDocumentKey(connectionId: number, kind: ResourceDocumentKind, schema: string | undefined, name: string) {
  return JSON.stringify([connectionId, kind, schema || '', name]);
}

/**
 * 视图、存储过程等对象的文档。键用 objectKey 而不是名字：Oracle 包里的重载、同名的视图与函数
 * 名字一样，objectKey 才分得开。新建的模板还没有 objectKey，按「类型 + 名字」占一个键，
 * 同名模板再点一次「新建」落回同一个页签，不会开出两个改同一个对象的编辑器。
 */
export function schemaObjectDocument(connectionId: number, source: SchemaObjectDocumentSource): ResourceDocument | null {
  if (source.object) {
    const { object } = source;
    return {
      key: resourceDocumentKey(connectionId, 'schemaObject', object.schemaName, object.objectKey),
      connectionId,
      kind: 'schemaObject',
      object: { schemaName: object.schemaName, name: object.name, type: object.kind, columns: [], indexes: [] },
      schemaObject: { object },
      dirty: false
    };
  }
  if (source.creation) {
    const { creation } = source;
    return {
      key: resourceDocumentKey(connectionId, 'schemaObject', creation.schemaName, `new:${creation.kind}:${creation.objectName}`),
      connectionId,
      kind: 'schemaObject',
      object: { schemaName: creation.schemaName, name: creation.objectName, type: creation.kind, columns: [], indexes: [] },
      schemaObject: { creation },
      dirty: false
    };
  }
  return null;
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
