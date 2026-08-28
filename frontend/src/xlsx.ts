/**
 * 浏览器侧的最小 xlsx 生成。
 *
 * 「导出本批」是在浏览器里把已加载的行落成文件，不重新查询；后端那条「重新查询并导出」
 * 走的是 XlsxWriter。两边写的是同一个 OOXML 子集：一个工作表、inline string、不带
 * styles.xml，规则也保持一致 —— 否则同一份数据从两个入口导出会得到不同的类型。
 *
 * ZIP 用 STORED（不压缩）：这条路径导出的是当前批次（通常几百行），省下一个压缩库比省下
 * 那几百 KB 更划算。整包导出请走 SQL 工作台的「重新查询并导出」。
 */
import type { ResultColumn } from './types';

/** Excel 用 IEEE 754 双精度存数字，超过 15 位有效数字的整数会被静默改写。 */
const MAX_EXCEL_DIGITS = 15;
const MAX_CELL_CHARS = 32_767;
const NUMERIC_TYPES = /(^|\s)(tinyint|smallint|mediumint|int|integer|bigint|decimal|numeric|number|real|float|double|serial|bigserial)(\s|$|\()/i;
const NUMERIC_VALUE = /^[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?$/;

export type XlsxCell = { kind: 'text'; value: string } | { kind: 'number'; value: string } | { kind: 'boolean'; value: boolean } | { kind: 'empty' };

/**
 * 决定一个单元格按什么类型写。
 *
 * 只有「列本身是数值类型」且「值看起来是数值」才按数字写，其余一律文本 —— 这正是选 xlsx
 * 而不是 CSV 的理由：订单号不该被 Excel 猜成科学计数法，前导零不该被吃掉。
 */
export function xlsxCell(value: unknown, typeName?: string): XlsxCell {
  if (value == null) return { kind: 'empty' };
  if (typeof value === 'boolean') return { kind: 'boolean', value };
  if (typeof value === 'number') {
    if (!Number.isFinite(value)) return { kind: 'text', value: String(value) };
    return { kind: 'number', value: String(value) };
  }
  if (typeof value === 'bigint') {
    return significantDigits(value.toString()) > MAX_EXCEL_DIGITS
      ? { kind: 'text', value: value.toString() }
      : { kind: 'number', value: value.toString() };
  }
  const text = String(value);
  const trimmed = text.trim();
  if (typeName && NUMERIC_TYPES.test(typeName) && NUMERIC_VALUE.test(trimmed)
    && significantDigits(trimmed) <= MAX_EXCEL_DIGITS) {
    return { kind: 'number', value: trimmed };
  }
  return { kind: 'text', value: text.length > MAX_CELL_CHARS ? text.slice(0, MAX_CELL_CHARS) : text };
}

function significantDigits(value: string): number {
  return value.replace(/[^0-9]/g, '').replace(/^0+/, '').length;
}

/** 1 -> A、26 -> Z、27 -> AA。 */
export function columnReference(column: number): string {
  let letters = '';
  let remaining = column;
  while (remaining > 0) {
    const digit = (remaining - 1) % 26;
    letters = String.fromCharCode(65 + digit) + letters;
    remaining = Math.floor((remaining - 1) / 26);
  }
  return letters;
}

/**
 * XML 转义，并剔除 XML 1.0 不允许的控制字符 —— 留着它们 Excel 会判定文件损坏。
 */
export function escapeXml(value: string): string {
  let result = '';
  for (const character of value) {
    const code = character.codePointAt(0) ?? 0;
    if (character === '<') result += '&lt;';
    else if (character === '>') result += '&gt;';
    else if (character === '&') result += '&amp;';
    else if (character === '"') result += '&quot;';
    else if (character === "'") result += '&apos;';
    else if (code === 0x9 || code === 0xa || code === 0xd || (code >= 0x20 && code !== 0xfffe && code !== 0xffff)) {
      result += character;
    }
  }
  return result;
}

export function sheetXml(columns: ResultColumn[], rows: unknown[][]): string {
  const parts = [
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>',
    '<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>'
  ];
  parts.push(rowXml(1, columns.map((column) => ({ kind: 'text', value: column.label } as XlsxCell))));
  rows.forEach((row, index) => {
    parts.push(rowXml(index + 2, columns.map((column, columnIndex) => xlsxCell(row[columnIndex], column.typeName))));
  });
  parts.push('</sheetData></worksheet>');
  return parts.join('');
}

function rowXml(rowNumber: number, cells: XlsxCell[]): string {
  const body = cells.map((cell, index) => {
    if (cell.kind === 'empty') return '';
    const reference = `${columnReference(index + 1)}${rowNumber}`;
    if (cell.kind === 'number') return `<c r="${reference}" t="n"><v>${cell.value}</v></c>`;
    if (cell.kind === 'boolean') return `<c r="${reference}" t="b"><v>${cell.value ? 1 : 0}</v></c>`;
    return `<c r="${reference}" t="inlineStr"><is><t xml:space="preserve">${escapeXml(cell.value)}</t></is></c>`;
  }).join('');
  return `<row r="${rowNumber}">${body}</row>`;
}

export function buildXlsx(columns: ResultColumn[], rows: unknown[][], sheetName = '查询结果'): Blob {
  const encoder = new TextEncoder();
  const entries = [
    { name: '[Content_Types].xml', data: encoder.encode(CONTENT_TYPES) },
    { name: '_rels/.rels', data: encoder.encode(ROOT_RELS) },
    { name: 'xl/workbook.xml', data: encoder.encode(workbookXml(sheetName)) },
    { name: 'xl/_rels/workbook.xml.rels', data: encoder.encode(WORKBOOK_RELS) },
    { name: 'xl/worksheets/sheet1.xml', data: encoder.encode(sheetXml(columns, rows)) }
  ];
  return new Blob([zipStore(entries)], {
    type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'
  });
}

function workbookXml(sheetName: string): string {
  const name = sheetName.replace(/[\\/*?[\]:]/g, '_').slice(0, 31) || 'Sheet1';
  return '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
    + '<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"'
    + ' xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">'
    + `<sheets><sheet name="${escapeXml(name)}" sheetId="1" r:id="rId1"/></sheets></workbook>`;
}

/**
 * 打一个 STORED（不压缩）的 ZIP 包。
 *
 * 只实现 ZIP 里最小的那条路径：本地文件头、中央目录、目录结束记录。不压缩就不需要
 * deflate 实现，代价是文件更大 —— 对「当前批次」这个量级可以接受。
 */
export function zipStore(entries: Array<{ name: string; data: Uint8Array }>): Uint8Array<ArrayBuffer> {
  const encoder = new TextEncoder();
  const locals: Uint8Array[] = [];
  const centrals: Uint8Array[] = [];
  let offset = 0;

  for (const entry of entries) {
    const name = encoder.encode(entry.name);
    const crc = crc32(entry.data);
    const local = new Uint8Array(30 + name.length);
    const localView = new DataView(local.buffer);
    localView.setUint32(0, 0x04034b50, true);
    localView.setUint16(4, 20, true);
    // 0x0800：文件名按 UTF-8 解释，中文路径与 sheet 名才不会乱码。
    localView.setUint16(6, 0x0800, true);
    localView.setUint16(8, 0, true);
    localView.setUint32(10, 0, true);
    localView.setUint32(14, crc, true);
    localView.setUint32(18, entry.data.length, true);
    localView.setUint32(22, entry.data.length, true);
    localView.setUint16(26, name.length, true);
    localView.setUint16(28, 0, true);
    local.set(name, 30);

    const central = new Uint8Array(46 + name.length);
    const centralView = new DataView(central.buffer);
    centralView.setUint32(0, 0x02014b50, true);
    centralView.setUint16(4, 20, true);
    centralView.setUint16(6, 20, true);
    centralView.setUint16(8, 0x0800, true);
    centralView.setUint16(10, 0, true);
    centralView.setUint32(12, 0, true);
    centralView.setUint32(16, crc, true);
    centralView.setUint32(20, entry.data.length, true);
    centralView.setUint32(24, entry.data.length, true);
    centralView.setUint16(28, name.length, true);
    centralView.setUint32(42, offset, true);
    central.set(name, 46);

    locals.push(local, entry.data);
    centrals.push(central);
    offset += local.length + entry.data.length;
  }

  const centralSize = centrals.reduce((total, part) => total + part.length, 0);
  const end = new Uint8Array(22);
  const endView = new DataView(end.buffer);
  endView.setUint32(0, 0x06054b50, true);
  endView.setUint16(8, entries.length, true);
  endView.setUint16(10, entries.length, true);
  endView.setUint32(12, centralSize, true);
  endView.setUint32(16, offset, true);

  return concat([...locals, ...centrals, end]);
}

function concat(parts: Uint8Array[]): Uint8Array<ArrayBuffer> {
  const total = parts.reduce((size, part) => size + part.length, 0);
  const result = new Uint8Array(total);
  let position = 0;
  for (const part of parts) {
    result.set(part, position);
    position += part.length;
  }
  return result;
}

const CRC_TABLE = (() => {
  const table = new Uint32Array(256);
  for (let index = 0; index < 256; index += 1) {
    let value = index;
    for (let bit = 0; bit < 8; bit += 1) {
      value = value & 1 ? 0xedb88320 ^ (value >>> 1) : value >>> 1;
    }
    table[index] = value >>> 0;
  }
  return table;
})();

export function crc32(data: Uint8Array): number {
  let crc = 0xffffffff;
  for (let index = 0; index < data.length; index += 1) {
    crc = CRC_TABLE[(crc ^ data[index]) & 0xff] ^ (crc >>> 8);
  }
  return (crc ^ 0xffffffff) >>> 0;
}

const CONTENT_TYPES = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
  + '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
  + '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
  + '<Default Extension="xml" ContentType="application/xml"/>'
  + '<Override PartName="/xl/workbook.xml"'
  + ' ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>'
  + '<Override PartName="/xl/worksheets/sheet1.xml"'
  + ' ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>'
  + '</Types>';

const ROOT_RELS = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
  + '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
  + '<Relationship Id="rId1"'
  + ' Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument"'
  + ' Target="xl/workbook.xml"/></Relationships>';

const WORKBOOK_RELS = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
  + '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
  + '<Relationship Id="rId1"'
  + ' Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet"'
  + ' Target="worksheets/sheet1.xml"/></Relationships>';
