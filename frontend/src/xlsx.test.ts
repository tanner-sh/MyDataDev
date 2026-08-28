import { describe, expect, it } from 'vitest';
import { buildXlsx, columnReference, crc32, escapeXml, sheetXml, xlsxCell, zipStore } from './xlsx';
import type { ResultColumn } from './types';

const columns: ResultColumn[] = [
  { key: 'c0', label: 'code', typeName: 'varchar' },
  { key: 'c1', label: 'amount', typeName: 'decimal' },
  { key: 'c2', label: 'active', typeName: 'boolean' }
];

describe('xlsx', () => {
  it('数值列里看起来是数值的才按数字写，文本列一律按文本', () => {
    expect(xlsxCell('0012', 'varchar')).toEqual({ kind: 'text', value: '0012' });
    expect(xlsxCell('3.50', 'decimal')).toEqual({ kind: 'number', value: '3.50' });
    // 数值列里放着非数值内容（比如 NULL 占位符）时不能硬转。
    expect(xlsxCell('N/A', 'decimal')).toEqual({ kind: 'text', value: 'N/A' });
    expect(xlsxCell(42, 'int')).toEqual({ kind: 'number', value: '42' });
    expect(xlsxCell(true, 'boolean')).toEqual({ kind: 'boolean', value: true });
    expect(xlsxCell(null, 'varchar')).toEqual({ kind: 'empty' });
    expect(xlsxCell(undefined, 'varchar')).toEqual({ kind: 'empty' });
  });

  it('超过 Excel 精度的整数按文本写，避免末尾被静默改成 0', () => {
    // 19 位的雪花 ID 是最典型的例子。
    expect(xlsxCell('7213954812345678901', 'bigint')).toEqual({ kind: 'text', value: '7213954812345678901' });
    expect(xlsxCell('123456789012345', 'bigint')).toEqual({ kind: 'number', value: '123456789012345' });
    expect(xlsxCell(BigInt('7213954812345678901'), 'bigint').kind).toBe('text');
    expect(xlsxCell(Number.POSITIVE_INFINITY, 'double')).toEqual({ kind: 'text', value: 'Infinity' });
  });

  it('转义 XML 并剔除非法控制字符', () => {
    expect(escapeXml('<a & b>')).toBe('&lt;a &amp; b&gt;');
    expect(escapeXml('null\u0000byte\u0007')).toBe('nullbyte');
    expect(escapeXml('保留\t换行\n')).toBe('保留\t换行\n');
    // 补充平面字符要原样留下：后端曾按 UTF-16 code unit 过滤，把 emoji 的两半各删一次。
    expect(escapeXml('订单\u{1F600}\u{20000}')).toBe('订单\u{1F600}\u{20000}');
    // 成不了对的代理项不是合法码点，写进去 Excel 会判定文件损坏。
    expect(escapeXml('孤立\uD800代理')).toBe('孤立代理');
  });

  it('列号按 Excel 的字母进位', () => {
    expect(columnReference(1)).toBe('A');
    expect(columnReference(26)).toBe('Z');
    expect(columnReference(27)).toBe('AA');
    expect(columnReference(703)).toBe('AAA');
  });

  it('表头占第一行，数据从第二行开始', () => {
    const xml = sheetXml(columns, [['0012', '3.50', true]]);
    expect(xml).toContain('<row r="1">');
    expect(xml).toContain('preserve">code</t>');
    expect(xml).toContain('<row r="2">');
    expect(xml).toContain('<c r="A2" t="inlineStr"><is><t xml:space="preserve">0012</t></is></c>');
    expect(xml).toContain('<c r="B2" t="n"><v>3.50</v></c>');
    expect(xml).toContain('<c r="C2" t="b"><v>1</v></c>');
    expect(xml.endsWith('</sheetData></worksheet>')).toBe(true);
  });

  it('空单元格整个跳过而不是写一个空的 c 元素', () => {
    const xml = sheetXml(columns, [['a', null, null]]);
    expect(xml).toContain('r="A2"');
    expect(xml).not.toContain('r="B2"');
    expect(xml).not.toContain('r="C2"');
  });

  it('CRC32 与标准实现一致', () => {
    // 标准测试向量：CRC32("123456789") = 0xCBF43926
    expect(crc32(new TextEncoder().encode('123456789'))).toBe(0xcbf43926);
    expect(crc32(new Uint8Array(0))).toBe(0);
  });

  it('ZIP 结构可被解析：签名、条目数与中央目录偏移', () => {
    const data = new TextEncoder().encode('hello');
    const zip = zipStore([{ name: 'a.txt', data }]);
    const view = new DataView(zip.buffer, zip.byteOffset, zip.byteLength);

    expect(view.getUint32(0, true)).toBe(0x04034b50);
    const end = zip.length - 22;
    expect(view.getUint32(end, true)).toBe(0x06054b50);
    expect(view.getUint16(end + 8, true)).toBe(1);
    const centralOffset = view.getUint32(end + 16, true);
    expect(view.getUint32(centralOffset, true)).toBe(0x02014b50);
    // STORED：压缩后与压缩前长度一致。
    expect(view.getUint32(18, true)).toBe(data.length);
    expect(view.getUint32(22, true)).toBe(data.length);
  });

  it('生成的工作簿包含 Excel 需要的五个部件', async () => {
    const blob = buildXlsx(columns, [['0012', '1', false]]);
    expect(blob.type).toContain('spreadsheetml.sheet');
    const text = new TextDecoder().decode(new Uint8Array(await blob.arrayBuffer()));
    for (const part of ['[Content_Types].xml', '_rels/.rels', 'xl/workbook.xml', 'xl/_rels/workbook.xml.rels', 'xl/worksheets/sheet1.xml']) {
      expect(text).toContain(part);
    }
  });
});
