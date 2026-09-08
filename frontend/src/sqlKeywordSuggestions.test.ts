import { describe, expect, it } from 'vitest';
import { analyzeSqlCompletion } from './sqlCompletion';
import { sqlKeywordCompletionItems, sqlKeywordPosition } from './sqlKeywordSuggestions';

/** 用 `|` 标出光标位置，省得每个用例都自己数偏移量。 */
function at(sqlWithCursor: string) {
  const offset = sqlWithCursor.indexOf('|');
  const sql = sqlWithCursor.replace('|', '');
  return analyzeSqlCompletion(sql, offset);
}

function keywords(sqlWithCursor: string) {
  return sqlKeywordCompletionItems(at(sqlWithCursor)).map((item) => item.label);
}

describe('SQL 关键字候选', () => {
  it('语句开头只给能开头的词', () => {
    // 用户报的就是这一条：一条语句以分号结束、光标落在分号后面，弹出来的却是
    // AND / FROM / GROUP BY / HAVING / INNER JOIN，那个位置上一个都不能用。
    expect(sqlKeywordPosition(at('select a from t;|'))).toBe('statement-start');
    expect(keywords('select a from t;|')).toEqual(['SELECT', 'INSERT', 'UPDATE', 'DELETE']);
    expect(keywords('|')).toEqual(['SELECT', 'INSERT', 'UPDATE', 'DELETE']);
    expect(keywords('select a from t;\n|')).not.toContain('AND');
  });

  it('SELECT 列表里写完一项之后只缺 FROM', () => {
    expect(keywords('select a, b |')).toEqual(['FROM']);
    expect(keywords('select count(*) |')).toEqual(['FROM']);
  });

  it('该写表达式的位置一个关键字都不给', () => {
    // 这些位置上真正有用的是字段候选，关键字只会把它们挤下去。
    expect(keywords('select |')).toEqual([]);
    expect(keywords('select a, |')).toEqual([]);
    expect(keywords('select * from t where |')).toEqual([]);
    expect(keywords('select * from t where a = |')).toEqual([]);
    expect(keywords('select * from t where a = 1 and |')).toEqual([]);
    expect(keywords('select * from t order by |')).toEqual([]);
  });

  it('该写表名的位置一个关键字都不给', () => {
    expect(sqlKeywordPosition(at('select * from |'))).toBe('table');
    expect(keywords('select * from |')).toEqual([]);
    expect(keywords('select * from t join |')).toEqual([]);
    expect(keywords('update |')).toEqual([]);
    expect(keywords('insert into |')).toEqual([]);
  });

  it('表名写完之后给接得上的子句，ON 只在 JOIN 之后出现', () => {
    expect(keywords('select * from orders |')).toEqual(
      ['WHERE', 'JOIN', 'LEFT JOIN', 'RIGHT JOIN', 'INNER JOIN', 'GROUP BY', 'ORDER BY', 'LIMIT']);
    expect(keywords('select * from orders o join items i |')).toContain('ON');
    expect(keywords('select * from orders |')).not.toContain('ON');
    expect(keywords('select * from orders |')).not.toContain('AND');
  });

  it('条件写完之后接的是 AND/OR 与后续子句，不是 FROM', () => {
    expect(keywords('select * from t where a = 1 |')).toEqual(['AND', 'OR', 'GROUP BY', 'ORDER BY', 'LIMIT']);
    expect(keywords('select * from a join b on a.id = b.id |')).toContain('AND');
    expect(keywords('select * from t where a = 1 |')).not.toContain('FROM');
  });

  it('GROUP BY 之后是 HAVING，ORDER BY 之后只剩 LIMIT', () => {
    expect(keywords('select a, count(*) from t group by a |')).toEqual(['HAVING', 'ORDER BY', 'LIMIT']);
    expect(keywords('select a from t order by a |')).toEqual(['LIMIT']);
    expect(keywords('select a from t order by a desc |')).toEqual(['LIMIT']);
  });

  it('正在输入的词不影响判断，前缀由编辑器自己筛', () => {
    // 光标前是半个词时，位置要按这个词之前的内容算，否则一敲字候选就变了。
    expect(keywords('select * from orders wh|')).toContain('WHERE');
    expect(keywords('select a from t;\nsel|')).toEqual(['SELECT', 'INSERT', 'UPDATE', 'DELETE']);
  });

  it('限定字段与注释里不给关键字', () => {
    expect(keywords('select * from orders o where o.|')).toEqual([]);
    expect(keywords('-- select * from t where |')).toEqual([]);
  });

  it('认不出来的位置退回整份清单', () => {
    // 宁可多给，也不要把真正能用的词筛掉。
    expect(sqlKeywordPosition(at('insert into t (a, b) values (1, 2) |'))).toBe('unknown');
    expect(keywords('insert into t (a, b) values (1, 2) |').length).toBeGreaterThan(10);
  });

  it('加引号的标识符不算子句关键字', () => {
    // "from" 是列名，不是 FROM 子句。
    expect(sqlKeywordPosition(at('select "from" |'))).toBe('select-list');
  });
});
