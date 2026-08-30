import { describe, expect, it } from 'vitest';
import {
  assignLayers,
  buildErModel,
  CANVAS_PADDING,
  LAYER_GAP,
  mergeRelations,
  MAX_VISIBLE_KEY_COLUMNS,
  NODE_WIDTH,
  orderWithinLayers
} from './erDiagram';
import type { DiagramRelation, DiagramTable, SchemaDiagram } from './types';

function table(name: string, keyColumns: string[] = [], columnCount = 5): DiagramTable {
  return {
    schemaName: 'public',
    name,
    keyColumns: keyColumns.map((column, index) => ({
      name: column,
      type: 'int',
      nullable: false,
      primaryKey: index === 0,
      foreignKey: index > 0
    })),
    columnCount
  };
}

function relation(from: string, fromColumn: string, to: string, toColumn: string, constraint = `fk_${from}_${to}`): DiagramRelation {
  return { constraintName: constraint, fromTable: from, fromColumn, toTable: to, toColumn };
}

function diagram(tables: DiagramTable[], relations: DiagramRelation[]): SchemaDiagram {
  return { schemaName: 'public', tables, relations, totalTables: tables.length, truncated: false };
}

describe('复合外键合并', () => {
  it('同一个约束的多列合成一条边', () => {
    const merged = mergeRelations([
      relation('order_item', 'order_id', 'orders', 'id', 'fk_composite'),
      relation('order_item', 'tenant_id', 'orders', 'tenant_id', 'fk_composite')
    ]);

    expect(merged).toHaveLength(1);
    expect(merged[0].columns).toEqual([
      { fromColumn: 'order_id', toColumn: 'id' },
      { fromColumn: 'tenant_id', toColumn: 'tenant_id' }
    ]);
  });

  it('约束名缺失时退化成按两端表名合并', () => {
    const merged = mergeRelations([
      { constraintName: null, fromTable: 'a', fromColumn: 'x', toTable: 'b', toColumn: 'id' },
      { constraintName: null, fromTable: 'a', fromColumn: 'y', toTable: 'c', toColumn: 'id' }
    ]);

    expect(merged.map((edge) => edge.to)).toEqual(['b', 'c']);
  });
});

describe('分层', () => {
  it('层号是到无出边表的最长路径', () => {
    const tables = [table('countries'), table('cities'), table('addresses'), table('orders')];
    const relations = [
      relation('cities', 'country_id', 'countries', 'id'),
      relation('addresses', 'city_id', 'cities', 'id'),
      relation('orders', 'address_id', 'addresses', 'id')
    ];

    const layers = assignLayers(tables, mergeRelations(relations));

    expect(layers.get('countries')).toBe(0);
    expect(layers.get('cities')).toBe(1);
    expect(layers.get('addresses')).toBe(2);
    expect(layers.get('orders')).toBe(3);
  });

  it('走最长的那条路径，而不是第一条', () => {
    // orders 既直接指向 countries，也经 cities 绕一层；层号应取长的那条。
    const tables = [table('countries'), table('cities'), table('orders')];
    const layers = assignLayers(tables, mergeRelations([
      relation('cities', 'country_id', 'countries', 'id'),
      relation('orders', 'country_id', 'countries', 'id', 'fk_a'),
      relation('orders', 'city_id', 'cities', 'id', 'fk_b')
    ]));

    expect(layers.get('orders')).toBe(2);
  });

  it('外键成环时不会无限递归', () => {
    const tables = [table('a'), table('b')];
    const layers = assignLayers(tables, mergeRelations([
      relation('a', 'b_id', 'b', 'id'),
      relation('b', 'a_id', 'a', 'id')
    ]));

    expect(layers.size).toBe(2);
    expect([...layers.values()].every((value) => Number.isFinite(value))).toBe(true);
  });

  it('自引用不影响层号', () => {
    const layers = assignLayers([table('employees')], mergeRelations([
      relation('employees', 'manager_id', 'employees', 'id')
    ]));

    expect(layers.get('employees')).toBe(0);
  });
});

