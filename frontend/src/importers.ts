import type { ImportFormat, ImportResult } from './types';

const MAX_IMPORT_ROWS = 1000;

export function parseImportFile(text: string, filename: string, tableName: string, columns: string[]): ImportResult {
  const format = importFormat(filename);
  const rows = format === 'csv'
    ? parseCsvRows(text, columns)
    : format === 'json'
      ? parseJsonRows(text, columns)
      : parseSqlRows(text, tableName, columns);
  if (rows.length === 0) {
    throw new Error('导入文件没有可导入的数据。');
  }
  if (rows.length > MAX_IMPORT_ROWS) {
    throw new Error(`单次最多导入 ${MAX_IMPORT_ROWS} 行，请拆分文件后重试。`);
  }
  return { rows, message: `已从 ${filename} 导入 ${rows.length} 行到待提交变更。` };
}

function importFormat(filename: string): ImportFormat {
  const ext = filename.split('.').pop()?.toLowerCase();
  if (ext === 'csv' || ext === 'json' || ext === 'sql') return ext;
  throw new Error('仅支持导入 CSV、JSON、SQL 文件。');
}

function parseCsvRows(text: string, columns: string[]) {
  const records = parseCsv(text.replace(/^\uFEFF/, ''));
  if (records.length < 2) {
    throw new Error('CSV 文件必须包含表头和至少一行数据。');
  }
  const headers = canonicalHeaders(records[0].map((value) => value.trim()), columns);
  return records.slice(1)
    .filter((record) => record.some((value) => value !== ''))
    .map((record, rowIndex) => {
      if (record.length > headers.length && record.slice(headers.length).some((value) => value !== '')) {
        throw new Error(`CSV 第 ${rowIndex + 2} 行包含超出表头的字段。`);
      }
      return Object.fromEntries(headers.flatMap((header, index) => {
        const value = record[index] ?? '';
        return value === '' ? [] : [[header, value]];
      }));
    });
}

function parseCsv(text: string) {
  const rows: string[][] = [];
  let row: string[] = [];
  let cell = '';
  let quoted = false;
  for (let index = 0; index < text.length; index++) {
    const char = text[index];
    if (quoted) {
      if (char === '"' && text[index + 1] === '"') {
        cell += '"';
        index++;
      } else if (char === '"') {
        quoted = false;
      } else {
        cell += char;
      }
      continue;
    }
    if (char === '"') {
      quoted = true;
    } else if (char === ',') {
      row.push(cell);
      cell = '';
    } else if (char === '\n') {
      row.push(cell);
      rows.push(row);
      enforceImportRowLimit(rows.length - 1);
      row = [];
      cell = '';
    } else if (char !== '\r') {
      cell += char;
    }
  }
  if (quoted) throw new Error('CSV 文件存在未闭合的引号。');
  row.push(cell);
  rows.push(row);
  enforceImportRowLimit(rows.length - 1);
  return rows.filter((record, index) => index < rows.length - 1 || record.some((value) => value !== ''));
}

function parseJsonRows(text: string, columns: string[]) {
  const parsed: unknown = JSON.parse(text);
  if (isPositionalJsonExport(parsed)) {
    enforceImportRowLimit(parsed.rows.length);
    const headers = canonicalHeaders(parsed.columns, columns);
    return parsed.rows.map((row, index) => {
      if (!Array.isArray(row) || row.length !== headers.length) {
        throw new Error(`JSON 第 ${index + 1} 行字段数量与 columns 不一致。`);
      }
      return Object.fromEntries(headers.map((header, columnIndex) => [header, row[columnIndex]]));
    });
  }
  if (!Array.isArray(parsed)) {
    throw new Error('JSON 文件必须是对象数组，或包含 columns/rows 的查询导出格式。');
  }
  enforceImportRowLimit(parsed.length);
  return parsed.map((row, index) => {
    if (!row || Array.isArray(row) || typeof row !== 'object') {
      throw new Error(`JSON 第 ${index + 1} 行不是对象。`);
    }
    const keys = Object.keys(row);
    const headers = canonicalHeaders(keys, columns);
    const source = row as Record<string, unknown>;
    return Object.fromEntries(keys.map((key, keyIndex) => [headers[keyIndex], source[key]]));
  });
}

function isPositionalJsonExport(value: unknown): value is { columns: string[]; rows: unknown[][] } {
  if (!value || Array.isArray(value) || typeof value !== 'object') return false;
  const candidate = value as { columns?: unknown; rows?: unknown };
  return Array.isArray(candidate.columns)
    && candidate.columns.every((column) => typeof column === 'string')
    && Array.isArray(candidate.rows);
}

function parseSqlRows(text: string, tableName: string, columns: string[]) {
  const statements = splitSqlStatements(text).filter((statement) => statement.trim() !== '');
  if (statements.length === 0) {
    throw new Error('SQL 文件没有可导入的 INSERT 语句。');
  }
  const rows: Record<string, unknown>[] = [];
  statements.forEach((statement, index) => {
    rows.push(...parseInsertStatement(statement, index, tableName, columns));
    enforceImportRowLimit(rows.length);
  });
  return rows;
}

function splitSqlStatements(text: string) {
  const statements: string[] = [];
  let current = '';
  let quoted = false;
  for (let index = 0; index < text.length; index++) {
    const char = text[index];
    current += char;
    if (char === "'" && text[index + 1] === "'") {
      current += text[index + 1];
      index++;
      continue;
    }
    if (char === "'") {
      quoted = !quoted;
    } else if (char === ';' && !quoted) {
      statements.push(current.slice(0, -1));
      current = '';
    }
  }
  if (current.trim()) statements.push(current);
  return statements;
}

