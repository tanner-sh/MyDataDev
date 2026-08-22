/**
 * 连接档案在列表里的组织方式。
 *
 * 连接一多，扁平列表就不够用了：找一条连接要靠肉眼扫名字。分组、标签、备注都是纯展示信息，
 * 判断逻辑放在这里，组件只负责渲染。
 */

import type { Connection } from './types';

/** 没有填分组的连接归到这一组，始终排在最后。 */
export const UNGROUPED_LABEL = '未分组';

export type ConnectionGroup = { name: string; connections: Connection[] };

export function connectionGroupName(connection: Connection): string {
  const name = connection.groupName?.trim();
  return name || UNGROUPED_LABEL;
}

/**
 * 按分组切分，保留传入顺序。
 *
 * 传入顺序已经承载了「当前连接与收藏优先」的排序结果，这里不再二次排序，只把同组的聚到一起：
 * 第一次出现的分组排在前面，「未分组」永远垫底。
 */
export function groupConnections(connections: Connection[]): ConnectionGroup[] {
  const groups = new Map<string, Connection[]>();
  for (const connection of connections) {
    const name = connectionGroupName(connection);
    const bucket = groups.get(name);
    if (bucket) bucket.push(connection);
    else groups.set(name, [connection]);
  }
  const ungrouped = groups.get(UNGROUPED_LABEL);
  groups.delete(UNGROUPED_LABEL);
  const result = [...groups.entries()].map(([name, items]) => ({ name, connections: items }));
  if (ungrouped) result.push({ name: UNGROUPED_LABEL, connections: ungrouped });
  return result;
}

/** 列表筛选器里可选的全部标签，按出现次数降序、同频按字典序。 */
export function availableTags(connections: Connection[]): string[] {
  const counts = new Map<string, number>();
  for (const connection of connections) {
    for (const tag of connection.tags || []) {
      counts.set(tag, (counts.get(tag) || 0) + 1);
    }
  }
  return [...counts.entries()]
    .sort((left, right) => right[1] - left[1] || left[0].localeCompare(right[0], 'zh-CN'))
    .map(([tag]) => tag);
}

/** 选中多个标签时取交集：标签是用来收窄范围的。 */
export function matchesTags(connection: Connection, selected: string[]): boolean {
  if (selected.length === 0) return true;
  const owned = new Set(connection.tags || []);
  return selected.every((tag) => owned.has(tag));
}

/**
 * 搜索命中范围：名称、地址、分组、标签、备注。
 *
 * 备注常写着「谁负责、什么时候能停」，按它搜到连接比按 JDBC 地址搜更常用。
 */
export function matchesKeyword(connection: Connection, keyword: string): boolean {
  const normalized = keyword.trim().toLocaleLowerCase();
  if (!normalized) return true;
  const haystack = [
    connection.name,
    connection.jdbcUrl,
    connection.groupName,
    connection.description,
    ...(connection.tags || [])
  ];
  return haystack.some((value) => value?.toLocaleLowerCase().includes(normalized));
}

/** 连接卡上要显示的档案摘要，没有内容时返回空串，组件据此决定要不要留出这一行。 */
export function connectionProfileSummary(connection: Connection): string {
  const parts: string[] = [];
  if (connection.defaultSchema?.trim()) parts.push(`默认 ${connection.defaultSchema.trim()}`);
  if (connection.initSql?.trim()) parts.push('会话初始化');
  return parts.join(' · ');
}
