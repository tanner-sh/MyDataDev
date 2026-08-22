/**
 * 全局对象搜索的展示逻辑。
 *
 * 资源管理器的搜索必须先在下拉里选定一种对象类型再搜 —— 想找一个记不清是视图还是函数的
 * 对象，得把类型挨个切一遍。这里把一次关键字的结果按类型分组呈现，供命令面板使用。
 */

export const OBJECT_SEARCH_DEBOUNCE_MS = 200;
export const OBJECT_SEARCH_LIMIT = 40;

export type ObjectSearchHit = {
  kind: string;
  schemaName?: string;
  name: string;
  displayName: string;
  subtype?: string;
  /** 只有 schema 对象才有；表和视图靠 schema + name 定位。 */
  objectKey?: string;
};

export type ObjectSearchResult = {
  namespaceKind: string;
  schemaName?: string;
  hits: ObjectSearchHit[];
  truncated: boolean;
};

const KIND_LABELS: Readonly<Record<string, string>> = {
  TABLE: '表',
  VIEW: '视图',
  MATERIALIZED_VIEW: '物化视图',
  SEQUENCE: '序列',
  TRIGGER: '触发器',
  PROCEDURE: '存储过程',
  FUNCTION: '函数'
};

/** 命中列表按这个顺序分组，最常用的排在前面。 */
const KIND_ORDER: readonly string[] = ['TABLE', 'VIEW', 'MATERIALIZED_VIEW', 'PROCEDURE', 'FUNCTION', 'TRIGGER', 'SEQUENCE'];

export function objectKindLabel(kind: string): string {
  return KIND_LABELS[kind] || kind;
}

/** 表能直接打开数据，其它类型只能看定义。 */
export function opensTableData(hit: ObjectSearchHit): boolean {
  return hit.kind === 'TABLE';
}

/** 表和视图走对象详情，其余交给资源管理器按类型定位。 */
export function isClassicObject(hit: ObjectSearchHit): boolean {
  return hit.kind === 'TABLE' || hit.kind === 'VIEW';
}

export function objectSearchHitKey(hit: ObjectSearchHit): string {
  return hit.objectKey || `${hit.kind}:${hit.schemaName || ''}.${hit.name}`;
}

/** 按类型排序后拍平，让键盘上下键的顺序和肉眼看到的分组一致。 */
export function orderObjectSearchHits(hits: ObjectSearchHit[]): ObjectSearchHit[] {
  const rank = (kind: string) => {
    const index = KIND_ORDER.indexOf(kind);
    return index < 0 ? KIND_ORDER.length : index;
  };
  return hits
    .map((hit, index) => ({ hit, index }))
    .sort((left, right) => rank(left.hit.kind) - rank(right.hit.kind) || left.index - right.index)
    .map(({ hit }) => hit);
}

export type ObjectSearchGroup = { kind: string; label: string; hits: ObjectSearchHit[] };

export function groupObjectSearchHits(hits: ObjectSearchHit[]): ObjectSearchGroup[] {
  const groups = new Map<string, ObjectSearchHit[]>();
  for (const hit of orderObjectSearchHits(hits)) {
    const bucket = groups.get(hit.kind);
    if (bucket) bucket.push(hit);
    else groups.set(hit.kind, [hit]);
  }
  return [...groups.entries()].map(([kind, kindHits]) => ({ kind, label: objectKindLabel(kind), hits: kindHits }));
}

/** 上下键在拍平后的列表里移动，越界时回绕。 */
export function moveObjectSearchSelection(current: number, total: number, delta: number): number {
  if (total <= 0) return -1;
  if (current < 0) return delta > 0 ? 0 : total - 1;
  return (current + delta + total) % total;
}

export function objectSearchRequestParams(keyword: string, schema?: string, limit = OBJECT_SEARCH_LIMIT): string {
  const params = new URLSearchParams({ limit: String(limit) });
  const normalized = keyword.trim();
  if (normalized) params.set('keyword', normalized);
  if (schema) params.set('schema', schema);
  return params.toString();
}

export function objectSearchSummary(count: number, truncated: boolean, keyword: string): string {
  if (count === 0) return keyword.trim() ? '没有匹配的数据库对象' : '输入关键字搜索当前 Schema 下的所有对象';
  return truncated ? `显示前 ${count} 个匹配对象，请输入更精确的关键字` : `共 ${count} 个匹配对象`;
}
