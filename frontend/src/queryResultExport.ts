import { isNumericColumnType } from './resultGridData';
import { parseSqlTableReferences, tokenizeSql, type SqlQuoteStyle } from './sqlCompletion';
import type { ExportFormat, ResultColumn, ResultCopyFormat, SqlResultSourceTable } from './types';

export type ResultSerializationOptions = {
  dbType?: string;
  targetTableParts?: string[];
  truncated?: boolean;
  maxRows?: number;
};

const NUMERIC_TYPES = /(^|\s)(tinyint|smallint|mediumint|int|integer|bigint|decimal|numeric|number|real|float|double|serial|bigserial)(\s|$|\()/i;
const NUMERIC_VALUE = /^[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?$/;
const BINARY_TYPES = /(^|\s)(binary|varbinary|longvarbinary|blob|bytea|raw|image)(\s|$|\()/i;
const OMITTED_VALUE = /^<(?:BLOB|BINARY)\s/i;
const TRUNCATED_VALUE = /<(?:CLOB\s*已截断|文本已截断)[^>]*>$/;
const COPY_FORMAT_KEY = 'mydatadev.result-copy-format';

export function inferSqlTargetParts(sql: string | undefined, sourceTable?: SqlResultSourceTable | null): string[] | undefined {
  if (!sql?.trim()) return validTargetParts(sourceTable?.nameParts);
  const tokens = tokenizeSql(sql).filter((token) => token.kind !== 'whitespace' && token.kind !== 'comment');
  const firstIdentifier = tokens.find((token) => token.kind === 'identifier');
  if (!firstIdentifier || firstIdentifier.value.toUpperCase() !== 'SELECT') return undefined;

  let depth = 0;
  const depthAt = new Map<number, number>();
  for (const token of tokens) {
    depthAt.set(token.start, depth);
    if (token.text === '(') depth += 1;
    else if (token.text === ')') depth = Math.max(0, depth - 1);
  }

  const references = parseSqlTableReferences(sql).filter((reference) => reference.sourceKeyword === 'FROM' || reference.sourceKeyword === 'JOIN');
  if (references.length !== 1 || depthAt.get(references[0].start) !== 0) return undefined;
  const topLevelComplexKeyword = tokens.some((token) => depthAt.get(token.start) === 0
    && token.kind === 'identifier'
    && ['WITH', 'JOIN', 'UNION', 'INTERSECT', 'EXCEPT', 'MINUS'].includes(token.value.toUpperCase()));
  if (topLevelComplexKeyword) return undefined;
  return validTargetParts(references[0].parts.map((part) => part.value));
}

export function parseQualifiedTableName(input: string): string[] | undefined {
  const tokens = tokenizeSql(input).filter((token) => token.kind !== 'whitespace' && token.kind !== 'comment');
  if (tokens.length === 0 || tokens.length > 5) return undefined;
  const parts: string[] = [];
  for (let index = 0; index < tokens.length; index += 1) {
    const token = tokens[index];
    if (index % 2 === 0) {
      if (token.kind !== 'identifier' || !token.closed || !token.value.trim()) return undefined;
      parts.push(token.value);
    } else if (token.kind !== 'symbol' || token.text !== '.') {
      return undefined;
    }
  }
  return tokens.length % 2 === 1 ? validTargetParts(parts) : undefined;
}

export function quoteQualifiedTable(parts: string[], dbType?: string): string {
  const style = quoteStyleForDb(dbType);
  return parts.map((part) => quoteIdentifier(part, style)).join('.');
}

/**
 * 导出文件的扩展名。
 *
 * <p>格式名不能直接当扩展名用：Markdown 的惯例扩展名是 .md，写成 .markdown 会让不少工具
 * （GitHub 附件预览、编辑器）认不出来。</p>
 */
export function exportFileExtension(format: ExportFormat): string {
  return format === 'markdown' ? 'md' : format;
}

export function serializeQueryResult(
  format: ExportFormat,
  columns: ResultColumn[],
  rows: unknown[][],
  options: ResultSerializationOptions = {}
): string {
  switch (format) {
    case 'csv': return serializeCsv(columns, rows);
    case 'json': return serializeJson(columns, rows, options);
    case 'xml': return serializeXml(columns, rows, options);
    case 'markdown': return serializeMarkdown(columns, rows);
    case 'sql': return serializeSql(columns, rows, options);
    // xlsx 是二进制，由 xlsx.ts 直接生成 Blob；调用方在分支时就该把它挑出去。
    case 'xlsx': throw new Error('Excel 导出不走文本序列化，请调用 buildXlsx');
  }
}

export function serializeCopiedRows(
  format: ResultCopyFormat,
  columns: ResultColumn[],
  rows: unknown[][],
  options: ResultSerializationOptions = {}
): string {
  return format === 'sql' ? serializeSql(columns, rows, options) : serializePipe(rows);
}

export function readResultCopyFormat(storage: Pick<Storage, 'getItem'> | undefined = typeof localStorage === 'undefined' ? undefined : localStorage): ResultCopyFormat {
  try {
    return storage?.getItem(COPY_FORMAT_KEY) === 'sql' ? 'sql' : 'pipe';
  } catch {
    return 'pipe';
  }
}

export function writeResultCopyFormat(format: ResultCopyFormat, storage: Pick<Storage, 'setItem'> | undefined = typeof localStorage === 'undefined' ? undefined : localStorage) {
  try {
    storage?.setItem(COPY_FORMAT_KEY, format);
  } catch {
    // Storage can be unavailable in private browsing or embedded desktop shells.
  }
}

function serializeCsv(columns: ResultColumn[], rows: unknown[][]): string {
  const lines = [columns.map((column) => csvValue(column.label)).join(',')];
  for (const row of rows) lines.push(columns.map((_column, index) => csvValue(row[index])).join(','));
  return `\uFEFF${lines.join('\r\n')}\r\n`;
}

function serializeJson(columns: ResultColumn[], rows: unknown[][], options: ResultSerializationOptions): string {
  return JSON.stringify({
    columns: columns.map((column) => column.label),
    rows,
    truncated: Boolean(options.truncated),
    maxRows: options.maxRows ?? rows.length
  }, (_key, value) => typeof value === 'bigint' ? value.toString() : value, 2);
}

function serializeXml(columns: ResultColumn[], rows: unknown[][], options: ResultSerializationOptions): string {
  const body = rows.map((row) => [
    '    <row>',
    ...columns.map((column, index) => `      <column name="${xmlValue(column.label)}">${xmlValue(displayValue(row[index]))}</column>`),
    '    </row>'
  ].join('\n')).join('\n');
  return `<?xml version="1.0" encoding="UTF-8"?>\n<result>\n  <rows>\n${body}${body ? '\n' : ''}  </rows>\n  <truncated>${Boolean(options.truncated)}</truncated>\n  <maxRows>${options.maxRows ?? rows.length}</maxRows>\n</result>\n`;
}

function serializeSql(columns: ResultColumn[], rows: unknown[][], options: ResultSerializationOptions): string {
  const target = validTargetParts(options.targetTableParts);
  if (!target) throw new Error('无法确定 SQL 插入的目标表，请先指定目标表');
  if (rows.length === 0) return '-- 查询结果为空，未生成 INSERT 语句。\n';
  const style = quoteStyleForDb(options.dbType);
  const names = uniqueColumnNames(columns).map((name) => quoteIdentifier(name, style)).join(', ');
  const table = quoteQualifiedTable(target, options.dbType);
  return `${rows.map((row) => {
    const values = columns.map((column, index) => sqlLiteral(row[index], column.typeName, options.dbType)).join(', ');
    return `INSERT INTO ${table} (${names}) VALUES (${values});`;
  }).join('\n')}\n`;
}

/**
 * Markdown 表格。
 *
 * <p>贴进 issue、PR 或文档里用的，所以对齐方式要按列类型给：数值列右对齐，读起来才能对位比较，
 * 这和结果表格里数值右对齐是同一条规矩。</p>
 *
 * <p>Markdown 表格的单元格不能跨行，也不能出现裸的竖线。换行折成 {@code <br>} 而不是丢弃 ——
 * 丢掉会让多行文本看起来像是被截断了；竖线转义成 {@code \|}，否则一个值里的竖线会把整行的列
 * 数撑乱，后面所有列都错位。</p>
 */
function serializeMarkdown(columns: ResultColumn[], rows: unknown[][]): string {
  const header = `| ${columns.map((column) => markdownValue(column.label)).join(' | ')} |`;
  const alignment = `| ${columns.map((column) => isNumericColumnType(column.typeName) ? '---:' : '---').join(' | ')} |`;
  const body = rows.map((row) => `| ${columns.map((_column, index) => markdownValue(row[index])).join(' | ')} |`);
  // 结果为空时仍然输出表头：贴出去的人至少能看到查了哪些列。
  return [header, alignment, ...body].join('\n') + '\n';
}

function markdownValue(value: unknown): string {
  const text = displayValue(value);
  if (!text) return '';
  return text
    .split('\\').join('\\\\')
    .split('|').join('\\|')
    .split('\r\n').join('<br>')
    .split('\n').join('<br>')
    .split('\r').join('<br>');
}

function serializePipe(rows: unknown[][]): string {
  return rows.map((row) => row.map((value) => pipeValue(value)).join('|')).join('\n');
}

function uniqueColumnNames(columns: ResultColumn[]): string[] {
  const used = new Set<string>();
  return columns.map((column) => {
    const base = column.label;
    let name = base;
    let suffix = 2;
    while (used.has(name.toLocaleLowerCase())) name = `${base}_${suffix++}`;
    used.add(name.toLocaleLowerCase());
    return name;
  });
}

function csvValue(value: unknown): string {
  return `"${displayValue(value).split('"').join('""')}"`;
}

function pipeValue(value: unknown): string {
  if (value == null) return 'NULL';
  return displayValue(value)
    .split('\\').join('\\\\')
    .split('|').join('\\|')
    .split('\r').join('\\r')
    .split('\n').join('\\n');
}

function sqlLiteral(value: unknown, typeName: string, dbType?: string): string {
  if (value == null) return 'NULL';
  if (value instanceof Uint8Array) return binaryLiteral(value, dbType);
  if (typeof value === 'boolean') {
    return booleanUsesNumericLiteral(dbType) ? (value ? '1' : '0') : (value ? 'TRUE' : 'FALSE');
  }
  if (typeof value === 'number' || typeof value === 'bigint') return String(value);
  const text = displayValue(value);
  if (NUMERIC_TYPES.test(typeName) && NUMERIC_VALUE.test(text.trim())) return text.trim();
  if ((BINARY_TYPES.test(typeName) && OMITTED_VALUE.test(text)) || TRUNCATED_VALUE.test(text)) {
    throw new Error('当前结果包含已省略或截断的单元格，请使用“导出全部结果”生成可还原的 SQL 文件');
  }
  return stringLiteral(text, dbType);
}

function stringLiteral(value: string, dbType?: string): string {
  const normalized = dbType?.toLowerCase() || '';
  if (['mysql', 'mariadb', 'oceanbase-mysql'].includes(normalized) && value.includes('\\')) {
    return `_utf8mb4 0x${toHex(new TextEncoder().encode(value))}`;
  }
  const escapedQuotes = value.split("'").join("''");
  if (normalized === 'clickhouse') return `'${escapedQuotes.split('\\').join('\\\\')}'`;
  if (normalized === 'postgresql' && value.includes('\\')) return `E'${escapedQuotes.split('\\').join('\\\\')}'`;
  if (['sqlserver', 'sql-server', 'mssql'].includes(normalized)) return `N'${escapedQuotes}'`;
  return `'${escapedQuotes}'`;
}

function booleanUsesNumericLiteral(dbType?: string): boolean {
  return ['mysql', 'mariadb', 'oceanbase-mysql', 'sqlserver', 'sql-server', 'mssql', 'oracle', 'oceanbase-oracle', 'dm', 'dameng']
    .includes(dbType?.toLowerCase() || '');
}

function binaryLiteral(value: Uint8Array, dbType?: string): string {
  const hex = toHex(value);
  const normalized = dbType?.toLowerCase() || '';
  if (['mysql', 'mariadb', 'oceanbase-mysql', 'sqlserver', 'sql-server', 'mssql'].includes(normalized)) return `0x${hex}`;
  if (normalized === 'postgresql') return `decode('${hex}', 'hex')`;
  if (normalized === 'clickhouse') return `unhex('${hex}')`;
  if (['oracle', 'oceanbase-oracle', 'dm', 'dameng'].includes(normalized)) return `hextoraw('${hex}')`;
  return `X'${hex}'`;
}

function toHex(value: Uint8Array): string {
  return Array.from(value, (byte) => byte.toString(16).padStart(2, '0')).join('');
}

function displayValue(value: unknown): string {
  if (value == null) return '';
  if (typeof value === 'object') {
    try {
      return JSON.stringify(value, (_key, nested) => typeof nested === 'bigint' ? nested.toString() : nested);
    } catch {
      return String(value);
    }
  }
  return String(value);
}

function xmlValue(value: string): string {
  return value.split('&').join('&amp;').split('<').join('&lt;').split('>').join('&gt;').split('"').join('&quot;').split("'").join('&apos;');
}

function quoteStyleForDb(dbType?: string): SqlQuoteStyle {
  const normalized = dbType?.toLowerCase() || '';
  if (['mysql', 'mariadb', 'oceanbase-mysql', 'clickhouse'].includes(normalized)) return 'backtick';
  if (normalized === 'sqlserver') return 'bracket';
  return 'double';
}

function quoteIdentifier(value: string, style: SqlQuoteStyle): string {
  if (style === 'backtick') return `\`${value.split('`').join('``')}\``;
  if (style === 'bracket') return `[${value.split(']').join(']]')}]`;
  return `"${value.split('"').join('""')}"`;
}

function validTargetParts(parts?: string[] | null): string[] | undefined {
  if (!parts || parts.length === 0 || parts.length > 3 || parts.some((part) => !part.trim())) return undefined;
  return parts.map((part) => part.trim());
}