function parseInsertStatement(statement: string, index: number, tableName: string, tableColumns: string[]) {
  const match = statement.trim().match(/^insert\s+into\s+(.+?)\s*\(([\s\S]+?)\)\s*values\s*([\s\S]+)$/i);
  if (!match) {
    throw new Error(`SQL 第 ${index + 1} 条语句不是支持的 INSERT INTO ... VALUES 格式。`);
  }
  const targetTable = lastIdentifier(match[1]);
  if (columnKey(targetTable) !== columnKey(tableName) && columnKey(targetTable) !== 'query_result') {
    throw new Error(`SQL 第 ${index + 1} 条语句目标表 ${targetTable} 与当前表 ${tableName} 不一致。`);
  }
  const headers = canonicalHeaders(splitComma(match[2]).map((value) => normalizeIdentifier(value.trim())), tableColumns);
  return parseValueTuples(match[3]).map((tuple) => {
    const values = splitComma(tuple).map(parseSqlValue);
    if (values.length !== headers.length) {
      throw new Error(`SQL 第 ${index + 1} 条语句字段数和值数量不一致。`);
    }
    return Object.fromEntries(headers.map((header, valueIndex) => [header, values[valueIndex]]));
  });
}

function parseValueTuples(valuesSql: string) {
  const tuples: string[] = [];
  let current = '';
  let depth = 0;
  let quoted = false;
  for (let index = 0; index < valuesSql.length; index++) {
    const char = valuesSql[index];
    if (quoted) {
      current += char;
      if (char === "'" && valuesSql[index + 1] === "'") {
        current += valuesSql[index + 1];
        index++;
      } else if (char === "'") {
        quoted = false;
      }
      continue;
    }
    if (char === "'") {
      quoted = true;
      current += char;
    } else if (char === '(') {
      if (depth > 0) current += char;
      depth++;
    } else if (char === ')') {
      depth--;
      if (depth === 0) {
        tuples.push(current);
        enforceImportRowLimit(tuples.length);
        current = '';
      } else {
        current += char;
      }
    } else if (depth > 0) {
      current += char;
    } else if (char !== ',' && char.trim() !== '') {
      throw new Error('SQL INSERT 仅支持 VALUES 字面量列表。');
    }
  }
  if (depth !== 0 || quoted) {
    throw new Error('SQL INSERT VALUES 语法不完整。');
  }
  return tuples;
}

function splitComma(value: string) {
  const parts: string[] = [];
  let current = '';
  let quoted = false;
  for (let index = 0; index < value.length; index++) {
    const char = value[index];
    if (quoted) {
      current += char;
      if (char === "'" && value[index + 1] === "'") {
        current += value[index + 1];
        index++;
      } else if (char === "'") {
        quoted = false;
      }
      continue;
    }
    if (char === "'") {
      quoted = true;
      current += char;
    } else if (char === ',') {
      parts.push(current.trim());
      current = '';
    } else {
      current += char;
    }
  }
  parts.push(current.trim());
  return parts;
}

function parseSqlValue(value: string): unknown {
  if (/^null$/i.test(value)) return null;
  if (/^true$/i.test(value)) return true;
  if (/^false$/i.test(value)) return false;
  // Keep numeric literals as text. The backend coerces them using the target
  // JDBC type, while JavaScript Number would corrupt BIGINT/DECIMAL values.
  if (/^-?\d+(\.\d+)?$/.test(value)) return value;
  if (value.startsWith("'") && value.endsWith("'")) {
    return value.slice(1, -1).replace(/''/g, "'");
  }
  throw new Error(`不支持的 SQL 字面量：${value}`);
}

function canonicalHeaders(headers: string[], columns: string[]) {
  const normalizedHeaders = headers.map(normalizeIdentifier);
  if (normalizedHeaders.length === 0) {
    throw new Error('导入文件至少需要包含一个有效字段。');
  }
  if (normalizedHeaders.some((header) => header === '')) {
    throw new Error('导入字段不能为空。');
  }
  const resolved = normalizedHeaders.map((header) => {
    if (columns.includes(header)) return header;
    const folded = columns.filter((column) => columnKey(column) === columnKey(header));
    if (folded.length === 1) return folded[0];
    if (folded.length > 1) throw new Error(`导入字段大小写不明确，请使用精确名称：${header}`);
    throw new Error(`导入字段不存在于当前表：${header}`);
  });
  const duplicate = resolved.find((header, index) => resolved.indexOf(header) !== index);
  if (duplicate) {
    throw new Error(`导入字段重复：${duplicate}`);
  }
  return resolved;
}

function lastIdentifier(tableRef: string) {
  const parts = tableRef.trim().split('.');
  return normalizeIdentifier(parts[parts.length - 1]);
}

function normalizeIdentifier(value: string) {
  return value.trim().replace(/^["'`\[]/, '').replace(/["'`\]]$/, '');
}

function columnKey(value: string) {
  return normalizeIdentifier(value).toLowerCase();
}

function enforceImportRowLimit(rowCount: number) {
  if (rowCount > MAX_IMPORT_ROWS) {
    throw new Error(`单次最多导入 ${MAX_IMPORT_ROWS} 行，请拆分文件后重试。`);
  }
}
