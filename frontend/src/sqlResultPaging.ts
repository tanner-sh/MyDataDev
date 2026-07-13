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

export function resizedSqlPage(pageSize: number): SqlPageNavigation {
  return { offset: 0, pageSize, previousOffsets: [] };
}
