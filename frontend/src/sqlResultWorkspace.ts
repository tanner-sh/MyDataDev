import type { SqlStatementResult } from './types';
import { inferSqlTargetParts } from './queryResultExport';

export type ResultPaneMode = 'normal' | 'collapsed' | 'maximized';
export type ResultPaneAction = 'toggle-collapse' | 'toggle-maximize' | 'new-result';

export function sqlStatementResultLabel(result: SqlStatementResult): string {
  if (result.status === 'FAILED') return `错误 ${result.index}`;
  if (!result.result.resultSet) return `影响 ${result.index}`;

  const sourceName = (result.result.sourceTable?.nameParts || inferSqlTargetParts(result.sql))
    ?.map((part) => part.trim())
    .filter(Boolean)
    .join('.');
  return sourceName ? `结果 ${result.index} · ${sourceName}` : `结果 ${result.index}`;
}

export function nextResultPaneMode(mode: ResultPaneMode, action: ResultPaneAction): ResultPaneMode {
  if (action === 'new-result') return mode === 'collapsed' ? 'normal' : mode;
  if (action === 'toggle-collapse') return mode === 'collapsed' ? 'normal' : 'collapsed';
  return mode === 'maximized' ? 'normal' : 'maximized';
}
