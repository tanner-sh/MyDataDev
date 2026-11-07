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

export function inferSqlTargetParts(sql: string | undefined, sourceTable?: SqlResultSourceTable | null, dbType?: string): string[] | undefined {
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
  return validTargetParts(references[0].parts.map((part) => foldUnquotedIdentifier(part.value, part.quoteStyle, dbType)));
}

export function parseQualifiedTableName(input: string, dbType?: string): string[] | undefined {
  const tokens = tokenizeSql(input).filter((token) => token.kind !== 'whitespace' && token.kind !== 'comment');
  if (tokens.length === 0 || tokens.length > 5) return undefined;
  const parts: string[] = [];
  for (let index = 0; index < tokens.length; index += 1) {
    const token = tokens[index];
    if (index % 2 === 0) {
      if (token.kind !== 'identifier' || !token.closed || !token.value.trim()) return undefined;
      parts.push(foldUnquotedIdentifier(token.value, token.quoteStyle, dbType));
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

/**
 * 把未加引号的标识符折成库里实际存的形态，与后端 {@code DatabaseDialect.foldUnquotedIdentifier} 同一套规则。
 *
 * <p>生成的 INSERT 会给表名加引号，而加了引号就是按原样大小写匹配：用户写
 * {@code select * from sim_assetsprop}，Oracle 存的是 {@code SIM_ASSETSPROP}，原样引成
 * {@code "sim_assetsprop"} 就是 ORA-00942。加了引号的名字不折 —— 那是用户明确写下的大小写。</p>
 */
function foldUnquotedIdentifier(value: string, quoteStyle: SqlQuoteStyle, dbType?: string): string {
  if (quoteStyle !== 'none') return value;
  const normalized = dbType?.toLowerCase() || '';
  if (['oracle', 'oceanbase-oracle', 'dm', 'dameng', 'h2'].includes(normalized)) return value.toUpperCase();
  if (normalized === 'postgresql') return value.toLowerCase();
  return value;
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

export const EXPORT_FORMAT_OPTIONS: ReadonlyArray<{ format: ExportFormat; label: string }> = [
  { format: 'csv', label: 'CSV' },
  { format: 'json', label: 'JSON' },
  { format: 'sql', label: 'SQL 插入' },
  { format: 'xml', label: 'XML' },
  { format: 'markdown', label: 'Markdown 表格' },
  { format: 'xlsx', label: 'Excel' }
];

export type ResultExportScope = 'loaded' | 'full';

export type ResultExportGroup = {
  scope: ResultExportScope;
  label: string;
  formats: ReadonlyArray<{ key: string; format: ExportFormat; label: string }>;
};

/**
 * 结果区导出菜单的分组。
 *
 * SQL 结果原来有两个导出入口：结果区的「导出本批」（前端序列化已加载的行）和工作台「更多」里的
 * 「重新查询并导出」（服务端重跑），格式列表还不一样。合成一个下拉之后，两组的差别必须写在
 * 组标题上 —— 一组不发请求、一组会重新执行；而重新执行的是原 SQL，结果区里下推的筛选与排序
 * 不在里面，这时要明说，否则「界面上 12 行、导出 40 行」又会出现。
 */
export function resultExportGroups({ loadedRows, selectedRows, fullAvailable, narrowed }: {
  loadedRows: number;
  selectedRows: number;
  fullAvailable: boolean;
  /** 结果区当前有筛选或排序（本地或下推的）。 */
  narrowed: boolean;
}): ResultExportGroup[] {
  const formats = (scope: ResultExportScope) => EXPORT_FORMAT_OPTIONS.map((option) => ({ ...option, key: `${scope}:${option.format}` }));
  const groups: ResultExportGroup[] = [{
    scope: 'loaded',
    label: selectedRows > 0 ? `已选 ${selectedRows} 行` : `当前批次 ${loadedRows} 行`,
    formats: formats('loaded')
  }];
  if (fullAvailable) {
    groups.push({
      scope: 'full',
      // 不叫「完整结果」：服务端导出有行数上限（默认一万行，截断时会提示），说「完整」是许诺不了的事。
      label: narrowed ? '重新执行原 SQL 导出（不含筛选与排序）' : '重新执行原 SQL 导出',
      formats: formats('full')
    });
  }
  return groups;
}

export type ResultExportMenuItem =
  | { type: 'group'; key: string; label: string; children: Array<{ key: string; label: string }> }
  | { key: string; label: string; children: Array<{ key: string; label: string }> };

/**
 * 导出分组排成下拉菜单的形状。
 *
 * 只有一组时平铺（组标题 + 六个格式，一眼看全）；两组时每组收成一个子菜单。两组平铺是
 * 两个组标题加十二个格式、十四行约 480px，结果区在窗口下半部，向下放不下、向上翻又会被
 * 视口顶边切掉 —— 第一组的标题和前几个格式就这样看不见了，而那正是最常用的一组。
 * 组标题仍然原样当子菜单的名字：「不含筛选与排序」这句提示不能因为收起来就丢掉。
 */
export function resultExportMenuItems(groups: ResultExportGroup[]): ResultExportMenuItem[] {
  const children = (group: ResultExportGroup) => group.formats.map((item) => ({ key: item.key, label: item.label }));
  if (groups.length <= 1) {
    return groups.map((group) => ({ type: 'group' as const, key: group.scope, label: group.label, children: children(group) }));
  }
  return groups.map((group) => ({ key: group.scope, label: group.label, children: children(group) }));
}

export function parseResultExportKey(key: string): { scope: ResultExportScope; format: ExportFormat } | undefined {
  const [scope, format] = key.split(':');
  if (scope !== 'loaded' && scope !== 'full') return undefined;
  const option = EXPORT_FORMAT_OPTIONS.find((item) => item.format === format);
  return option ? { scope, format: option.format } : undefined;
}
