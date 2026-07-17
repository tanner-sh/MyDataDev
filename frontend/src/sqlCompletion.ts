export type SqlQuoteStyle = 'none' | 'double' | 'backtick' | 'bracket';

export type SqlTokenKind =
  | 'whitespace'
  | 'comment'
  | 'string'
  | 'identifier'
  | 'number'
  | 'symbol';

export type SqlToken = {
  kind: SqlTokenKind;
  /** The exact text from the SQL source. */
  text: string;
  /** The decoded identifier/string value, otherwise the token text. */
  value: string;
  start: number;
  end: number;
  quoteStyle: SqlQuoteStyle;
  closed: boolean;
};

export type SqlStatementSlice = {
  text: string;
  start: number;
  end: number;
  cursor: number;
  cursorInStatement: number;
};

export type SqlIdentifierPart = {
  value: string;
  normalized: string;
  text: string;
  quoteStyle: SqlQuoteStyle;
  start: number;
  end: number;
};

export type SqlTableSourceKeyword = 'FROM' | 'JOIN' | 'UPDATE' | 'INTO';

export type SqlTableReference = {
  parts: SqlIdentifierPart[];
  name: string;
  schemaName?: string;
  qualifiedName: string;
  normalizedName: string;
  normalizedQualifiedName: string;
  alias?: string;
  normalizedAlias?: string;
  aliasText?: string;
  aliasQuoteStyle?: SqlQuoteStyle;
  sourceKeyword: SqlTableSourceKeyword;
  start: number;
  end: number;
};

export type SqlCompletionMode =
  | 'none'
  | 'table'
  | 'qualified-column'
  | 'column'
  | 'keyword';

export type SqlColumnCompletionStrategy = 'none' | 'bare' | 'qualified';

export type SqlCompletionReplacement = {
  start: number;
  end: number;
  text: string;
  prefix: string;
  quoteStyle: SqlQuoteStyle;
};

export type SqlCompletionContext = {
  statement: SqlStatementSlice;
  tokens: SqlToken[];
  significantTokens: SqlToken[];
  tables: SqlTableReference[];
  mode: SqlCompletionMode;
  columnStrategy: SqlColumnCompletionStrategy;
  replacement: SqlCompletionReplacement;
  qualifier?: string;
  qualifierParts: string[];
  tablePosition: boolean;
  insideCommentOrString: boolean;
  /** True when unqualified columns are useful and unambiguous. */
  suggestBareColumns: boolean;
  /** True when column candidates should be inserted as alias.column/table.column. */
  qualifyColumns: boolean;
};

const TABLE_SOURCE_KEYWORDS = new Set(['FROM', 'JOIN', 'UPDATE', 'INTO']);

const FROM_BOUNDARY_KEYWORDS = new Set([
  'WHERE', 'GROUP', 'ORDER', 'HAVING', 'QUALIFY', 'LIMIT', 'OFFSET', 'FETCH',
  'UNION', 'EXCEPT', 'INTERSECT', 'MINUS', 'RETURNING', 'CONNECT', 'START',
  'MODEL', 'WINDOW', 'FOR', 'PROCEDURE', 'INTO', 'SET', 'VALUES'
]);

const RESERVED_ALIAS_KEYWORDS = new Set([
  ...TABLE_SOURCE_KEYWORDS,
  ...FROM_BOUNDARY_KEYWORDS,
  'AS', 'ON', 'USING', 'LEFT', 'RIGHT', 'FULL', 'INNER', 'OUTER', 'CROSS',
  'NATURAL', 'STRAIGHT_JOIN', 'APPLY', 'PIVOT', 'UNPIVOT', 'PARTITION', 'USE',
  'FORCE', 'IGNORE', 'INDEX', 'KEY', 'TABLESAMPLE', 'ONLY', 'LATERAL', 'SELECT',
  'INSERT', 'DELETE', 'MERGE', 'WHEN', 'MATCHED', 'THEN', 'ELSE', 'END'
]);

