import type { SqlPageInfo, SqlPageNavigation } from './types';

export function sqlResultRangeLabel(page: SqlPageInfo, rowCount: number): string {
  if (rowCount === 0) return page.offset === 0 ? '0 / 0' : `${page.offset} / ${page.offset}`;
  const start = page.offset + 1;
  const end = page.offset + rowCount;
  return page.hasMore ? `${start}-${end} / ${end + 1}+` : `${start}-${end} / ${end}`;
}

export function firstSqlPage(page: SqlPageInfo): SqlPageNavigation {
  return { offset: 0, pageSize: page.requestedPageSize, previousOffsets: [] };
}

export function previousSqlPage(page: SqlPageInfo): SqlPageNavigation | null {
  const offsets = page.previousOffsets || [];
  if (offsets.length === 0) return null;
  return {
    offset: offsets[offsets.length - 1],
    pageSize: page.requestedPageSize,
    previousOffsets: offsets.slice(0, -1)
  };
}

export function nextSqlPage(page: SqlPageInfo, rowCount: number): SqlPageNavigation | null {
  if (!page.hasMore || rowCount === 0) return null;
  return {
    offset: page.offset + rowCount,
    pageSize: page.requestedPageSize,
    previousOffsets: [...(page.previousOffsets || []), page.offset]
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
  return { offset: page.offset, pageSize: page.requestedPageSize, previousOffsets: page.previousOffsets || [] };
}

export function resizedSqlPage(pageSize: number): SqlPageNavigation {
  return { offset: 0, pageSize, previousOffsets: [] };
}
