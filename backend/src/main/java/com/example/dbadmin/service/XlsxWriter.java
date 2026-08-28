package com.example.dbadmin.service;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 流式写出一个最小可用的 .xlsx。
 *
 * <p>为什么不引 Apache POI：导出只需要「一个工作表、一行表头、若干行数据」，而 POI 会给
 * 桌面安装包和 Web 发行 JAR 增加十几兆。这里手写的是 OOXML 里最小的那个子集 —— 五个 XML
 * 部件，字符串用 inline string（省掉 sharedStrings.xml），不带 styles.xml。</p>
 *
 * <p>相比 CSV，xlsx 的意义在于类型不会被 Excel 重新猜一遍：长订单号不会变成科学计数法，
 * 前导零不会被吃掉，因为文本就是以文本写进去的。代价是日期也按文本原样写出（没有
 * styles.xml 就没有日期格式），这反而更安全 —— 序列号加时区的还原是另一类错误的源头。</p>
 */
final class XlsxWriter implements AutoCloseable {
    /** Excel 单个工作表的行上限，导出上限远低于它，这里只作为兜底断言。 */
    static final int MAX_ROWS = 1_048_576;
    /** Excel 单元格文本上限。 */
    static final int MAX_CELL_CHARS = 32_767;

    private final ZipOutputStream zip;
    private final Writer sheet;
    private final String sheetName;
    private int rowNumber;
    private boolean sheetClosed;

    XlsxWriter(OutputStream output, String sheetName) throws IOException {
        this.zip = new ZipOutputStream(output, StandardCharsets.UTF_8);
        this.sheetName = sanitizeSheetName(sheetName);
        writeEntry("[Content_Types].xml", CONTENT_TYPES);
        writeEntry("_rels/.rels", ROOT_RELS);
        writeEntry("xl/workbook.xml", workbook());
        writeEntry("xl/_rels/workbook.xml.rels", WORKBOOK_RELS);
        zip.putNextEntry(new ZipEntry("xl/worksheets/sheet1.xml"));
        this.sheet = new BufferedWriter(new OutputStreamWriter(zip, StandardCharsets.UTF_8), 64 * 1024);
        sheet.write("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>");
        sheet.write("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>");
    }

    /**
     * 写一行。{@code null} 元素落成空单元格；数字与布尔按类型写，其余一律按文本写。
     */
    void row(Object[] values) throws IOException {
        if (rowNumber >= MAX_ROWS) throw new IllegalStateException("导出行数超过 Excel 单表上限。");
        rowNumber++;
        sheet.write("<row r=\"" + rowNumber + "\">");
        for (int index = 0; index < values.length; index++) {
            writeCell(reference(index + 1, rowNumber), values[index]);
        }
        sheet.write("</row>");
    }

    private void writeCell(String reference, Object value) throws IOException {
        if (value == null) return;
        if (value instanceof Boolean bool) {
            sheet.write("<c r=\"" + reference + "\" t=\"b\"><v>" + (bool ? 1 : 0) + "</v></c>");
            return;
        }
        if (isFiniteNumber(value)) {
            sheet.write("<c r=\"" + reference + "\" t=\"n\"><v>" + value + "</v></c>");
            return;
        }
        String text = value.toString();
        if (text.length() > MAX_CELL_CHARS) text = text.substring(0, MAX_CELL_CHARS);
        sheet.write("<c r=\"" + reference + "\" t=\"inlineStr\"><is><t xml:space=\"preserve\">");
        sheet.write(escape(text));
        sheet.write("</t></is></c>");
    }

    /**
     * 只有能被 Excel 原样读回的数值才按数字写。
     *
     * <p>NaN 与无穷在 OOXML 里没有合法写法，浮点数直接 toString 也可能带上 {@code E} 记法
     * 之外的形式；这两类退回文本，宁可丢掉「可参与计算」也不要生成一个 Excel 打不开的文件。</p>
     */
    private boolean isFiniteNumber(Object value) {
        if (value instanceof Integer || value instanceof Long || value instanceof Short
                || value instanceof Byte || value instanceof BigInteger || value instanceof BigDecimal) {
            return true;
        }
        if (value instanceof Double number) return !number.isNaN() && !number.isInfinite();
        if (value instanceof Float number) return !number.isNaN() && !number.isInfinite();
        return false;
    }