const TABLE_MODIFIERS = new Set(['ONLY', 'LATERAL']);

const TWO_CHARACTER_SYMBOLS = new Set([
  '<=', '>=', '<>', '!=', '||', '&&', '::', ':=', '=>', '->', '<<', '>>'
]);

/**
 * Tokenizes SQL without treating delimiters inside strings, quoted identifiers,
 * or comments as syntax. Token offsets are absolute and include `baseOffset`.
 */
export function tokenizeSql(sql: string, baseOffset = 0): SqlToken[] {
  const tokens: SqlToken[] = [];
  let index = 0;

  while (index < sql.length) {
    const start = index;
    const char = sql[index];
    const next = sql[index + 1];

    if (isWhitespace(char)) {
      index += 1;
      while (index < sql.length && isWhitespace(sql[index])) index += 1;
      pushToken(tokens, 'whitespace', sql, start, index, baseOffset);
      continue;
    }

    if ((char === '-' && next === '-') || char === '#') {
      index += char === '#' ? 1 : 2;
      while (index < sql.length && sql[index] !== '\n' && sql[index] !== '\r') index += 1;
      // A line comment has no closing delimiter. Keeping `closed` false means a
      // cursor at its final character is still correctly treated as commented.
      pushToken(tokens, 'comment', sql, start, index, baseOffset, 'none', false);
      continue;
    }

    if (char === '/' && next === '*') {
      index += 2;
      let depth = 1;
      while (index < sql.length && depth > 0) {
        if (sql[index] === '/' && sql[index + 1] === '*') {
          depth += 1;
          index += 2;
        } else if (sql[index] === '*' && sql[index + 1] === '/') {
          depth -= 1;
          index += 2;
        } else {
          index += 1;
        }
      }
      pushToken(tokens, 'comment', sql, start, index, baseOffset, 'none', depth === 0);
      continue;
    }

    if (char === '\'') {
      const quoted = readQuoted(sql, index, '\'', '\'', true);
      index = quoted.end;
      const text = sql.slice(start, index);
      tokens.push({
        kind: 'string',
        text,
        value: decodeDelimited(text, '\'', '\'', quoted.closed),
        start: baseOffset + start,
        end: baseOffset + index,
        quoteStyle: 'none',
        closed: quoted.closed
      });
      continue;
    }

    if (char === '"' || char === '`') {
      const style: SqlQuoteStyle = char === '"' ? 'double' : 'backtick';
      const quoted = readQuoted(sql, index, char, char, true);
      index = quoted.end;
      const text = sql.slice(start, index);
      tokens.push({
        kind: 'identifier',
        text,
        value: decodeDelimited(text, char, char, quoted.closed),
        start: baseOffset + start,
        end: baseOffset + index,
        quoteStyle: style,
        closed: quoted.closed
      });
      continue;
    }

    if (char === '[') {
      const quoted = readQuoted(sql, index, '[', ']', false);
      index = quoted.end;
      const text = sql.slice(start, index);
      tokens.push({
        kind: 'identifier',
        text,
        value: decodeDelimited(text, '[', ']', quoted.closed),
        start: baseOffset + start,
        end: baseOffset + index,
        quoteStyle: 'bracket',
        closed: quoted.closed
      });
      continue;
    }

    if (isIdentifierStart(char)) {
      index += 1;
      while (index < sql.length && isIdentifierContinue(sql[index])) index += 1;
      pushToken(tokens, 'identifier', sql, start, index, baseOffset);
      continue;
    }

    if (isDigit(char)) {
      index = readNumber(sql, index);
      pushToken(tokens, 'number', sql, start, index, baseOffset);
      continue;
    }

    const twoCharacters = sql.slice(index, index + 2);
    index += TWO_CHARACTER_SYMBOLS.has(twoCharacters) ? 2 : 1;
    pushToken(tokens, 'symbol', sql, start, index, baseOffset);
  }

  return tokens;
}

