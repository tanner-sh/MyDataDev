/**
 * ER 图的布局建模。
 *
 * 只负责「哪个方块摆在哪、连线怎么走」，不碰 DOM —— 渲染在 components/ErDiagram.tsx。
 * 和 resultChart.ts 一样是自绘 SVG：首屏体积预算是硬约束，为一张图引入图库不划算。
 *
 * 布局是确定性的：同一份输入永远得到同一张图。力导向布局每次刷新都会抖，读者刚建立起来的
 * 空间记忆就没了；ER 图的价值恰恰在于「上次那张表在左上角」这种记忆。
 */
import type { DiagramRelation, DiagramTable, SchemaDiagram } from './types';

export const NODE_WIDTH = 200;
export const NODE_HEADER_HEIGHT = 30;
export const NODE_ROW_HEIGHT = 20;
/** 方框底部留给「另有 N 列」那一行。 */
export const NODE_FOOTER_HEIGHT = 18;
export const LAYER_GAP = 96;
export const NODE_GAP = 24;
export const CANVAS_PADDING = 24;
/** 一个方框里最多列几行键列，超出的并进底部计数。 */
export const MAX_VISIBLE_KEY_COLUMNS = 8;

export type ErNode = {
  name: string;
  schemaName: string;
  columns: DiagramTable['keyColumns'];
  hiddenColumnCount: number;
  columnCount: number;
  x: number;
  y: number;
  width: number;
  height: number;
  layer: number;
};

export type ErEdge = {
  /** 同一个外键约束跨多列时只画一根线，id 用约束名加两端表名保证稳定。 */
  id: string;
  from: string;
  to: string;
  columns: Array<{ fromColumn: string; toColumn: string }>;
  /** 折线顶点，已是画布坐标。 */
  points: Array<{ x: number; y: number }>;
  /** 自引用（表指向自己）单独画成一个回环。 */
  selfReference: boolean;
};

export type ErModel = {
  nodes: ErNode[];
  edges: ErEdge[];
  width: number;
  height: number;
  /** 参与了至少一条关系的表数量，用来提示「其余都是孤立表」。 */
  connectedTables: number;
};

function key(value: string): string {
  return value.trim().toLowerCase();
}

function nodeHeight(visibleColumns: number, hasHidden: boolean): number {
  return NODE_HEADER_HEIGHT + visibleColumns * NODE_ROW_HEIGHT + (hasHidden ? NODE_FOOTER_HEIGHT : 0);
}

/**
 * 把同一个外键约束的多列合成一条边。
 *
 * 复合外键在 JDBC 元数据里是每列一行；照着画会在两张表之间叠出好几根重合的线。
 */
export function mergeRelations(relations: DiagramRelation[]): Array<{
  id: string;
  from: string;
  to: string;
  columns: Array<{ fromColumn: string; toColumn: string }>;
}> {
  const merged = new Map<string, { id: string; from: string; to: string; columns: Array<{ fromColumn: string; toColumn: string }> }>();
  for (const relation of relations) {
    // 约束名可能为空（部分驱动不返回），退化成用两端表名做键。
    const id = `${relation.constraintName || 'fk'}:${key(relation.fromTable)}->${key(relation.toTable)}`;
    const existing = merged.get(id);
    if (existing) {
      existing.columns.push({ fromColumn: relation.fromColumn, toColumn: relation.toColumn });
      continue;
    }
    merged.set(id, {
      id,
      from: relation.fromTable,
      to: relation.toTable,
      columns: [{ fromColumn: relation.fromColumn, toColumn: relation.toColumn }]
    });
  }
  return [...merged.values()];
}

/**
 * 给每张表分层：被引用得最"深"的表在左，引用别人的表在右。
 *
 * 层号取「到无出边表的最长路径」，读起来就是数据流向 —— 字典表在左，业务表在右。
 * 外键成环时（自引用、互相引用）沿用已算出的层号，不再往下钻，否则会无限递归。
 */
export function assignLayers(
  tables: DiagramTable[],
  edges: Array<{ from: string; to: string }>
): Map<string, number> {
  const targets = new Map<string, string[]>();
  for (const table of tables) targets.set(key(table.name), []);
  for (const edge of edges) {
    const from = key(edge.from);
    const to = key(edge.to);
    if (from === to) continue;
    targets.get(from)?.push(to);
  }

  const layers = new Map<string, number>();
  const visiting = new Set<string>();
  function depth(name: string): number {
    const cached = layers.get(name);
    if (cached !== undefined) return cached;
    if (visiting.has(name)) return 0;
    visiting.add(name);
    let best = 0;
    for (const target of targets.get(name) || []) {
      best = Math.max(best, depth(target) + 1);
    }
    visiting.delete(name);
    layers.set(name, best);
    return best;
  }
  for (const table of tables) depth(key(table.name));
  return layers;
}

/**
 * 层内排序，用重心法减少连线交叉。
 *
 * 每个节点的位置取它在上一层邻居的平均位置；没有邻居的保持原有顺序排在后面。
 * 一遍就够：ER 图的层通常很浅，多轮迭代换来的交叉减少肉眼看不出来，却让布局变得难以预测。
 */
