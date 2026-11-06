/**
 * 表关系：人确认 / 否决过的关联，加上从执行历史里挖出来的候选（服务端只给名字对与次数）。
 *
 * <p>它是 JOIN 补全的第二份知识来源（第一份是 joinInference.ts 的命名推断）：确认过的排最前，
 * 历史里常用的次之，否决过的不论来自哪条规则都不再提示。管理入口是管理抽屉的「表关系」分区。</p>
 */
import { api } from './api';
import { AsyncResourceCache } from './asyncResourceCache';
import type { MinedTableRelation, TableRelation, TableRelationPair, TableRelationsResponse } from './types';

/** 与服务端 SqlJoinMining.JoinPair.key 同一个规范：两侧按 (表.列) 小写字典序排好再拼。 */
export function relationKey(tableA: string, columnA: string, tableB: string, columnB: string): string {
  const a = `${tableA}.${columnA}`.toLowerCase();
  const b = `${tableB}.${columnB}`.toLowerCase();
  return a <= b ? `${a}=${b}` : `${b}=${a}`;
}

export function pairKey(pair: TableRelationPair): string {
  return relationKey(pair.leftTable, pair.leftColumn, pair.rightTable, pair.rightColumn);
}

export function relationText(pair: TableRelationPair): string {
  return `${pair.leftTable}.${pair.leftColumn} = ${pair.rightTable}.${pair.rightColumn}`;
}

/** 补全用的形状：确认的、历史里挖到的（带次数）、否决的键。 */
export type RelationKnowledge = {
  confirmed: TableRelation[];
  mined: MinedTableRelation[];
  rejectedKeys: Set<string>;
};

export const EMPTY_RELATION_KNOWLEDGE: RelationKnowledge = { confirmed: [], mined: [], rejectedKeys: new Set() };

export function relationKnowledge(response: TableRelationsResponse): RelationKnowledge {
  return {
    confirmed: response.decided.filter((relation) => relation.status === 'CONFIRMED'),
    mined: response.mined,
    rejectedKeys: new Set(response.decided.filter((relation) => relation.status === 'REJECTED').map(pairKey))
  };
}

/** 两个列表共用的名字筛选：表名或列名包含关键字（不分大小写）。 */
export function filterRelations<T extends TableRelationPair>(items: T[], keyword: string): T[] {
  const needle = keyword.trim().toLowerCase();
  if (!needle) return items;
  return items.filter((item) => [item.leftTable, item.leftColumn, item.rightTable, item.rightColumn]
    .some((part) => part.toLowerCase().includes(needle)));
}

/** 手动添加的输入校验：与服务端规则一致（只填名字本身，不带 Schema、空格、等号），两侧不能是同一列。 */
export function manualRelationProblem(pair: TableRelationPair): string | null {
  const parts: [string, string][] = [
    [pair.leftTable, '左侧表名'], [pair.leftColumn, '左侧列名'], [pair.rightTable, '右侧表名'], [pair.rightColumn, '右侧列名']
  ];
  for (const [value, label] of parts) {
    const trimmed = value.trim();
    if (!trimmed) return `请填写${label}`;
    if (/[\s.=]/.test(trimmed)) return `${label}只填名字本身，不带 Schema 前缀、空格或等号`;
  }
  if (pair.leftTable.trim().toLowerCase() === pair.rightTable.trim().toLowerCase()
    && pair.leftColumn.trim().toLowerCase() === pair.rightColumn.trim().toLowerCase()) {
    return '关联的两侧不能是同一张表的同一列';
  }
  return null;
}

const cache = new AsyncResourceCache<number, RelationKnowledge>({ ttlMs: 2 * 60_000, maxEntries: 50 });

/** 补全时按连接取一次，缓存两分钟；在「表关系」里改了判断之后调 invalidateRelationKnowledge。 */
export function loadRelationKnowledge(connectionId: number): Promise<RelationKnowledge> {
  return cache.load(connectionId, async () => relationKnowledge(await api<TableRelationsResponse>(`/metadata/${connectionId}/table-relations`)));
}

export function invalidateRelationKnowledge(connectionId?: number) {
  if (connectionId == null) cache.clear();
  else cache.delete(connectionId);
}