/** Returns the semicolon-delimited statement containing the cursor. */
export function getCurrentSqlStatement(sql: string, cursorPosition: number): SqlStatementSlice {
  const cursor = clamp(cursorPosition, 0, sql.length);
  const tokens = tokenizeSql(sql);
  let start = 0;
  let end = sql.length;

  for (const token of tokens) {
    if (token.kind !== 'symbol' || token.text !== ';') continue;
    if (token.end <= cursor) {
      start = token.end;
      continue;
    }
    if (token.start >= cursor) {
      end = token.start;
      break;
    }
  }

  return {
    text: sql.slice(start, end),
    start,
    end,
    cursor,
    cursorInStatement: cursor - start
  };
}

/**
 * Builds a completion context from only the statement containing the cursor.
 * The returned table references are read from the entire current statement, so
 * `SELECT | FROM orders o` can still offer columns from `orders`.
 */
export function analyzeSqlCompletion(sql: string, cursorPosition: number): SqlCompletionContext {
  const statement = getCurrentSqlStatement(sql, cursorPosition);
  const tokens = tokenizeSql(statement.text, statement.start);
  const significantTokens = tokens.filter(isSignificantToken);
  const cursorToken = tokenContainingCursor(tokens, statement.cursor);
  const insideCommentOrString = cursorToken?.kind === 'comment' || cursorToken?.kind === 'string';
  const replacement = getCompletionReplacement(sql, tokens, statement.cursor);
  const beforeReplacement = significantTokens.filter((token) => token.end <= replacement.start);
  const tablePosition = !insideCommentOrString && isTableCompletionPosition(beforeReplacement);
  const qualifierParts = tablePosition ? [] : readQualifierParts(beforeReplacement);
  const qualifier = qualifierParts.length > 0 ? qualifierParts.join('.') : undefined;
  const tables = parseSqlTableReferences(tokens);

  let mode: SqlCompletionMode = 'keyword';
  let columnStrategy: SqlColumnCompletionStrategy = 'none';

  if (insideCommentOrString) {
    mode = 'none';
  } else if (tablePosition) {
    mode = 'table';
  } else if (qualifier) {
    mode = 'qualified-column';
    columnStrategy = 'bare';
  } else if (tables.length === 1) {
    mode = 'column';
    columnStrategy = 'bare';
  } else if (tables.length > 1) {
    mode = 'column';
    columnStrategy = 'qualified';
  }

  return {
    statement,
    tokens,
    significantTokens,
    tables,
    mode,
    columnStrategy,
    replacement,
    qualifier,
    qualifierParts,
    tablePosition,
    insideCommentOrString,
    suggestBareColumns: mode === 'column' && columnStrategy === 'bare',
    qualifyColumns: mode === 'column' && columnStrategy === 'qualified'
  };
}

/** Monaco must ask the provider again as a table prefix grows instead of fuzzy-filtering a capped list. */
export function isSqlCompletionListIncomplete(context: SqlCompletionContext): boolean {
  return context.mode === 'table';
}