export function orderWithinLayers(
  layers: Map<string, number>,
  tables: DiagramTable[],
  edges: Array<{ from: string; to: string }>
): Map<string, number> {
  const byLayer = new Map<number, string[]>();
  for (const table of tables) {
    const name = key(table.name);
    const layer = layers.get(name) ?? 0;
    if (!byLayer.has(layer)) byLayer.set(layer, []);
    byLayer.get(layer)!.push(name);
  }

  const order = new Map<string, number>();
  const sortedLayers = [...byLayer.keys()].sort((a, b) => a - b);
  for (const layer of sortedLayers) {
    const names = byLayer.get(layer)!;
    if (layer === sortedLayers[0]) {
      names.forEach((name, index) => order.set(name, index));
      continue;
    }
    const scored = names.map((name, index) => {
      const neighbours = edges
        .filter((edge) => key(edge.from) === name && order.has(key(edge.to)))
        .map((edge) => order.get(key(edge.to))!);
      const barycenter = neighbours.length > 0
        ? neighbours.reduce((sum, value) => sum + value, 0) / neighbours.length
        : Number.POSITIVE_INFINITY;
      return { name, index, barycenter };
    });
    scored.sort((a, b) => (a.barycenter - b.barycenter) || (a.index - b.index));
    scored.forEach((entry, index) => order.set(entry.name, index));
  }
  return order;
}

/** 把 schema 图装配成可直接绘制的坐标模型。 */
export function buildErModel(diagram: SchemaDiagram): ErModel {
  const merged = mergeRelations(diagram.relations);
  const layers = assignLayers(diagram.tables, merged);
  const order = orderWithinLayers(layers, diagram.tables, merged);

  const nodes: ErNode[] = diagram.tables.map((table) => {
    const name = key(table.name);
    const visible = table.keyColumns.slice(0, MAX_VISIBLE_KEY_COLUMNS);
    const hiddenKeyColumns = table.keyColumns.length - visible.length;
    const otherColumns = Math.max(0, table.columnCount - table.keyColumns.length);
    const hiddenColumnCount = hiddenKeyColumns + otherColumns;
    return {
      name: table.name,
      schemaName: table.schemaName,
      columns: visible,
      hiddenColumnCount,
      columnCount: table.columnCount,
      x: 0,
      y: 0,
      width: NODE_WIDTH,
      height: nodeHeight(visible.length, hiddenColumnCount > 0),
      layer: layers.get(name) ?? 0
    };
  });

  // 层号即列号：层 0 是不引用任何人的字典表，放最左；引用别人的往右排，
  // 连线因此整体从右指向左，读起来就是「谁依赖谁」。
  const columnTops = new Map<number, number>();
  for (const node of nodes.slice().sort((a, b) => {
    const layerDiff = a.layer - b.layer;
    if (layerDiff !== 0) return layerDiff;
    return (order.get(key(a.name)) ?? 0) - (order.get(key(b.name)) ?? 0);
  })) {
    const column = node.layer;
    const top = columnTops.get(column) ?? CANVAS_PADDING;
    node.x = CANVAS_PADDING + column * (NODE_WIDTH + LAYER_GAP);
    node.y = top;
    columnTops.set(column, top + node.height + NODE_GAP);
  }

  const byName = new Map(nodes.map((node) => [key(node.name), node]));
  const edges: ErEdge[] = merged.map((relation) => {
    const from = byName.get(key(relation.from));
    const to = byName.get(key(relation.to));
    if (!from || !to) {
      return { ...relation, points: [], selfReference: false };
    }
    if (from === to) {
      // 自引用：从右边出去绕一圈回到右边，不去挤别的连线的通道。
      const y = from.y + from.height / 2;
      const right = from.x + from.width;
      return {
        ...relation,
        selfReference: true,
        points: [
          { x: right, y: y - 8 },
          { x: right + NODE_GAP, y: y - 8 },
          { x: right + NODE_GAP, y: y + 8 },
          { x: right, y: y + 8 }
        ]
      };
    }
    const fromRight = from.x <= to.x;
    const start = { x: fromRight ? from.x + from.width : from.x, y: from.y + from.height / 2 };
    const end = { x: fromRight ? to.x : to.x + to.width, y: to.y + to.height / 2 };
    // 竖直段走目标列右侧那道列间距，而不是两端的中点：跨多列时中点会正好落在某个方框上，
    // 连线就从方框中间穿过去了。列间距是空的，那里永远画得下。
    const channelX = fromRight
      ? to.x - LAYER_GAP / 2
      : to.x + to.width + LAYER_GAP / 2;
    return {
      ...relation,
      selfReference: false,
      points: [start, { x: channelX, y: start.y }, { x: channelX, y: end.y }, end]
    };
  });

  const connected = new Set<string>();
  for (const relation of merged) {
    connected.add(key(relation.from));
    connected.add(key(relation.to));
  }

  const width = nodes.reduce((max, node) => Math.max(max, node.x + node.width), 0) + CANVAS_PADDING;
  const height = nodes.reduce((max, node) => Math.max(max, node.y + node.height), 0) + CANVAS_PADDING;
  return { nodes, edges, width, height, connectedTables: connected.size };
}
