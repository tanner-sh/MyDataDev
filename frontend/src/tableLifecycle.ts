import type { ColumnDesign, IndexDesign, ObjectDetail, TableLifecycleRequest } from './types';

type DesignColumn = ColumnDesign & { key: string };
type DesignIndex = IndexDesign & { key: string };

export function fullTableName(schemaName: string | undefined, tableName: string) {
  return schemaName ? `${schemaName}.${tableName}` : tableName;
}

export function createTableRequest(
  schemaName: string | undefined,
  tableName: string,
  columns: DesignColumn[],
  indexes: DesignIndex[],
  primaryKeys: string[],
  confirmation?: string
): TableLifecycleRequest {
  return {
    operation: 'CREATE',
    schemaName: schemaName || undefined,
    tableName: tableName.trim(),
    columns: columns.map(({ key: _key, ...column }) => column),
    indexes: indexes.map(({ key: _key, ...index }) => index),
    primaryKeys,
    confirmation
  };
}

export function tableActionRequest(
  operation: 'RENAME' | 'DROP',
  detail: ObjectDetail,
  newTableName?: string,
  confirmation?: string
): TableLifecycleRequest {
  return {
    operation,
    schemaName: detail.schemaName,
    tableName: detail.name,
    newTableName: operation === 'RENAME' ? newTableName?.trim() : undefined,
    structureVersion: detail.structureVersion,
    confirmation
  };
}