/** Parses FROM/JOIN/UPDATE/INTO table names and their explicit or implicit aliases. */
export function parseSqlTableReferences(tokensOrSql: SqlToken[] | string): SqlTableReference[] {
  const tokens = (typeof tokensOrSql === 'string' ? tokenizeSql(tokensOrSql) : tokensOrSql)
    .filter(isSignificantToken);
  const references: SqlTableReference[] = [];
  const seen = new Set<string>();

  const addReference = (parsed: ParsedTable | undefined) => {
    if (!parsed) return;
    const key = `${parsed.reference.start}:${parsed.reference.end}:${parsed.reference.sourceKeyword}`;
    if (seen.has(key)) return;
    seen.add(key);
    references.push(parsed.reference);
  };

  for (let index = 0; index < tokens.length; index += 1) {
    const sourceKeyword = tableSourceKeyword(tokens[index]);
    if (!sourceKeyword) continue;

    const parsed = parseTableAt(tokens, index + 1, sourceKeyword);
    addReference(parsed);

    if (sourceKeyword !== 'FROM') continue;
    const baseDepth = depthBefore(tokens, index);
    let nestedDepth = baseDepth;
    let scanIndex = parsed?.nextIndex ?? index + 1;

    for (; scanIndex < tokens.length; scanIndex += 1) {
      const token = tokens[scanIndex];
      if (token.text === '(') nestedDepth += 1;
      if (token.text === ')') {
        if (nestedDepth === baseDepth) break;
        nestedDepth -= 1;
        continue;
      }
      if (nestedDepth !== baseDepth) continue;
      if (isFromBoundary(token) || isKeyword(token, 'JOIN')) break;
      if (token.text !== ',') continue;

      const commaSource = parseTableAt(tokens, scanIndex + 1, 'FROM');
      addReference(commaSource);
      if (commaSource) scanIndex = commaSource.nextIndex - 1;
    }
  }

  return references.sort((left, right) => left.start - right.start);
}

/** Resolves an alias, table name, or qualified table name against parsed sources. */
export function resolveSqlTableReference(
  tablesOrContext: SqlTableReference[] | Pick<SqlCompletionContext, 'tables' | 'qualifierParts'>,
  qualifier?: string | string[]
): SqlTableReference | undefined {
  const tables = Array.isArray(tablesOrContext) ? tablesOrContext : tablesOrContext.tables;
  const rawParts = Array.isArray(qualifier)
    ? qualifier
    : qualifier
      ? parseSqlIdentifierPath(qualifier).map((part) => part.value)
      : !Array.isArray(tablesOrContext)
        ? tablesOrContext.qualifierParts
        : [];
  const normalizedParts = rawParts.map(normalizeIdentifier).filter(Boolean);
  if (normalizedParts.length === 0) return undefined;

  const normalized = normalizedParts.join('.');
  const last = normalizedParts[normalizedParts.length - 1];
  const matches = tables.filter((table) => (
    table.normalizedAlias === normalized
    || table.normalizedAlias === last
    || table.normalizedName === normalized
    || table.normalizedName === last
    || table.normalizedQualifiedName === normalized
    || table.normalizedQualifiedName.endsWith(`.${normalized}`)
  ));
  return matches[matches.length - 1];
}

/** Parses a dotted identifier while respecting all supported quote styles. */
export function parseSqlIdentifierPath(text: string, baseOffset = 0): SqlIdentifierPart[] {
  const tokens = tokenizeSql(text, baseOffset).filter(isSignificantToken);
  const parts: SqlIdentifierPart[] = [];
  let expectIdentifier = true;

  for (const token of tokens) {
    if (expectIdentifier && token.kind === 'identifier') {
      parts.push(identifierPart(token));
      expectIdentifier = false;
      continue;
    }
    if (!expectIdentifier && token.kind === 'symbol' && token.text === '.') {
      expectIdentifier = true;
      continue;
    }
    break;
  }
  return parts;
}

/** Quotes and escapes an identifier using the requested database style. */
export function quoteSqlIdentifier(value: string, style: SqlQuoteStyle): string {
  if (style === 'double') return `"${value.split('"').join('""')}"`;
  if (style === 'backtick') return `\`${value.split('`').join('``')}\``;
  if (style === 'bracket') return `[${value.split(']').join(']]')}]`;
  return value;
}

/**
 * Returns an insertion qualifier for a table. Aliases win over table names.
 * Existing quote styles are preserved when possible.
 */
export function sqlTableQualifier(table: SqlTableReference): string {
  if (table.alias) return table.aliasText ?? quoteSqlIdentifier(table.alias, table.aliasQuoteStyle ?? 'none');
  return table.parts[table.parts.length - 1]?.text ?? table.name;
}

