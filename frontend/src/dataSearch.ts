/**
 * 全库数据检索的纯逻辑：表单整形与结论文案。
 *
 * <p>这里最要紧的一件事是把「扫完了」和「扫到一半停了」说清楚。检索结果天然会被当成
 * 「全库就这些」，而一次触了上限的扫描给出这个印象，比没有这个功能更糟 —— 所以
 * `summarizeDataSearch` 永远先说范围，再说命中。</p>
 */
import type { DataSearchRequest, DataSearchResponse, DataSearchTableHit } from './types';

export type DataSearchMode = DataSearchRequest['mode'];

export type DataSearchForm = {
  connectionId?: number;
  schemaName: string;
  keyword: string;
  mode: DataSearchMode;
  maxTables: number;
  rowsPerTable: number;
  budgetSeconds: number;
};

export const DATA_SEARCH_DEFAULTS = {
  maxTables: 300,
  rowsPerTable: 5,
  budgetSeconds: 20
} as const;

export const EMPTY_DATA_SEARCH_FORM: DataSearchForm = {
  schemaName: '',
  keyword: '',
  mode: 'CONTAINS',
  ...DATA_SEARCH_DEFAULTS
};

export const DATA_SEARCH_MODE_OPTIONS: ReadonlyArray<{ value: DataSearchMode; label: string; hint: string }> = [
  { value: 'CONTAINS', label: '包含', hint: '列的文本里出现这段内容就算命中；% 和 _ 按字面量处理' },
  { value: 'EQUALS', label: '完全相等', hint: '整列的值与输入完全一致才算命中' }
];

export function canRunDataSearch(form: DataSearchForm): boolean {
  return Boolean(form.connectionId) && form.keyword.trim().length > 0;
}

export function buildDataSearchRequest(form: DataSearchForm): DataSearchRequest {
  if (!form.connectionId) throw new Error('请先选择连接');
  const keyword = form.keyword.trim();
  if (!keyword) throw new Error('请输入要检索的内容');
  return {
    connectionId: form.connectionId,
    schemaName: form.schemaName.trim() || undefined,
    keyword,
    mode: form.mode,
    maxTables: form.maxTables,
    rowsPerTable: form.rowsPerTable,
    budgetSeconds: form.budgetSeconds
  };
}

/**
 * 一句话结论。
 *
 * <p>范围写在命中前面，而且没扫完时不说「共」——「在 3 张表里找到」听起来像是全库的结论，
 * 「已扫描的 40 张表里找到 3 张」才是这次实际问出来的东西。</p>
 */
export function summarizeDataSearch(response: DataSearchResponse): string {
  const scope = response.complete
    ? `扫描了全部 ${response.scannedTables} 张表`
    : `已扫描 ${response.scannedTables} 张表（未扫完）`;
  if (response.matchedTables === 0) return `${scope}，没有找到「${response.keyword}」。`;
  return `${scope}，在 ${response.matchedTables} 张表里找到「${response.keyword}」。`;
}

/** 扫描没跑完时那句必须显眼的提示；跑完了返回空串。 */
export function incompleteNotice(response: DataSearchResponse): string {
  if (response.complete) return '';
  const reason = response.stopReason ? `${response.stopReason}。` : '';
  return `${reason}还有表没有扫到，这次的结果不能当成全库的结论 —— 缩小 Schema 范围或调高上限后再试。`;
}

export function tableHitLabel(hit: DataSearchTableHit): string {
  return hit.schemaName ? `${hit.schemaName}.${hit.tableName}` : hit.tableName;
}

/** 一张表里命中的样本行数；样本之外还有更多时要写成「至少」，不能报一个准确的假数字。 */
export function describeHitRows(hit: DataSearchTableHit): string {
  return hit.truncated ? `至少 ${hit.matchedRows} 行` : `${hit.matchedRows} 行`;
}

export function sqlTabTitle(hit: DataSearchTableHit): string {
  return `检索 ${hit.tableName}`;
}
