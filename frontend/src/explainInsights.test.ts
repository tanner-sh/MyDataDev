import { describe, expect, it } from 'vitest';
import {
  explainFindings,
  explainFindingsSummary,
  explainRowLevels,
  isExplainResult,
  LARGE_ROW_ESTIMATE
} from './explainInsights';

const MYSQL_COLUMNS = ['id', 'select_type', 'table', 'type', 'possible_keys', 'key', 'key_len', 'ref', 'rows', 'Extra'];

function mysqlRow(overrides: Partial<Record<string, unknown>> = {}) {
  const base: Record<string, unknown> = {
    id: 1, select_type: 'SIMPLE', table: 'orders', type: 'ref',
    possible_keys: 'idx_customer', key: 'idx_customer', key_len: '4', ref: 'const', rows: 10, Extra: ''
  };
  return MYSQL_COLUMNS.map((column) => ({ ...base, ...overrides })[column]);
}

describe('isExplainResult', () => {
  it('认得 MySQL 经典 EXPLAIN 的列结构', () => {
    expect(isExplainResult(MYSQL_COLUMNS)).toBe(true);
  });

  it('认得 PostgreSQL 与 Oracle 的单列文本计划', () => {
    expect(isExplainResult(['QUERY PLAN'])).toBe(true);
    expect(isExplainResult(['PLAN_TABLE_OUTPUT'])).toBe(true);
  });

  it('认得 SQLite 的 EXPLAIN QUERY PLAN', () => {
    expect(isExplainResult(['id', 'parent', 'notused', 'detail'])).toBe(true);
  });

  it('普通查询结果不会被当成执行计划', () => {
    expect(isExplainResult(['id', 'name', 'type'])).toBe(false);
    expect(isExplainResult([])).toBe(false);
  });
});

describe('explainFindings · MySQL', () => {
  it('标出全表扫描', () => {
    const findings = explainFindings(MYSQL_COLUMNS, [mysqlRow({ type: 'ALL', key: null, possible_keys: null })]);
    expect(findings.map((finding) => finding.code)).toContain('full-table-scan');
    expect(findings[0].level).toBe('warning');
  });

  it('全索引扫描只作为提示，而不是警告', () => {
    const findings = explainFindings(MYSQL_COLUMNS, [mysqlRow({ type: 'index' })]);
    expect(findings.find((finding) => finding.code === 'full-index-scan')?.level).toBe('notice');
  });

  it('标出 filesort、临时表与连接缓冲', () => {
    const findings = explainFindings(MYSQL_COLUMNS, [
      mysqlRow({ Extra: 'Using where; Using filesort' }),
      mysqlRow({ Extra: 'Using temporary; Using filesort' }),
      mysqlRow({ Extra: 'Using join buffer (Block Nested Loop)' })
    ]);
    expect(findings.map((finding) => finding.code).sort())
      .toEqual(['filesort', 'join-buffer', 'temporary-table']);
  });

  it('同一类问题合并成一条，把命中的行号列出来', () => {
    // 5 表关联里三处全表扫描应该读作「三处」，而不是刷三条一样的警告。
    const findings = explainFindings(MYSQL_COLUMNS, [
      mysqlRow({ type: 'ALL', key: null, possible_keys: null }),
      mysqlRow({ type: 'ref' }),
      mysqlRow({ type: 'ALL', key: null, possible_keys: null })
    ]);
    const scan = findings.find((finding) => finding.code === 'full-table-scan');
    expect(scan?.rows).toEqual([0, 2]);
  });

  it('有候选索引却没用上时单独提醒', () => {
    const findings = explainFindings(MYSQL_COLUMNS, [mysqlRow({ type: 'ALL', key: '', possible_keys: 'idx_status' })]);
    const unused = findings.find((finding) => finding.code === 'unused-index');
    expect(unused?.detail).toContain('idx_status');
  });

  it('预估行数超过阈值才报，正常量级不报', () => {
    expect(explainFindings(MYSQL_COLUMNS, [mysqlRow({ rows: LARGE_ROW_ESTIMATE - 1 })])).toHaveLength(0);
    const findings = explainFindings(MYSQL_COLUMNS, [mysqlRow({ rows: LARGE_ROW_ESTIMATE })]);
    expect(findings[0].code).toBe('large-row-estimate');
    expect(findings[0].title).toContain('万');
  });

  it('带千分位的行数也能解析', () => {
    expect(explainFindings(MYSQL_COLUMNS, [mysqlRow({ rows: '1,200,000' })])[0].code).toBe('large-row-estimate');
  });

  it('健康的计划不产生任何噪音', () => {
    expect(explainFindings(MYSQL_COLUMNS, [mysqlRow()])).toEqual([]);
  });
});