function getCompletionReplacement(sql: string, tokens: SqlToken[], cursor: number): SqlCompletionReplacement {
  const token = tokens.find((candidate) => (
    candidate.kind === 'identifier'
    && candidate.start < cursor
    && cursor <= candidate.end
  ));

  if (!token) {
    return { start: cursor, end: cursor, text: '', prefix: '', quoteStyle: 'none' };
  }

  const text = sql.slice(token.start, cursor);
  return {
    start: token.start,
    end: cursor,
    text,
    prefix: decodeIdentifierPrefix(text, token.quoteStyle),
    quoteStyle: token.quoteStyle
  };
}

function tokenContainingCursor(tokens: SqlToken[], cursor: number): SqlToken | undefined {
  return tokens.find((token) => (
    token.start < cursor
    && (cursor < token.end || (cursor === token.end && !token.closed))
  ));
}

function readQualifierParts(tokens: SqlToken[]): string[] {
  if (tokens.length < 2 || tokens[tokens.length - 1].text !== '.') return [];
  const reversed: string[] = [];
  let index = tokens.length - 2;
  let expectIdentifier = true;

  while (index >= 0) {
    const token = tokens[index];
    if (expectIdentifier) {
      if (token.kind !== 'identifier') break;
      reversed.push(token.value);
      expectIdentifier = false;
      index -= 1;
      continue;
    }
    if (token.text !== '.') break;
    expectIdentifier = true;
    index -= 1;
  }
  return reversed.reverse();
}

type TableScanFrame = {
  clause: 'unknown' | 'select' | 'from' | 'update' | 'into' | 'other';
  fromList: boolean;
  expectTable: boolean;
  readingTable: boolean;
  expectQualifiedPart: boolean;
};

function isTableCompletionPosition(tokens: SqlToken[]): boolean {
  const frames: TableScanFrame[] = [newScanFrame()];

  for (let index = 0; index < tokens.length; index += 1) {
    const token = tokens[index];
    let frame = frames[frames.length - 1];

    if (token.text === '(') {
      if (frame.expectTable) {
        frame.expectTable = false;
        frame.readingTable = false;
        frame.expectQualifiedPart = false;
      }
      frames.push(newScanFrame());
      continue;
    }
    if (token.text === ')') {
      if (frames.length > 1) frames.pop();
      continue;
    }

    frame = frames[frames.length - 1];
    const keyword = unquotedKeyword(token);

    if (keyword && TABLE_SOURCE_KEYWORDS.has(keyword)) {
      frame.clause = keyword.toLowerCase() as TableScanFrame['clause'];
      frame.fromList = keyword === 'FROM' || keyword === 'JOIN';
      frame.expectTable = true;
      frame.readingTable = false;
      frame.expectQualifiedPart = false;
      continue;
    }

    if (keyword === 'SELECT') {
      frame.clause = 'select';
      frame.fromList = false;
      frame.expectTable = false;
      frame.readingTable = false;
      continue;
    }

    if (keyword && FROM_BOUNDARY_KEYWORDS.has(keyword)) {
      frame.clause = 'other';
      frame.fromList = false;
      frame.expectTable = false;
      frame.readingTable = false;
      frame.expectQualifiedPart = false;
      continue;
    }

    if (frame.expectTable) {
      if (keyword && TABLE_MODIFIERS.has(keyword)) continue;
      if (token.kind === 'identifier') {
        frame.expectTable = false;
        frame.readingTable = true;
        frame.expectQualifiedPart = false;
      } else if (token.text !== '.') {
        frame.expectTable = false;
      }
      continue;
    }

    if (frame.readingTable) {
      if (token.text === '.') {
        frame.expectQualifiedPart = true;
        continue;
      }
      if (frame.expectQualifiedPart && token.kind === 'identifier') {
        frame.expectQualifiedPart = false;
        continue;
      }
      frame.readingTable = false;
      frame.expectQualifiedPart = false;
      if (keyword === 'AS') continue;
      // An identifier immediately after a table is its implicit alias.
      if (token.kind === 'identifier' && !keyword) continue;
    }

    if (token.text === ',' && frame.fromList) {
      frame.expectTable = true;
      frame.readingTable = false;
      frame.expectQualifiedPart = false;
    }
  }

  const frame = frames[frames.length - 1];
  return frame.expectTable || frame.expectQualifiedPart;
}

