/**
 * 外键导航。
 *
 * 对象详情的「关系」页此前只把外键渲染成卡片，点不进去；数据网格里看到一个 CUSTOMER_ID
 * 也没法一键跳到对应的客户。后端 /objects/relations 早就返回了完整的列映射，这里把它变成
 * 可点的路径。
 */

import type { ObjectRelation } from './types';

/** 一条外键在某个方向上的落点。 */
export type RelationTarget = {
  schemaName?: string;
  tableName: string;
  /** 目标表上参与关联的列。 */
  columnName: string;
  /** 当前表上对应的列，用来取值构造筛选。 */
  sourceColumnName: string;
};

/**
 * imported（当前表引用别人）跳到被引用的主键表；
 * exported（别人引用当前表）跳到引用方的外键表。
 */
export function relationTarget(relation: ObjectRelation, direction: 'imported' | 'exported'): RelationTarget {
  return direction === 'imported'
    ? {
        schemaName: relation.pkSchemaName,
        tableName: relation.pkTableName,
        columnName: relation.pkColumnName,
        sourceColumnName: relation.fkColumnName
      }
    : {
        schemaName: relation.fkSchemaName,
        tableName: relation.fkTableName,
        columnName: relation.fkColumnName,
        sourceColumnName: relation.pkColumnName
      };
}

export function relationTargetLabel(target: RelationTarget): string {
  return target.schemaName ? `${target.schemaName}.${target.tableName}` : target.tableName;
}

/**
 * 数据网格里可跳转的外键列：列名 → 目标。
 *
 * 只取 imported 方向：当前行的这一列有具体的值，可以直接拿去筛目标表。exported 方向要反过来
 * 用当前行的主键筛别人，那属于「查看引用了我的记录」，由另一个入口承担。
 */
export function foreignKeyColumns(relations?: { importedKeys: ObjectRelation[] } | null): Map<string, RelationTarget> {
  const result = new Map<string, RelationTarget>();
  for (const relation of relations?.importedKeys || []) {
    // 复合外键的每一列都能单独跳，取第一个映射即可；重复列名以先出现的为准。
    if (result.has(relation.fkColumnName)) continue;
    result.set(relation.fkColumnName, relationTarget(relation, 'imported'));
  }
  return result;
}

/** SQL 字面量：数字原样，其余按字符串转义单引号。 */
export function sqlLiteral(value: unknown): string {
  if (value == null) return 'NULL';
  if (typeof value === 'number' || typeof value === 'bigint') return String(value);
  if (typeof value === 'boolean') return value ? 'TRUE' : 'FALSE';
  const text = String(value);
  // 纯数字文本也当数字处理：多数驱动把 BIGINT 序列化成字符串。
  if (/^-?\d+(\.\d+)?$/.test(text)) return text;
  return `'${text.split("'").join("''")}'`;
}

function quote(identifier: string): string {
  return `"${identifier.replace(/"/g, '""')}"`;
}

/**
 * 生成「跳到关联记录」的查询。
 *
 * 用 SQL 而不是直接打开表数据，是因为表数据工作区没有按任意列筛选的入口；生成的语句也让
 * 用户看得见自己将要执行什么。
 */
export function relationJumpSql(target: RelationTarget, value: unknown): string {
  const table = target.schemaName ? `${quote(target.schemaName)}.${quote(target.tableName)}` : quote(target.tableName);
  return `SELECT * FROM ${table} WHERE ${quote(target.columnName)} = ${sqlLiteral(value)}`;
}

/** 外键值为空时没有可跳的目标。 */
export function canJumpToRelation(value: unknown): boolean {
  return value != null && value !== '';
}

export function relationJumpTooltip(target: RelationTarget, value: unknown): string {
  if (!canJumpToRelation(value)) return '该外键为空，没有可跳转的记录';
  return `查看 ${relationTargetLabel(target)} 中 ${target.columnName} = ${String(value)} 的记录`;
}
