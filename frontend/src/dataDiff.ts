/**
 * 数据对比的纯逻辑：请求整形、同步脚本拼装与展示用的文案。
 *
 * 与 `schemaDiff.ts` 是一对 —— 那边比结构，这边比数据。面板只负责表单和渲染。
 */
import type { DataDiffChange, DataDiffRequest, DataDiffResponse } from './types';

export type DataDiffForm = {
  sourceConnectionId?: number;
  sourceSchema: string;
  sourceTable: string;
  targetConnectionId?: number;
  targetSchema: string;
  /** 留空表示与源表同名。 */
  targetTable: string;
  /** 逗号或换行分隔的匹配字段，留空表示用主键。 */
  keyColumns: string;
  includeDeletes: boolean;
};

export const EMPTY_DATA_DIFF_FORM: DataDiffForm = {
  sourceSchema: '',
  sourceTable: '',
  targetSchema: '',
  targetTable: '',
  keyColumns: '',
  includeDeletes: false
};

export const DATA_DIFF_CHANGE_LABELS: Readonly<Record<DataDiffChange, string>> = {
  ONLY_IN_SOURCE: '目标端缺失',
  ONLY_IN_TARGET: '源端没有',
  DIFFERENT: '值不一致'
};

export function parseKeyColumns(value: string): string[] {
  return value
    .split(/[\n,，、\s]+/)
    .map((item) => item.trim())
    .filter(Boolean)
    .slice(0, 20);
}

export function canRunDataDiff(form: DataDiffForm): boolean {
  if (!form.sourceConnectionId || !form.targetConnectionId || !form.sourceTable.trim()) return false;
  if (form.sourceConnectionId !== form.targetConnectionId) return true;
  // 同一条连接下，要么 Schema 不同，要么表名不同，否则是在和自己比。
  const sameSchema = form.sourceSchema.trim().toLowerCase() === form.targetSchema.trim().toLowerCase();
  const targetTable = (form.targetTable.trim() || form.sourceTable.trim()).toLowerCase();
  return !sameSchema || targetTable !== form.sourceTable.trim().toLowerCase();
}

export function buildDataDiffRequest(form: DataDiffForm): DataDiffRequest {
  if (!form.sourceConnectionId || !form.targetConnectionId) throw new Error('请先选择源和目标连接');
  return {
    sourceConnectionId: form.sourceConnectionId,
    sourceSchema: form.sourceSchema.trim() || undefined,
    sourceTable: form.sourceTable.trim(),
    targetConnectionId: form.targetConnectionId,
    targetSchema: form.targetSchema.trim() || undefined,
    targetTable: form.targetTable.trim() || undefined,
    keyColumns: parseKeyColumns(form.keyColumns),
    includeDeletes: form.includeDeletes
  };
}

/**
 * 同步脚本。
 *
 * <p>抬头里写清楚方向和依据的匹配字段：这份脚本会被粘进 SQL 工作台执行，而「把谁对齐到谁」
 * 弄反的代价是往生产库里写错数据。差异被截断时，警告必须留在脚本里 —— 只在界面上提示一次，
 * 复制走的人就看不到了。</p>
 */
export function buildSyncScript(response: DataDiffResponse): string {
  if (response.script.length === 0) return '';
  const header = [
    `-- 数据同步脚本：把 ${describeTable(response, 'target')} 对齐到 ${describeTable(response, 'source')}`,
    `-- 匹配字段：${response.keyColumns.join(', ')}`,
    '-- 由 MyDataDev 数据对比生成，执行前请逐条复核。',
    ...response.warnings.map((warning) => `-- 注意：${warning}`)
  ];
  return [...header, '', ...response.script].join('\n');
}

export function summarizeDataDiff(response: DataDiffResponse): string {
  const { onlyInSource, onlyInTarget, different, identical } = response.summary;
  if (onlyInSource + onlyInTarget + different === 0) {
    return `两侧数据一致，共比较 ${identical} 行。`;
  }
  return `目标端缺 ${onlyInSource} 行，多 ${onlyInTarget} 行，${different} 行值不一致，${identical} 行一致。`;
}

export function describeTable(response: DataDiffResponse, side: 'source' | 'target'): string {
  const endpoint = side === 'source' ? response.source : response.target;
  const table = side === 'source' ? response.sourceTable : response.targetTable;
  return `${endpoint.connectionName} / ${endpoint.schemaName}.${table}`;
}

/** 差异行里某一列的两侧取值，供表格逐列渲染。 */
export function cellPair(
  response: DataDiffResponse,
  row: DataDiffResponse['rows'][number],
  column: string
): { source?: string | null; target?: string | null } {
  const index = response.columns.indexOf(column);
  if (index < 0) return {};
  return { source: row.sourceValues[index], target: row.targetValues[index] };
}
