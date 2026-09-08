/**
 * 关键字候选按光标所在的位置给，而不是每次都把整份清单倒出来。
 *
 * <p>此前不管光标在哪里，补全里都跟着同一份 17 个关键字：一条 `select ... ;` 写完、光标落在
 * 分号后面（也就是下一条语句的开头），弹出来的却是 AND / FROM / GROUP BY / HAVING / INNER JOIN。
 * 那个位置上这些词一个都不能用 —— 能用的只有 SELECT / INSERT / UPDATE / DELETE。候选里混进
 * 语法上不成立的词，比少给几个词糟：用户得先自己筛一遍，久了就干脆不看这个列表了。</p>
 *
 * <p>这里不做语法分析，只看两件事：<b>光标前的那个词</b>（它决定「接下来该写什么」——
 * WHERE 后面要的是条件、FROM 后面要的是表名，这两种位置一个关键字都不该给），以及
 * <b>往回找到的最近一个子句关键字</b>（它决定「这条语句写到哪儿了」）。判断不出来就退回整份
 * 清单：宁可多给，也不要把真正能用的词筛掉。</p>
 */
import type { SqlCompletionContext, SqlToken } from './sqlCompletion';
import type { SqlCompletionItem } from './sqlEditorTypes';

export type SqlKeywordPosition =
  /** 语句开头：只能是一条语句的第一个词。 */
  | 'statement-start'
  /** 该写表达式/字段了（WHERE、SELECT、逗号、运算符之后）。 */
  | 'expression'
  /** 该写表名了（FROM、JOIN、INTO、UPDATE 之后）。 */
  | 'table'
  /** SELECT 列表里写完一项之后。 */
  | 'select-list'
  /** FROM/JOIN 的表名写完之后。 */
  | 'table-source'
  /** JOIN 的表名写完之后：这里还缺一个 ON。 */
  | 'join-source'
  /** WHERE/ON/HAVING 的条件写完之后。 */
  | 'condition'
  | 'group-by'
  | 'order-by'
  /** 认不出来：给整份清单。 */
  | 'unknown';

/** 语句开头能写的词。 */
const STATEMENT_START = ['SELECT', 'INSERT', 'UPDATE', 'DELETE'];

const KEYWORDS_BY_POSITION: Record<SqlKeywordPosition, string[]> = {
  'statement-start': STATEMENT_START,
  expression: [],
  table: [],
  'select-list': ['FROM'],
  'table-source': ['WHERE', 'JOIN', 'LEFT JOIN', 'RIGHT JOIN', 'INNER JOIN', 'GROUP BY', 'ORDER BY', 'LIMIT'],
  'join-source': ['ON', 'WHERE', 'JOIN', 'LEFT JOIN', 'RIGHT JOIN', 'INNER JOIN', 'GROUP BY', 'ORDER BY', 'LIMIT'],
  condition: ['AND', 'OR', 'GROUP BY', 'ORDER BY', 'LIMIT'],
  'group-by': ['HAVING', 'ORDER BY', 'LIMIT'],
  'order-by': ['LIMIT'],
  unknown: [
    'SELECT', 'FROM', 'WHERE', 'JOIN', 'LEFT JOIN', 'RIGHT JOIN', 'INNER JOIN',
    'GROUP BY', 'ORDER BY', 'HAVING', 'ON', 'AND', 'OR', 'LIMIT', 'INSERT', 'UPDATE', 'DELETE'
  ]
};

/** 这些词后面要的是表达式或字段名，给关键字只会挡住真正有用的候选。 */
const EXPRESSION_EXPECTED = new Set([
  'SELECT', 'WHERE', 'ON', 'HAVING', 'AND', 'OR', 'NOT', 'BY', 'SET', 'VALUES',
  'DISTINCT', 'ALL', 'AS', 'CASE', 'WHEN', 'THEN', 'ELSE', 'IN', 'LIKE', 'BETWEEN',
  'IS', 'USING', 'EXISTS'
]);

/** 这些词后面要的是表名。 */
const TABLE_EXPECTED = new Set(['FROM', 'JOIN', 'INTO', 'UPDATE', 'TABLE']);

/** 往回找子句时认的词；`BY` 单独看它前面是 GROUP 还是 ORDER。 */
const CLAUSE_KEYWORDS = new Set([
  'SELECT', 'FROM', 'JOIN', 'WHERE', 'ON', 'HAVING', 'AND', 'OR', 'BY',
  'INSERT', 'INTO', 'UPDATE', 'DELETE', 'SET', 'VALUES', 'LIMIT', 'OFFSET', 'UNION'
]);

/** 表达式还没写完的符号；`)` 不在其中，它结束一段表达式。 */
const EXPRESSION_SYMBOLS = new Set([
  ',', '(', '=', '<', '>', '<=', '>=', '<>', '!=', '+', '-', '*', '/', '%', '||', '.'
]);

export function sqlKeywordPosition(context: SqlCompletionContext): SqlKeywordPosition {
  if (context.insideCommentOrString) return 'expression';
  // 正在写表名或 `别名.` 后面的字段时，关键字一个都不合适。
  if (context.tablePosition) return 'table';
  if (context.qualifier) return 'expression';

  const before = context.significantTokens.filter((token) => token.end <= context.replacement.start);
  if (before.length === 0) return 'statement-start';

  const previous = before[before.length - 1];
  const previousKeyword = keywordOf(previous);
  if (previousKeyword && TABLE_EXPECTED.has(previousKeyword)) return 'table';
  if (previousKeyword && EXPRESSION_EXPECTED.has(previousKeyword)) return 'expression';
  if (previous.kind === 'symbol' && EXPRESSION_SYMBOLS.has(previous.text)) return 'expression';

  return clausePosition(before);
}

export function sqlKeywordCompletionItems(context: SqlCompletionContext): SqlCompletionItem[] {
  return KEYWORDS_BY_POSITION[sqlKeywordPosition(context)].map((keyword) => ({
    label: keyword,
    kind: 'keyword' as const,
    insertText: keyword,
    detail: 'SQL 关键字',
    // 关键字排在表和列之后：用户已经知道 SELECT 怎么写，需要提示的是库里有什么。
    sortText: `2-${keyword.toLowerCase()}`
  }));
}

/** 往回找最近一个子句关键字，认不出来就交给 unknown。 */
function clausePosition(before: SqlToken[]): SqlKeywordPosition {
  for (let index = before.length - 1; index >= 0; index -= 1) {
    const keyword = keywordOf(before[index]);
    if (!keyword || !CLAUSE_KEYWORDS.has(keyword)) continue;
    switch (keyword) {
      case 'SELECT':
        return 'select-list';
      case 'FROM':
        return 'table-source';
      case 'JOIN':
        // JOIN 的表写完之后还缺一个 ON，FROM 那边则不需要。
        return 'join-source';
      case 'WHERE':
      case 'ON':
      case 'HAVING':
      case 'AND':
      case 'OR':
        return 'condition';
      case 'BY':
        return keywordOf(before[index - 1]) === 'GROUP' ? 'group-by' : 'order-by';
      default:
        return 'unknown';
    }
  }
  return 'unknown';
}

/** 未加引号的标识符才算关键字：`"from"` 是列名，不是子句。 */
function keywordOf(token: SqlToken | undefined): string | undefined {
  if (!token || token.kind !== 'identifier' || token.quoteStyle !== 'none') return undefined;
  return token.value.toUpperCase();
}
