/**
 * AI 产出的 SQL 在进编辑器之前的检查。
 *
 * 判定放在前端而不是后端，是因为「要不要把这条 SQL 放进编辑器」这个决定发生在这里：
 * 流式回答是一段一段到达的，后端见到的是自己吐出去的片段，而用户点「插入」时面对的是
 * 拼完的那一整段。规则只有一份，就在这里。
 *
 * 检查不阻止插入 —— 用户完全可以要一条 UPDATE。它只负责让写操作和多语句无法悄悄溜进去。
 */

export type SqlSuggestionKind = 'QUERY' | 'WRITE' | 'DDL' | 'UNKNOWN';

export type SqlSuggestionCheck = {
  sql: string;
  kind: SqlSuggestionKind;
  /** 拆出来的语句数量；大于 1 时只插第一条，其余交给用户自己确认。 */
  statementCount: number;
  /** 需要在按钮旁边显红的提示；没有问题时为 undefined。 */
  warning?: string;
};

const WRITE_HEADS = ['insert', 'update', 'delete', 'merge', 'replace', 'upsert', 'truncate'];
const DDL_HEADS = ['create', 'alter', 'drop', 'rename', 'comment', 'grant', 'revoke'];

/** 去掉注释与字符串字面量，避免把注释里的关键字或分号当真。 */
function strip(sql: string): string {
  return sql
    .replace(/--[^\n]*/g, ' ')
    .replace(/\/\*[\s\S]*?\*\//g, ' ')
    .replace(/'(?:''|[^'])*'/g, "''");
}

/** 按分号切分；结尾的分号不算一条空语句。 */
export function splitStatements(sql: string): string[] {
  return strip(sql)
    .split(';')
    .map((statement) => statement.trim())
    .filter((statement) => statement.length > 0);
}

export function classifyStatement(sql: string): SqlSuggestionKind {
  const head = strip(sql).trim().toLowerCase().split(/\s+/)[0] || '';
  if (!head) return 'UNKNOWN';
  if (head === 'select' || head === 'with' || head === 'show' || head === 'explain' || head === 'desc' || head === 'describe') {
    return 'QUERY';
  }
  if (WRITE_HEADS.includes(head)) return 'WRITE';
  if (DDL_HEADS.includes(head)) return 'DDL';
  return 'UNKNOWN';
}

export function checkSqlSuggestion(sql: string): SqlSuggestionCheck {
  const trimmed = (sql || '').trim();
  const statements = splitStatements(trimmed);
  const kind = classifyStatement(statements[0] || trimmed);
  return {
    sql: trimmed,
    kind,
    statementCount: statements.length,
    warning: warningFor(kind, statements.length)
  };
}

function warningFor(kind: SqlSuggestionKind, statementCount: number): string | undefined {
  if (statementCount > 1) return `这段回答里有 ${statementCount} 条语句，请逐条确认后再执行。`;
  if (kind === 'WRITE') return '这是一条写语句，执行前请确认影响范围。';
  if (kind === 'DDL') return '这是一条结构变更语句，执行前请确认影响范围。';
  if (kind === 'UNKNOWN') return '无法判断这条语句的类型，执行前请自行确认。';
  return undefined;
}
