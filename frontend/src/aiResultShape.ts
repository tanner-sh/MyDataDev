import type { ResultColumn, SqlResult } from './types';

/**
 * 把一次成功执行的结果压成「形状」：行数、耗时，以及每列有多少空值、多少个不同取值。
 *
 * <p>这是给 AI 复盘用的，和 {@link ./aiResultPreview} 是两件事 —— 那边发的是真实数据行，
 * 需要连接开到「结构 + 样本行」档；这里只出计数，一个业务值都不出，所以在「只发结构」档下
 * 就能用。查错真正需要的信号本来也是计数：0 行、某列全空、行数爆炸，说的都是关联写错了，
 * 而不是某一行的值是什么。</p>
 *
 * <p>统计只覆盖当前这一页 —— 不为了复盘再去数据库跑一次聚合。所以文案里必须写清楚基数是
 * 「本页 N 行」，否则模型会把它当成全量结论。</p>
 */

/** 单列的形状。三个字段都是计数，不含任何取值。 */
export type AiColumnShape = {
  label: string;
  typeName: string;
  nullCount: number;
  distinctCount: number;
};

export type AiResultShape = {
  rowCount: number;
  /** 结果被截断或还有下一页：行数是「至少这么多」而不是总数。 */
  hasMore: boolean;
  elapsedMs: number;
  columns: AiColumnShape[];
};

/** 一列最多统计多少个不同取值。超过就不再细数，反正结论已经是「取值很分散」。 */
export const MAX_DISTINCT_TRACKED = 200;
/** 最多描述多少列。宽表全描述只会把上下文撑满，前几十列已经说明结构了。 */
export const MAX_SHAPE_COLUMNS = 40;

export function resultShape(result: SqlResult): AiResultShape | undefined {
  if (!result.resultSet) return undefined;
  const columns = result.columns.slice(0, MAX_SHAPE_COLUMNS);
  return {
    rowCount: result.rows.length,
    hasMore: Boolean(result.truncated) || Boolean(result.page?.hasMore),
    elapsedMs: result.elapsedMs,
    columns: columns.map((column, index) => columnShape(column, result.rows, index))
  };
}

function columnShape(column: ResultColumn, rows: unknown[][], index: number): AiColumnShape {
  let nullCount = 0;
  const seen = new Set<string>();
  for (const row of rows) {
    const value = row[index];
    if (value == null) {
      nullCount += 1;
      continue;
    }
    if (seen.size < MAX_DISTINCT_TRACKED) seen.add(String(value));
  }
  return { label: column.label, typeName: column.typeName, nullCount, distinctCount: seen.size };
}

/**
 * 渲染成给模型看的一段文字。
 *
 * <p>刻意点名几个可诊断的信号，而不是丢一张表让模型自己发现：0 行、整列全空、结果被截断。
 * 这三种恰好对应最常见的三类写错 —— 过滤条件过严、外连接没匹配上、笛卡尔积。</p>
 */
export function resultShapeText(shape: AiResultShape): string {
  const lines: string[] = [];
  lines.push(shape.hasMore
    ? `本页返回 ${shape.rowCount} 行（结果被截断，实际更多），耗时 ${shape.elapsedMs} 毫秒。`
    : `共返回 ${shape.rowCount} 行，耗时 ${shape.elapsedMs} 毫秒。`);
  if (shape.rowCount === 0) {
    lines.push('没有任何行返回。');
  } else if (shape.columns.length > 0) {
    lines.push(`各列在本页 ${shape.rowCount} 行里的分布（只有计数，没有具体取值）：`);
    for (const column of shape.columns) {
      const notes: string[] = [];
      if (column.nullCount === shape.rowCount) notes.push('整列全为空');
      else if (column.nullCount > 0) notes.push(`${column.nullCount} 行为空`);
      if (column.distinctCount === 1) notes.push('只有 1 个不同取值');
      else notes.push(`${column.distinctCount}${column.distinctCount >= MAX_DISTINCT_TRACKED ? '+' : ''} 个不同取值`);
      lines.push(`- ${column.label}（${column.typeName}）：${notes.join('，')}`);
    }
  }
  return lines.join('\n');
}
