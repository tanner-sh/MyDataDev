import type {
  SqlPageInfo,
  SqlPageNavigation,
  SqlResultFilterRequest,
  SqlResultSort,
  SqlSortDirection
} from './types';

/**
 * 当前生效的排序。
 *
 * <p>每次翻页都要把它原样带上：排序是下推进 SQL 的，navigation 里丢了它，翻一页顺序就换了 ——
 * 而用户只是点了「下一批」。</p>
 */
export function activeSqlSort(page: SqlPageInfo): SqlResultSort | null {
  if (!page.sortColumn) return null;
  return { column: page.sortColumn, direction: page.sortDirection === 'DESC' ? 'DESC' : 'ASC' };
}

/** 当前生效的筛选。和排序一样，翻页时必须原样带上。 */
export function activeSqlFilters(page: SqlPageInfo): SqlResultFilterRequest[] {
  return page.filters ? [...page.filters] : [];
}

export function sqlResultRangeLabel(page: SqlPageInfo, rowCount: number): string {
  if (rowCount === 0) return page.offset === 0 ? '0 / 0' : `${page.offset} / ${page.offset}`;
  const start = page.offset + 1;
  const end = page.offset + rowCount;
  return page.hasMore ? `${start}-${end} / ${end + 1}+` : `${start}-${end} / ${end}`;
}

export function firstSqlPage(page: SqlPageInfo): SqlPageNavigation {
  return {
    offset: 0,
    pageSize: page.requestedPageSize,
    previousOffsets: [],
    sort: activeSqlSort(page),
    filters: activeSqlFilters(page)
  };
}

/**
 * 改排序：回到第一批。
 *
 * <p>换了顺序，第 3 页就不再是原来那批行了 —— 停在原偏移上等于把用户丢在结果集中间的某个
 * 随机位置。传空列表示取消排序，回到查询本来的顺序。</p>
 */
export function sortedSqlPage(page: SqlPageInfo, column: string | null, direction: SqlSortDirection): SqlPageNavigation {
  return {
    offset: 0,
    pageSize: page.requestedPageSize,
    previousOffsets: [],
    sort: column ? { column, direction } : null,
    filters: activeSqlFilters(page)
  };
}

/**
 * 改筛选：同样回到第一批。
 *
 * <p>换了筛选条件，第 3 页就不再是原来那批行 —— 与改排序同理。排序保持不变：用户改的是
 * 「看哪些行」，不是「怎么排」。</p>
 */
export function filteredSqlPage(page: SqlPageInfo, filters: SqlResultFilterRequest[]): SqlPageNavigation {
  return {
    offset: 0,
    pageSize: page.requestedPageSize,
    previousOffsets: [],
    sort: activeSqlSort(page),
    filters
  };
}

export function previousSqlPage(page: SqlPageInfo): SqlPageNavigation | null {
  const offsets = page.previousOffsets || [];
  if (offsets.length === 0) return null;
  return {
    offset: offsets[offsets.length - 1],
    pageSize: page.requestedPageSize,
    previousOffsets: offsets.slice(0, -1),
    sort: activeSqlSort(page),
    filters: activeSqlFilters(page)
  };
}

export function nextSqlPage(page: SqlPageInfo, rowCount: number): SqlPageNavigation | null {
  if (!page.hasMore || rowCount === 0) return null;
  return {
    offset: page.offset + rowCount,
    pageSize: page.requestedPageSize,
    previousOffsets: [...(page.previousOffsets || []), page.offset],
    sort: activeSqlSort(page),
    filters: activeSqlFilters(page)
  };
}

/**
 * 重新加载当前这一页。
 *
 * <p>结果就地编辑提交后必须重查：表格会清空本地编辑态，不重查的话界面继续显示旧值，用户再改
 * 同一行时带上去的原值已经过期，会被乐观锁挡下来。偏移和翻页历史保持不变，用户不会被弹回
 * 第一页。</p>
 */
export function currentSqlPage(page: SqlPageInfo): SqlPageNavigation {
  return {
    offset: page.offset,
    pageSize: page.requestedPageSize,
    previousOffsets: page.previousOffsets || [],
    sort: activeSqlSort(page),
    filters: activeSqlFilters(page)
  };
}

export function resizedSqlPage(pageSize: number, page?: SqlPageInfo): SqlPageNavigation {
  return {
    offset: 0,
    pageSize,
    previousOffsets: [],
    sort: page ? activeSqlSort(page) : null,
    filters: page ? activeSqlFilters(page) : []
  };
}