describe('层内排序', () => {
  it('按上一层邻居的重心排，减少连线交叉', () => {
    const tables = [table('a'), table('b'), table('x'), table('y')];
    const edges = mergeRelations([
      // x 指向 b（下标 1），y 指向 a（下标 0）：正确的顺序应把 y 排在 x 前面。
      relation('x', 'b_id', 'b', 'id'),
      relation('y', 'a_id', 'a', 'id')
    ]);
    const layers = assignLayers(tables, edges);

    const order = orderWithinLayers(layers, tables, edges);

    expect(order.get('y')).toBeLessThan(order.get('x')!);
  });
});

describe('布局模型', () => {
  it('层号大的排在右边，连线整体指向被引用方', () => {
    const model = buildErModel(diagram(
      [table('countries', ['id']), table('cities', ['id', 'country_id'])],
      [relation('cities', 'country_id', 'countries', 'id')]
    ));

    const countries = model.nodes.find((node) => node.name === 'countries')!;
    const cities = model.nodes.find((node) => node.name === 'cities')!;
    expect(countries.x).toBeLessThan(cities.x);
    expect(cities.x - countries.x).toBe(NODE_WIDTH + LAYER_GAP);
    expect(countries.x).toBe(CANVAS_PADDING);
  });

  it('指向图外的关系已在服务端剔除，这里的边两端都能落到节点上', () => {
    const model = buildErModel(diagram(
      [table('a', ['id']), table('b', ['id', 'a_id'])],
      [relation('b', 'a_id', 'a', 'id')]
    ));

    expect(model.edges).toHaveLength(1);
    expect(model.edges[0].points.length).toBeGreaterThan(0);
    expect(model.connectedTables).toBe(2);
  });

  it('自引用画成回环而不是一根零长度的线', () => {
    const model = buildErModel(diagram(
      [table('employees', ['id', 'manager_id'])],
      [relation('employees', 'manager_id', 'employees', 'id')]
    ));

    const edge = model.edges[0];
    expect(edge.selfReference).toBe(true);
    // 回环必须真的绕出去，否则在方框边上看不见。
    expect(Math.max(...edge.points.map((point) => point.x))).toBeGreaterThan(model.nodes[0].x + NODE_WIDTH);
  });

  it('键列过多时截断并计入「另有 N 列」', () => {
    const columns = Array.from({ length: MAX_VISIBLE_KEY_COLUMNS + 3 }, (_, index) => `c${index}`);
    const model = buildErModel(diagram([table('wide', columns, 40)], []));

    const node = model.nodes[0];
    expect(node.columns).toHaveLength(MAX_VISIBLE_KEY_COLUMNS);
    // 3 个被截掉的键列 + 40 减去 11 个键列之后剩下的普通列。
    expect(node.hiddenColumnCount).toBe(3 + (40 - columns.length));
  });

  it('孤立表也参与布局，并从关联计数里排除', () => {
    const model = buildErModel(diagram(
      [table('a', ['id']), table('b', ['id', 'a_id']), table('lonely', ['id'])],
      [relation('b', 'a_id', 'a', 'id')]
    ));

    expect(model.nodes).toHaveLength(3);
    expect(model.connectedTables).toBe(2);
  });

  it('画布尺寸包住所有节点', () => {
    const model = buildErModel(diagram(
      [table('a', ['id']), table('b', ['id', 'a_id']), table('c', ['id', 'b_id'])],
      [relation('b', 'a_id', 'a', 'id'), relation('c', 'b_id', 'b', 'id')]
    ));

    for (const node of model.nodes) {
      expect(node.x + node.width).toBeLessThanOrEqual(model.width);
      expect(node.y + node.height).toBeLessThanOrEqual(model.height);
    }
  });

  it('同一份输入两次布局结果完全一致', () => {
    const input = diagram(
      [table('a', ['id']), table('b', ['id', 'a_id']), table('c', ['id', 'a_id'])],
      [relation('b', 'a_id', 'a', 'id'), relation('c', 'a_id', 'a', 'id')]
    );

    expect(buildErModel(input)).toEqual(buildErModel(input));
  });
});