    @Override
    public void close() throws IOException {
        if (!sheetClosed) {
            sheet.write("</sheetData></worksheet>");
            sheet.flush();
            sheetClosed = true;
            zip.closeEntry();
        }
        // finish 而不是 close：底层输出流由调用方管理（导出链路上它还套着大小限制流）。
        zip.finish();
    }

    private void writeEntry(String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private String workbook() {
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"\
                 xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">\
                <sheets><sheet name="%s" sheetId="1" r:id="rId1"/></sheets></workbook>
                """.formatted(escape(sheetName));
    }

    /** 工作表名不能超过 31 个字符，也不能含 Excel 保留的那几个符号。 */
    private static String sanitizeSheetName(String name) {
        String value = name == null || name.isBlank() ? "Sheet1" : name.trim();
        value = value.replaceAll("[\\\\/*?\\[\\]:]", "_");
        return value.length() > 31 ? value.substring(0, 31) : value;
    }

    /** 1 -> A、26 -> Z、27 -> AA。 */
    static String reference(int column, int row) {
        StringBuilder letters = new StringBuilder();
        int remaining = column;
        while (remaining > 0) {
            int digit = (remaining - 1) % 26;
            letters.insert(0, (char) ('A' + digit));
            remaining = (remaining - 1) / 26;
        }
        return letters + Integer.toString(row);
    }

    /**
     * XML 转义，外加剔除 XML 1.0 不允许的控制字符。
     *
     * <p>数据库里存着 {@code \\u0000} 这类字符并不罕见（尤其是被当成文本读出来的二进制），
     * 原样写进去 Excel 会直接判定文件损坏、整份导出打不开。</p>
     *
     * <p>按码点遍历而不是按 {@code char}：emoji、扩展 B 区汉字这些补充平面字符在 Java 里
     * 是一对代理项，逐 {@code char} 判断时两半都落在 {@code 0xD800-0xDFFF}，会被合法性
     * 检查当成非法字符各删一次 —— 「订单😀𠀀」曾因此导出成「订单」。</p>
     */
    static String escape(String value) {
        StringBuilder result = new StringBuilder(value.length() + 16);
        int index = 0;
        while (index < value.length()) {
            int codePoint = value.codePointAt(index);
            index += Character.charCount(codePoint);
            switch (codePoint) {
                case '<' -> result.append("&lt;");
                case '>' -> result.append("&gt;");
                case '&' -> result.append("&amp;");
                case '"' -> result.append("&quot;");
                case '\'' -> result.append("&apos;");
                default -> {
                    if (isAllowedXmlChar(codePoint)) result.appendCodePoint(codePoint);
                }
            }
        }
        return result.toString();
    }

    /** XML 1.0 的 Char 产生式。补充平面（{@code 0x10000} 起）整段合法，孤立代理项不是码点，落不到这里。 */
    private static boolean isAllowedXmlChar(int value) {
        return value == '\t' || value == '\n' || value == '\r'
                || value >= 0x20 && value <= 0xD7FF
                || value >= 0xE000 && value <= 0xFFFD
                || value >= 0x10000 && value <= 0x10FFFF;
    }

    private static final String CONTENT_TYPES = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">\
            <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>\
            <Default Extension="xml" ContentType="application/xml"/>\
            <Override PartName="/xl/workbook.xml"\
             ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>\
            <Override PartName="/xl/worksheets/sheet1.xml"\
             ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>\
            </Types>
            """;

    private static final String ROOT_RELS = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">\
            <Relationship Id="rId1"\
             Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument"\
             Target="xl/workbook.xml"/></Relationships>
            """;

    private static final String WORKBOOK_RELS = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">\
            <Relationship Id="rId1"\
             Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet"\
             Target="worksheets/sheet1.xml"/></Relationships>
            """;
}