function newScanFrame(): TableScanFrame {
  return {
    clause: 'unknown',
    fromList: false,
    expectTable: false,
    readingTable: false,
    expectQualifiedPart: false
  };
}

type ParsedTable = {
  reference: SqlTableReference;
  nextIndex: number;
};

function parseTableAt(
  tokens: SqlToken[],
  initialIndex: number,
  sourceKeyword: SqlTableSourceKeyword
): ParsedTable | undefined {
  let index = initialIndex;
  while (index < tokens.length && TABLE_MODIFIERS.has(unquotedKeyword(tokens[index]) ?? '')) index += 1;
  if (tokens[index]?.text === '(' || tokens[index]?.kind !== 'identifier') return undefined;

  const parts: SqlIdentifierPart[] = [identifierPart(tokens[index])];
  index += 1;
  while (
    tokens[index]?.kind === 'symbol'
    && tokens[index]?.text === '.'
    && tokens[index + 1]?.kind === 'identifier'
  ) {
    parts.push(identifierPart(tokens[index + 1]));
    index += 2;
  }

  let aliasPart: SqlIdentifierPart | undefined;
  if (isKeyword(tokens[index], 'AS') && isAliasToken(tokens[index + 1])) {
    aliasPart = identifierPart(tokens[index + 1]);
    index += 2;
  } else if (isAliasToken(tokens[index])) {
    aliasPart = identifierPart(tokens[index]);
    index += 1;
  }

  const namePart = parts[parts.length - 1];
  const schemaParts = parts.slice(0, -1);
  const qualifiedName = parts.map((part) => part.value).join('.');
  return {
    reference: {
      parts,
      name: namePart.value,
      schemaName: schemaParts.length > 0 ? schemaParts.map((part) => part.value).join('.') : undefined,
      qualifiedName,
      normalizedName: namePart.normalized,
      normalizedQualifiedName: normalizeIdentifier(qualifiedName),
      alias: aliasPart?.value,
      normalizedAlias: aliasPart?.normalized,
      aliasText: aliasPart?.text,
      aliasQuoteStyle: aliasPart?.quoteStyle,
      sourceKeyword,
      start: parts[0].start,
      end: aliasPart?.end ?? namePart.end
    },
    nextIndex: index
  };
}

function identifierPart(token: SqlToken): SqlIdentifierPart {
  return {
    value: token.value,
    normalized: normalizeIdentifier(token.value),
    text: token.text,
    quoteStyle: token.quoteStyle,
    start: token.start,
    end: token.end
  };
}

function isAliasToken(token: SqlToken | undefined): token is SqlToken {
  if (!token || token.kind !== 'identifier') return false;
  const keyword = unquotedKeyword(token);
  return !keyword || !RESERVED_ALIAS_KEYWORDS.has(keyword);
}

function tableSourceKeyword(token: SqlToken | undefined): SqlTableSourceKeyword | undefined {
  const keyword = unquotedKeyword(token);
  return keyword && TABLE_SOURCE_KEYWORDS.has(keyword) ? keyword as SqlTableSourceKeyword : undefined;
}

function isFromBoundary(token: SqlToken): boolean {
  const keyword = unquotedKeyword(token);
  return Boolean(keyword && FROM_BOUNDARY_KEYWORDS.has(keyword));
}

