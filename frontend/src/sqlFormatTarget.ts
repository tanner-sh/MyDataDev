import { getCurrentSqlStatement } from './sqlCompletion';

export type SqlTextSelection = { start: number; end: number };

export type SqlFormatTarget = {
  sql: string;
  start: number;
  end: number;
  selected: boolean;
};

export function getSqlFormatTarget(sql: string, cursor: number, selection?: SqlTextSelection): SqlFormatTarget | undefined {
  if (selection && selection.start !== selection.end) {
    const start = Math.min(selection.start, selection.end);
    const end = Math.max(selection.start, selection.end);
    const selected = trimmedTarget(sql, start, end, true);
    if (selected) return selected;
  }

  const statement = getCurrentSqlStatement(sql, cursor);
  return trimmedTarget(sql, statement.start, statement.end, false);
}

function trimmedTarget(sql: string, start: number, end: number, selected: boolean): SqlFormatTarget | undefined {
  const text = sql.slice(start, end);
  const leadingWhitespace = text.length - text.trimStart().length;
  const trailingWhitespace = text.length - text.trimEnd().length;
  const contentStart = start + leadingWhitespace;
  const contentEnd = Math.max(contentStart, end - trailingWhitespace);
  const content = sql.slice(contentStart, contentEnd);
  if (!content) return undefined;
  return { sql: content, start: contentStart, end: contentEnd, selected };
}
