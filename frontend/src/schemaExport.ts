/**
 * 对象定义批量导出面板的纯逻辑：候选清单怎么分组、筛选、选择，请求怎么拼，文件叫什么。
 *
 * <p>排序（被依赖的对象在前）在服务端做 —— 依赖要读外键和数据库的依赖字典，这里拿不到。
 * 这边只负责把用户的选择原样交上去，以及把结果说清楚。</p>
 */
import type { SchemaExportCandidate, SchemaExportResponse } from './types';

/** 与服务端 SchemaExportOrdering 的默认顺序一致，界面上的分组也按这个排。 */
export const SCHEMA_EXPORT_KINDS = ['TABLE', 'VIEW', 'MATERIALIZED_VIEW', 'SEQUENCE', 'FUNCTION', 'PROCEDURE', 'TRIGGER'] as const;
export const MAX_SCHEMA_EXPORT_OBJECTS = 500;

const KIND_LABELS: Record<string, string> = {
  TABLE: '表',
  VIEW: '视图',
  MATERIALIZED_VIEW: '物化视图',
  SEQUENCE: '序列',
  FUNCTION: '函数',
  PROCEDURE: '存储过程',
  TRIGGER: '触发器'
};

export function schemaExportKindLabel(kind: string): string {
  return KIND_LABELS[kind] ?? kind;
}

/** 候选项在一次加载内的唯一键：同名的函数可能有多个重载，靠 objectKey 区分。 */
export function candidateKey(candidate: SchemaExportCandidate): string {
  return `${candidate.kind}\u0000${candidate.name}\u0000${candidate.objectKey ?? ''}`;
}

export type KindCount = { kind: string; label: string; total: number; selected: number };

export function kindCounts(candidates: SchemaExportCandidate[], selected: ReadonlySet<string>): KindCount[] {
  const counts = new Map<string, KindCount>();
  for (const candidate of candidates) {
    const entry = counts.get(candidate.kind) ?? { kind: candidate.kind, label: schemaExportKindLabel(candidate.kind), total: 0, selected: 0 };
    entry.total += 1;
    if (selected.has(candidateKey(candidate))) entry.selected += 1;
    counts.set(candidate.kind, entry);
  }
  const rank = (kind: string) => {
    const index = (SCHEMA_EXPORT_KINDS as readonly string[]).indexOf(kind);
    return index < 0 ? SCHEMA_EXPORT_KINDS.length : index;
  };
  return [...counts.values()].sort((left, right) => rank(left.kind) - rank(right.kind));
}

/** 当前类型 + 关键字下可见的候选项。关键字不分大小写，匹配名字或显示名（带参数签名）。 */
export function visibleCandidates(candidates: SchemaExportCandidate[], kind: string | 'ALL', keyword: string): SchemaExportCandidate[] {
  const needle = keyword.trim().toLocaleLowerCase();
  return candidates.filter((candidate) => (kind === 'ALL' || candidate.kind === kind)
    && (!needle || candidate.name.toLocaleLowerCase().includes(needle) || candidate.displayName.toLocaleLowerCase().includes(needle)));
}

/** 把一批候选项整体加入或移出选择；返回新集合，不改原集合。 */
export function toggleCandidates(selected: ReadonlySet<string>, candidates: SchemaExportCandidate[], include: boolean): Set<string> {
  const next = new Set(selected);
  for (const candidate of candidates) {
    if (include) next.add(candidateKey(candidate));
    else next.delete(candidateKey(candidate));
  }
  return next;
}

export function selectionProblem(count: number): string | null {
  if (count === 0) return '请至少选择一个对象';
  if (count > MAX_SCHEMA_EXPORT_OBJECTS) return `一次最多导出 ${MAX_SCHEMA_EXPORT_OBJECTS} 个对象，当前选了 ${count} 个，请分批导出`;
  return null;
}

export function buildSchemaExportRequest(candidates: SchemaExportCandidate[], selected: ReadonlySet<string>, schemaName: string, stripDefiner: boolean) {
  return {
    schemaName: schemaName.trim() || null,
    objects: candidates
      .filter((candidate) => selected.has(candidateKey(candidate)))
      .map((candidate) => ({ kind: candidate.kind, name: candidate.name, objectKey: candidate.objectKey })),
    stripDefiner
  };
}

/** 只有 MySQL 系的定义带 DEFINER，其余库不显示这个开关。 */
export function supportsDefinerStripping(dbType: string | undefined): boolean {
  return ['mysql', 'mariadb', 'oceanbase-mysql'].includes((dbType ?? '').toLowerCase());
}

export function schemaExportFileName(connectionName: string, schemaName: string, stamp: string): string {
  const safe = (value: string) => value.trim().replace(/[\\/:*?"<>|\s]+/g, '_').replace(/^_+|_+$/g, '');
  return [safe(connectionName) || 'connection', safe(schemaName), 'objects', stamp].filter(Boolean).join('-') + '.sql';
}

/** 一句话结论。有失败、有环时要放在最前面说，不能被「已导出 N 个」盖过去。 */
export function schemaExportSummary(response: SchemaExportResponse): { type: 'success' | 'warning'; text: string } {
  const parts = [`已按依赖顺序导出 ${response.items.length} 个对象`];
  if (response.failed > 0) parts.push(`${response.failed} 个读取失败已跳过`);
  if (response.cycles.length > 0) parts.push(`${response.cycles.length} 处循环依赖需要人工处理`);
  return { type: response.failed > 0 || response.cycles.length > 0 ? 'warning' : 'success', text: parts.join('，') };
}