function depthBefore(tokens: SqlToken[], endIndex: number): number {
  let depth = 0;
  for (let index = 0; index < endIndex; index += 1) {
    if (tokens[index].text === '(') depth += 1;
    if (tokens[index].text === ')') depth = Math.max(0, depth - 1);
  }
  return depth;
}

function isSignificantToken(token: SqlToken): boolean {
  return token.kind !== 'whitespace' && token.kind !== 'comment';
}

function isKeyword(token: SqlToken | undefined, keyword: string): boolean {
  return unquotedKeyword(token) === keyword;
}

function unquotedKeyword(token: SqlToken | undefined): string | undefined {
  if (!token || token.kind !== 'identifier' || token.quoteStyle !== 'none') return undefined;
  return token.value.toUpperCase();
}

function decodeIdentifierPrefix(text: string, style: SqlQuoteStyle): string {
  if (style === 'double') return decodeDelimited(text, '"', '"', false);
  if (style === 'backtick') return decodeDelimited(text, '`', '`', false);
  if (style === 'bracket') return decodeDelimited(text, '[', ']', false);
  return text;
}

function decodeDelimited(text: string, open: string, close: string, closed: boolean): string {
  const withoutOpen = text.startsWith(open) ? text.slice(open.length) : text;
  const content = closed && withoutOpen.endsWith(close)
    ? withoutOpen.slice(0, -close.length)
    : withoutOpen;
  return content.split(close + close).join(close).replace(/\\(.)/g, '$1');
}

function readQuoted(
  sql: string,
  start: number,
  open: string,
  close: string,
  supportsBackslashEscape: boolean
): { end: number; closed: boolean } {
  let index = start + open.length;
  while (index < sql.length) {
    if (supportsBackslashEscape && sql[index] === '\\' && index + 1 < sql.length) {
      index += 2;
      continue;
    }
    if (sql[index] === close) {
      if (sql[index + 1] === close) {
        index += 2;
        continue;
      }
      return { end: index + close.length, closed: true };
    }
    index += 1;
  }
  return { end: sql.length, closed: false };
}

function readNumber(sql: string, start: number): number {
  let index = start;
  while (index < sql.length && isDigit(sql[index])) index += 1;
  if (sql[index] === '.' && isDigit(sql[index + 1])) {
    index += 1;
    while (index < sql.length && isDigit(sql[index])) index += 1;
  }
  if (sql[index] === 'e' || sql[index] === 'E') {
    let exponentIndex = index + 1;
    if (sql[exponentIndex] === '+' || sql[exponentIndex] === '-') exponentIndex += 1;
    if (isDigit(sql[exponentIndex])) {
      index = exponentIndex + 1;
      while (index < sql.length && isDigit(sql[index])) index += 1;
    }
  }
  return index;
}

function pushToken(
  tokens: SqlToken[],
  kind: SqlTokenKind,
  source: string,
  start: number,
  end: number,
  baseOffset: number,
  quoteStyle: SqlQuoteStyle = 'none',
  closed = true
): void {
  const text = source.slice(start, end);
  tokens.push({
    kind,
    text,
    value: text,
    start: baseOffset + start,
    end: baseOffset + end,
    quoteStyle,
    closed
  });
}

function normalizeIdentifier(value: string): string {
  return value.toLocaleLowerCase();
}

function isWhitespace(char: string | undefined): boolean {
  return char !== undefined && /\s/u.test(char);
}

function isIdentifierStart(char: string | undefined): boolean {
  return char !== undefined && /[\p{L}\p{Nl}_$@#]/u.test(char);
}

function isIdentifierContinue(char: string | undefined): boolean {
  return char !== undefined && /[\p{L}\p{Nl}\p{Mn}\p{Mc}\p{Nd}_$@#]/u.test(char);
}

function isDigit(char: string | undefined): boolean {
  return char !== undefined && char >= '0' && char <= '9';
}

function clamp(value: number, minimum: number, maximum: number): number {
  return Math.min(maximum, Math.max(minimum, value));
}
