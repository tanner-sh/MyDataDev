import type { DatabaseCapabilities, SchemaObjectCapability, SchemaObjectKind, SchemaObjectSummary } from './types';

export const SCHEMA_OBJECT_ORDER: SchemaObjectKind[] = ['VIEW', 'MATERIALIZED_VIEW', 'SEQUENCE', 'TRIGGER', 'PROCEDURE', 'FUNCTION'];

export function schemaObjectKindLabel(kind: SchemaObjectKind) {
  return ({
    VIEW: '视图',
    MATERIALIZED_VIEW: '物化视图',
    SEQUENCE: '序列',
    TRIGGER: '触发器',
    PROCEDURE: '存储过程',
    FUNCTION: '函数'
  } as const)[kind];
}

export function schemaObjectCapabilities(capabilities?: DatabaseCapabilities): SchemaObjectCapability[] {
  const available = capabilities?.schemaObjects || [];
  return SCHEMA_OBJECT_ORDER.flatMap((kind) => {
    const capability = available.find((item) => item.kind === kind);
    return capability ? [capability] : [];
  });
}

export function schemaObjectConfirmationTarget(object: Pick<SchemaObjectSummary, 'schemaName' | 'name' | 'displayName' | 'kind'>) {
  const name = object.kind === 'PROCEDURE' || object.kind === 'FUNCTION' ? object.displayName : object.name;
  return object.schemaName ? `${object.schemaName}.${name}` : name;
}

export function schemaObjectDisplayStatus(status?: string) {
  if (!status) return undefined;
  if (status.toUpperCase() === 'ENABLED') return '已启用';
  if (status.toUpperCase() === 'DISABLED') return '已禁用';
  if (status.toUpperCase() === 'VALID') return '有效';
  if (status.toUpperCase() === 'INVALID') return '无效';
  return status;
}
