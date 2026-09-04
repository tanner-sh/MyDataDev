import { describe, expect, it } from 'vitest';
import { checkSqlSuggestion, classifyStatement, splitStatements } from './sqlSuggestion';

describe('语句拆分', () => {
  it('按分号拆，结尾分号不算一条', () => {
    expect(splitStatements('SELECT 1; SELECT 2;')).toEqual(['SELECT 1', 'SELECT 2']);
    expect(splitStatements('SELECT 1;')).toEqual(['SELECT 1']);
  });

  /** 注释和字面量里的分号不是语句分隔符，否则一条正常 SQL 会被误判成多语句。 */
  it('忽略注释与字符串里的分号', () => {
    expect(splitStatements("SELECT 'a;b' FROM t -- 这里;有分号")).toHaveLength(1);
    expect(splitStatements('SELECT 1 /* ; */ FROM t')).toHaveLength(1);
  });
});

describe('语句分类', () => {
  it('认得查询', () => {
    expect(classifyStatement('SELECT * FROM orders')).toBe('QUERY');
    expect(classifyStatement('WITH t AS (SELECT 1) SELECT * FROM t')).toBe('QUERY');
    expect(classifyStatement('EXPLAIN SELECT 1')).toBe('QUERY');
  });

  it('认得写操作与结构变更', () => {
    expect(classifyStatement('UPDATE orders SET status = 1')).toBe('WRITE');
    expect(classifyStatement('delete from orders')).toBe('WRITE');
    expect(classifyStatement('ALTER TABLE orders ADD COLUMN note VARCHAR(20)')).toBe('DDL');
  });

  /** 注释开头的写语句必须仍然判成写 —— 这是最容易被绕过去的一种伪装。 */
  it('不被前置注释骗过去', () => {
    expect(classifyStatement('-- 只是查一下\nDELETE FROM orders')).toBe('WRITE');
  });

  it('认不出来就说认不出来', () => {
    expect(classifyStatement('CALL do_something()')).toBe('UNKNOWN');
    expect(classifyStatement('   ')).toBe('UNKNOWN');
  });
});

describe('插入前的检查', () => {
  it('普通查询没有提示', () => {
    expect(checkSqlSuggestion('SELECT * FROM orders')).toMatchObject({ kind: 'QUERY', statementCount: 1, warning: undefined });
  });

  it('写语句给出提示但不阻止插入', () => {
    const check = checkSqlSuggestion('UPDATE orders SET status = 1 WHERE id = 2');

    expect(check.kind).toBe('WRITE');
    expect(check.warning).toContain('写语句');
    expect(check.sql).toContain('UPDATE');
  });

  it('多语句提示逐条确认', () => {
    const check = checkSqlSuggestion('SELECT 1; DELETE FROM orders;');

    expect(check.statementCount).toBe(2);
    expect(check.warning).toContain('2 条语句');
  });

  it('结构变更单独提示', () => {
    expect(checkSqlSuggestion('DROP TABLE orders').warning).toContain('结构变更');
  });
});
