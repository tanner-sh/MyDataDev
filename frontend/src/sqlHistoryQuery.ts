/**
 * SQL 执行历史的取数策略。
 *
 * 历史在服务端保留数十天，但一次只取回一页。之前的实现固定取 50 条、在浏览器里做关键字
 * 过滤和分页 —— 分页控件让人以为能翻到更早的记录，实际上永远只有最近 50 条，搜索也只在
 * 这 50 条里进行。现在关键字下推到服务端，「加载更多」按页扩大 limit，直到后端的单次上限。
 */

/** 单次取回的条数，也是「加载更多」每次增加的步长。 */
export const SQL_HISTORY_PAGE_SIZE = 50;

/** 与后端 SqlHistoryRepository.MAX_HISTORY_LIMIT 保持一致。 */
export const SQL_HISTORY_MAX_LIMIT = 200;

export const SQL_HISTORY_SEARCH_DEBOUNCE_MS = 300;

export type SqlHistoryQuery = {
  keyword: string;
  limit: number;
};

export const INITIAL_SQL_HISTORY_QUERY: SqlHistoryQuery = { keyword: '', limit: SQL_HISTORY_PAGE_SIZE };

/**
 * 判断是否还可能有更多记录。
 *
 * 只有「取回的条数刚好顶到 limit」才说明可能被截断；同时 limit 已经到后端上限时，
 * 再加载也拿不到更多。
 */
export function hasMoreSqlHistory(loaded: number, limit: number): boolean {
  return loaded >= limit && limit < SQL_HISTORY_MAX_LIMIT;
}

/** 下一次「加载更多」应使用的 limit；已经到顶时返回原值。 */
export function nextSqlHistoryLimit(limit: number): number {
  return Math.min(limit + SQL_HISTORY_PAGE_SIZE, SQL_HISTORY_MAX_LIMIT);
}

/** limit 已经顶到后端上限，且结果仍被截断。 */
export function isSqlHistoryAtLimit(loaded: number, limit: number): boolean {
  return limit >= SQL_HISTORY_MAX_LIMIT && loaded >= SQL_HISTORY_MAX_LIMIT;
}

/** 列表底部的说明文字，必须如实反映「还有没有更多」。 */
export function historyLoadedSummary(loaded: number, hasMore: boolean, atLimit: boolean): string {
  if (atLimit) return `已显示最近 ${loaded} 条（单次上限），请用关键字缩小范围查看更早的记录`;
  if (hasMore) return `已加载 ${loaded} 条，还有更早的记录`;
  return `已加载全部 ${loaded} 条`;
}

/** 构造 /sql/history 的查询串。 */
export function sqlHistoryRequestParams(connectionId: number, query: SqlHistoryQuery): string {
  const params = new URLSearchParams({
    connectionId: String(connectionId),
    limit: String(Math.min(Math.max(query.limit, 1), SQL_HISTORY_MAX_LIMIT))
  });
  const keyword = query.keyword.trim();
  if (keyword) params.set('keyword', keyword);
  return params.toString();
}
