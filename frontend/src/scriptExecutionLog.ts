/**
 * 多语句执行的结果怎么摆。
 *
 * <p>以前每条语句一个结果标签：500 条 INSERT 就是 500 个「影响 N」的标签，标签条拖不动，
 * 页面也卡。现在不返回结果集的语句合进一个「执行日志」标签，一行一条；只有查询结果（要看表格）
 * 和失败的那条（要看报错）还单独成标签。</p>
 */
import type { SqlStatementResult } from './types';

export const SCRIPT_LOG_KEY = 'script-log';
/** 查询结果标签的上限：再多就只在日志里列出，标签条本身成了负担。 */
export const MAX_RESULT_TABS = 30;

export type ScriptLogEntry = {
  index: number;
  summary: string;
  status: 'SUCCESS' | 'FAILED';
  resultSet: boolean;
  /** 不返回结果集的语句的影响行数；驱动给不出时为 null。 */
  affectedRows: number | null;
  rows: number | null;
  elapsedMs: number;
  errorMessage?: string | null;
};

/** 日志里一条语句的摘要：第一行不是注释的内容，压成一行。 */
export function statementSummary(sql: string, max = 160): string {
  const meaningful = sql.split(/\r?\n/).map((line) => line.trim()).filter((line) => line && !line.startsWith('--'));
  const text = (meaningful.join(' ') || sql.trim()).replace(/\s+/g, ' ');
  return text.length > max ? `${text.slice(0, max - 1)}…` : text;
}

export function buildScriptLog(results: SqlStatementResult[]): ScriptLogEntry[] {
  return results.map((item) => ({
    index: item.index,
    summary: statementSummary(item.sql),
    status: item.status,
    resultSet: Boolean(item.result?.resultSet),
    affectedRows: !item.result?.resultSet && item.status === 'SUCCESS' && item.result && item.result.affectedRows >= 0 ? item.result.affectedRows : null,
    rows: item.result?.resultSet ? item.result.rows.length : null,
    elapsedMs: item.result?.elapsedMs ?? 0,
    errorMessage: item.errorMessage
  }));
}

/** 只有一条语句时照旧直接显示它的结果；多于一条才需要日志。 */
export function needsScriptLog(results: SqlStatementResult[]): boolean {
  return results.length > 1;
}

/** 单独成标签的语句：查询结果（最多 MAX_RESULT_TABS 个）与失败的那条。 */
export function tabbedResults(results: SqlStatementResult[]): SqlStatementResult[] {
  let queries = 0;
  return results.filter((item) => {
    if (item.status === 'FAILED') return true;
    if (!item.result?.resultSet) return false;
    queries += 1;
    return queries <= MAX_RESULT_TABS;
  });
}

export function hiddenResultTabCount(results: SqlStatementResult[]): number {
  const queries = results.filter((item) => item.status === 'SUCCESS' && item.result?.resultSet).length;
  return Math.max(0, queries - MAX_RESULT_TABS);
}

export type ScriptLogSummary = { total: number; succeeded: number; failed: number; queries: number; affectedRows: number; elapsedMs: number };

export function summarizeScriptLog(entries: ScriptLogEntry[]): ScriptLogSummary {
  return entries.reduce<ScriptLogSummary>((summary, entry) => ({
    total: summary.total + 1,
    succeeded: summary.succeeded + (entry.status === 'SUCCESS' ? 1 : 0),
    failed: summary.failed + (entry.status === 'FAILED' ? 1 : 0),
    queries: summary.queries + (entry.resultSet ? 1 : 0),
    affectedRows: summary.affectedRows + (entry.affectedRows ?? 0),
    elapsedMs: summary.elapsedMs + entry.elapsedMs
  }), { total: 0, succeeded: 0, failed: 0, queries: 0, affectedRows: 0, elapsedMs: 0 });
}

/** 记下的激活标签可能是一条现在已经并进日志的语句：那就落到日志上，而不是一个不存在的标签。 */
export function resolveResultTabKey(activeKey: string | undefined, tabKeys: string[]): string | undefined {
  if (activeKey && tabKeys.includes(activeKey)) return activeKey;
  return tabKeys[0];
}