describe('explainFindings · 文本计划', () => {
  it('标出 PostgreSQL 的顺序扫描与排序落盘', () => {
    const findings = explainFindings(['QUERY PLAN'], [
      ['Seq Scan on orders  (cost=0.00..18584.00 rows=5 width=244)'],
      ['  Sort Method: external merge  Disk: 24480kB']
    ]);
    expect(findings.map((finding) => finding.code).sort()).toEqual(['external-sort', 'seq-scan']);
  });

  it('PostgreSQL 的大 rows= 估算作为提示给出', () => {
    const findings = explainFindings(['QUERY PLAN'], [['Index Scan using idx on orders  (cost=0.4 rows=250000 width=8)']]);
    expect(findings[0].code).toBe('large-row-estimate');
    expect(findings[0].level).toBe('notice');
  });

  it('PostgreSQL 走了索引的 Index Scan 不能被当成表扫描', () => {
    // SQLite 的 SCAN 规则曾经跨方言命中这里，把一条健康的计划标成了问题。
    const findings = explainFindings(['QUERY PLAN'], [['Index Scan using idx_customer on orders  (cost=0.4 rows=12 width=8)']]);
    expect(findings).toEqual([]);
  });

  it('标出 H2 注释里的 tableScan，走了索引的计划不报', () => {
    // H2 把访问路径写在注释里，这是本项目默认数据库的真实输出形态。
    const scan = explainFindings(['PLAN'], [['SELECT ... FROM "PUBLIC"."ORDERS" /* PUBLIC.ORDERS.tableScan */ ORDER BY 2']]);
    expect(scan[0].code).toBe('h2-table-scan');
    const indexed = explainFindings(['PLAN'], [['SELECT ... FROM "PUBLIC"."ORDERS" /* PUBLIC.IDX_NOTE: NOTE = \'x\' */ WHERE "NOTE" = \'x\'']]);
    expect(indexed).toEqual([]);
  });

  it('标出 Oracle 的全表扫描', () => {
    const findings = explainFindings(['PLAN_TABLE_OUTPUT'], [['|  1 |  TABLE ACCESS FULL | ORDERS |']]);
    expect(findings[0].code).toBe('oracle-full-scan');
  });

  it('SQLite 里 SEARCH 表示走了索引，不该被当成表扫描', () => {
    expect(explainFindings(['id', 'parent', 'notused', 'detail'], [[0, 0, 0, 'SEARCH orders USING INDEX idx_customer']]))
      .toEqual([]);
    expect(explainFindings(['id', 'parent', 'notused', 'detail'], [[0, 0, 0, 'SCAN orders']])[0].code)
      .toBe('sqlite-scan');
  });
});

describe('explainRowLevels', () => {
  it('同一行取最高级别', () => {
    const levels = explainRowLevels([
      { level: 'notice', code: 'a', title: '', detail: '', rows: [0, 1] },
      { level: 'warning', code: 'b', title: '', detail: '', rows: [1] }
    ]);
    expect(levels.get(0)).toBe('notice');
    expect(levels.get(1)).toBe('warning');
  });
});

describe('explainFindingsSummary', () => {
  it('分别说明警告与提示的数量', () => {
    expect(explainFindingsSummary([])).toBe('执行计划中没有发现明显的性能问题');
    expect(explainFindingsSummary([
      { level: 'warning', code: 'a', title: '', detail: '', rows: [0] },
      { level: 'notice', code: 'b', title: '', detail: '', rows: [1] }
    ])).toBe('执行计划解读：1 项需要关注，1 项提示');
  });
});
