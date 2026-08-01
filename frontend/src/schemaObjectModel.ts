import type { DatabaseCapabilities, SchemaObjectCapability, SchemaObjectKind, SchemaObjectSummary } from './types';

export const SCHEMA_OBJECT_ORDER: SchemaObjectKind[] = ['VIEW', 'MATERIALIZED_VIEW', 'SEQUENCE', 'TRIGGER', 'PROCEDURE', 'FUNCTION'];
export type ExplorerObjectKind = 'TABLE' | SchemaObjectKind;
export type ExplorerObjectCount = {
  loaded: number;
  total?: number;
  hasMore?: boolean;
  loading?: boolean;
  cachedAt?: string;
  cacheHit?: boolean;
};

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

export function explorerObjectKinds(capabilities?: DatabaseCapabilities): ExplorerObjectKind[] {
  return ['TABLE', ...schemaObjectCapabilities(capabilities).map((capability) => capability.kind)];
}

export function normalizeExplorerObjectKind(kind: ExplorerObjectKind, capabilities?: DatabaseCapabilities): ExplorerObjectKind {
  return explorerObjectKinds(capabilities).includes(kind) ? kind : 'TABLE';
}

export function explorerObjectKindLabel(kind: ExplorerObjectKind, managesViews = false) {
  if (kind === 'TABLE') return managesViews ? '表' : '表与视图';
  return schemaObjectKindLabel(kind);
}

export function explorerObjectCountLabel(count?: ExplorerObjectCount) {
  if (!count || count.loading && count.loaded === 0 && count.total == null) return '…';
  if (count.total != null) return String(count.total);
  return `${count.loaded}${count.hasMore ? '+' : ''}`;
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
