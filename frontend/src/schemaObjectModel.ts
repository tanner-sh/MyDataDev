import type { CompilationError, DatabaseCapabilities, SchemaObjectCapability, SchemaObjectKind, SchemaObjectSummary } from './types';

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

/**
 * 编译错误的展示。
 *
 * <p>「对象建出来了但编译不过」是 Oracle 独有的一种成功：语句不报错，对象以不可用状态留在
 * 库里。界面上这件事必须比「已创建」更响 —— 否则用户要到某天有人调用它时才知道。</p>
 */

/** 位置 + 原文。位置缺失时不编造一个「第 0 行」。 */
export function formatCompilationError(error: CompilationError): string {
  const where = error.line == null
    ? ''
    : `第 ${error.line} 行${error.position == null ? '' : ` 第 ${error.position} 列`}：`;
  return `${where}${(error.text || '').trim()}`;
}

/**
 * 顶部那句结论。
 *
 * <p>点名第一条：一条语法错误往往级联出十几行，后面那些指的都是同一个原因，而用户要找的是
 * 从哪一行开始改。</p>
 */
export function compilationErrorSummary(errors: CompilationError[]): string {
  if (errors.length === 0) return '';
  const first = errors[0];
  const where = first.line == null ? '' : `，从第 ${first.line} 行开始`;
  return errors.length === 1
    ? `对象已创建但编译未通过${where}，当前不可用。`
    : `对象已创建但编译未通过，共 ${errors.length} 条错误${where}，当前不可用。`;
}
