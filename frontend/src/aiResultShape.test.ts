import { describe, expect, it } from 'vitest';
import { MAX_DISTINCT_TRACKED, MAX_SHAPE_COLUMNS, resultShape, resultShapeText } from './aiResultShape';
import type { SqlResult } from './types';

function result(columns: string[], rows: unknown[][], extra: Partial<SqlResult> = {}): SqlResult {
  return {
    columns: columns.map((label) => ({ key: label, label, typeName: 'VARCHAR' })),
    rows,
    affectedRows: 0,
    elapsedMs: 12,
    resultSet: true,
    ...extra
  };
}

describe('resultShape', () => {
  it('只产出计数，不带任何业务值', () => {
    const shape = resultShape(result(['CUST_NM', 'MOBILE'], [['张三', '13800000000'], ['李四', null]]));

    expect(JSON.stringify(shape)).not.toContain('张三');
    expect(JSON.stringify(shape)).not.toContain('13800000000');
    expect(shape?.columns).toEqual([
      { label: 'CUST_NM', typeName: 'VARCHAR', nullCount: 0, distinctCount: 2 },
      { label: 'MOBILE', typeName: 'VARCHAR', nullCount: 1, distinctCount: 1 }
    ]);
  });

  it('写操作没有结果集，不产出形状', () => {
    expect(resultShape(result([], [], { resultSet: false }))).toBeUndefined();
  });

  it('截断或还有下一页时标记 hasMore，行数只是下界', () => {
    expect(resultShape(result(['A'], [[1]], { truncated: true }))?.hasMore).toBe(true);
    expect(resultShape(result(['A'], [[1]], {
      page: { connectionId: 1, offset: 0, requestedPageSize: 1, effectivePageSize: 1, hasMore: true }
    }))?.hasMore).toBe(true);
    expect(resultShape(result(['A'], [[1]]))?.hasMore).toBe(false);
  });

  it('不同取值数封顶，宽表只描述前若干列', () => {
    const many = Array.from({ length: MAX_DISTINCT_TRACKED + 50 }, (_, index) => [index]);
    expect(resultShape(result(['A'], many))?.columns[0].distinctCount).toBe(MAX_DISTINCT_TRACKED);

    const wide = Array.from({ length: MAX_SHAPE_COLUMNS + 10 }, (_, index) => `C${index}`);
    expect(resultShape(result(wide, []))?.columns).toHaveLength(MAX_SHAPE_COLUMNS);
  });
});

describe('resultShapeText', () => {
  /** 0 行是最常见的「结果不对」，模型第一眼就该看到。 */
  it('零行时直接点明', () => {
    const text = resultShapeText(resultShape(result(['A'], []))!);

    expect(text).toContain('共返回 0 行');
    expect(text).toContain('没有任何行返回');
  });

  it('整列全空要单独点名 —— 外连接没匹配上就长这样', () => {
    const text = resultShapeText(resultShape(result(['CUST_NM', 'PAID_AT'], [['x', null], ['y', null]]))!);

    expect(text).toContain('PAID_AT（VARCHAR）：整列全为空');
    expect(text).toContain('各列在本页 2 行里的分布（只有计数，没有具体取值）');
  });

  it('截断时说明行数只是本页，避免模型当成全量结论', () => {
    const text = resultShapeText(resultShape(result(['A'], [[1]], { truncated: true }))!);

    expect(text).toContain('本页返回 1 行（结果被截断，实际更多）');
  });

  it('渲染出来的文字里同样没有业务值', () => {
    const text = resultShapeText(resultShape(result(['CUST_NM'], [['张三'], ['李四']]))!);

    expect(text).not.toContain('张三');
    expect(text).toContain('2 个不同取值');
  });
});
