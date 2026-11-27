import type { OutputFormat } from '../types.js';
import { CliError } from '../errors.js';

export function resolveOutputFormat(
  explicit: OutputFormat | undefined,
  isTTY: boolean = Boolean(process.stdout.isTTY)
): OutputFormat {
  if (explicit) return explicit;
  return isTTY ? 'table' : 'json';
}

export function formatValue(value: unknown): string {
  if (value === null || value === undefined) return '';
  if (typeof value === 'object') return JSON.stringify(value);
  return String(value);
}

/** 计算字符串在终端中的显示宽度（中日韩全角字符占 2 格）。 */
export function displayWidth(text: string): number {
  let width = 0;
  for (const char of text) {
    const code = char.codePointAt(0) ?? 0;
    if (
      (code >= 0x1100 && code <= 0x115f) ||
      (code >= 0x2e80 && code <= 0xa4cf) ||
      (code >= 0xac00 && code <= 0xd7a3) ||
      (code >= 0xf900 && code <= 0xfaff) ||
      (code >= 0xfe10 && code <= 0xfe6f) ||
      (code >= 0xff00 && code <= 0xff60) ||
      (code >= 0xffe0 && code <= 0xffe6) ||
      (code >= 0x20000 && code <= 0x2fffd)
    ) {
      width += 2;
    } else {
      width += 1;
    }
  }
  return width;
}

function padEndDisplay(text: string, targetWidth: number): string {
  const pad = Math.max(0, targetWidth - displayWidth(text));
  return text + ' '.repeat(pad);
}

export function formatTable(headers: string[], rows: unknown[][]): string {
  const strRows = rows.map((row) => headers.map((_, idx) => formatValue(row[idx]).replace(/\r?\n/g, ' ')));
  const widths = headers.map((header, idx) => {
    let max = displayWidth(header);
    for (const row of strRows) {
      const w = displayWidth(row[idx]);
      if (w > max) max = w;
    }
    return Math.min(max, 80);
  });

  const formatRow = (cells: string[]) =>
    cells.map((cell, idx) => padEndDisplay(cell, widths[idx])).join('  ').trimEnd();

  const lines = [
    formatRow(headers),
    widths.map((w) => '-'.repeat(Math.max(1, w))).join('  ')
  ];
  for (const row of strRows) {
    lines.push(formatRow(row));
  }
  return lines.join('\n');
}

export function formatCsv(headers: string[], rows: unknown[][]): string {
  const escape = (val: unknown) => {
    const str = formatValue(val);
    if (/[",\r\n]/.test(str)) {
      return `"${str.replace(/"/g, '""')}"`;
    }
    return str;
  };
  const lines = [headers.map(escape).join(',')];
  for (const row of rows) {
    lines.push(headers.map((_, idx) => escape(row[idx])).join(','));
  }
  return lines.join('\n');
}

export function formatTabular(
  format: OutputFormat,
  headers: string[],
  rows: unknown[][],
  rawJsonObject?: unknown
): string {
  if (format === 'json' && rawJsonObject !== undefined) return JSON.stringify(rawJsonObject, null, 2);
  if (format === 'ndjson' || format === 'json') {
    const reserved = new Set(headers);
    const nextSuffix = new Map<string, number>();
    const keys = headers.map((header) => {
      let suffix = nextSuffix.get(header);
      if (suffix === undefined) {
        nextSuffix.set(header, 2);
        return header;
      }
      let key = `${header}_${suffix++}`;
      while (reserved.has(key)) key = `${header}_${suffix++}`;
      reserved.add(key);
      nextSuffix.set(header, suffix);
      return key;
    });
    const objects = rows.map((row) => Object.fromEntries(keys.map((key, i) => [key, row[i] ?? null])));
    return format === 'ndjson' ? objects.map((row) => JSON.stringify(row)).join('\n') : JSON.stringify(objects, null, 2);
  }
  if (format === 'csv') {
    return formatCsv(headers, rows);
  }
  return formatTable(headers, rows);
}

export function formatKeyValue(
  format: OutputFormat,
  entries: Array<[string, unknown]>,
  rawJsonObject?: Record<string, unknown>
): string {
  if (format === 'json') {
    if (rawJsonObject) return JSON.stringify(rawJsonObject, null, 2);
    return JSON.stringify(Object.fromEntries(entries), null, 2);
  }
  if (format === 'ndjson') {
    return JSON.stringify(rawJsonObject ?? Object.fromEntries(entries));
  }
  if (format === 'csv') {
    return formatCsv(['key', 'value'], entries.map(([k, v]) => [k, v]));
  }
  const keyWidth = Math.max(...entries.map(([k]) => displayWidth(k)), 4);
  return entries
    .map(([k, v]) => `${padEndDisplay(k, keyWidth)}  ${formatValue(v)}`)
    .join('\n');
}

export function formatErrorOutput(error: unknown, format: OutputFormat): string {
  const code = error instanceof CliError ? error.code : 'INTERNAL_ERROR';
  const message = error instanceof Error ? error.message : String(error);
  if (format === 'json' || format === 'ndjson') {
    return JSON.stringify({
      ok: false,
      code,
      message,
      ...(error instanceof CliError && error.details ? { details: error.details } : {})
    });
  }
  return `✖ [${code}] ${message}`;
}
