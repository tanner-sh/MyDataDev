package com.example.dbadmin.service;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

class XlsxWriterTest {
    @Test
    void writesTheFivePartsExcelNeedsToOpenTheFile() throws Exception {
        Map<String, String> parts = write(workbook -> workbook.row(new Object[]{"id"}));

        assertThat(parts).containsKeys(
                "[Content_Types].xml", "_rels/.rels", "xl/workbook.xml",
                "xl/_rels/workbook.xml.rels", "xl/worksheets/sheet1.xml");
        assertThat(parts.get("xl/workbook.xml")).contains("<sheet name=\"查询结果\"");
        assertThat(parts.get("xl/worksheets/sheet1.xml"))
                .startsWith("<?xml version=\"1.0\"")
                .endsWith("</sheetData></worksheet>");
    }

    @Test
    void keepsTextAsTextAndNumbersAsNumbers() throws Exception {
        Map<String, String> parts = write(workbook -> workbook.row(new Object[]{
                "0012", 42, new BigDecimal("3.50"), true, null, "尾随空格 "
        }));
        String sheet = parts.get("xl/worksheets/sheet1.xml");

        // 前导零是选 xlsx 而不是 CSV 的直接理由：文本就得以文本写出。
        assertThat(sheet).contains("<c r=\"A1\" t=\"inlineStr\"><is><t xml:space=\"preserve\">0012</t></is></c>");
        assertThat(sheet).contains("<c r=\"B1\" t=\"n\"><v>42</v></c>");
        assertThat(sheet).contains("<c r=\"C1\" t=\"n\"><v>3.50</v></c>");
        assertThat(sheet).contains("<c r=\"D1\" t=\"b\"><v>1</v></c>");
        // 空单元格整个跳过，而不是写一个空的 <c/>。
        assertThat(sheet).doesNotContain("r=\"E1\"");
        assertThat(sheet).contains("xml:space=\"preserve\">尾随空格 <");
    }

    @Test
    void escapesMarkupAndDropsCharactersXmlForbids() throws Exception {
        Map<String, String> parts = write(workbook -> workbook.row(new Object[]{"<a & b>", "null\u0000byte"}));
        String sheet = parts.get("xl/worksheets/sheet1.xml");

        assertThat(sheet).contains("&lt;a &amp; b&gt;");
        // 控制字符原样写进去 Excel 会判定文件损坏，整份导出都打不开。数据库里存着这类
        // 字符并不罕见，尤其是被当成文本读出来的二进制。
        assertThat(sheet).contains("nullbyte").doesNotContain("\u0000");
    }

    @Test
    void numbersRowsAndColumnsTheWayExcelExpects() throws Exception {
        Map<String, String> parts = write(workbook -> {
            workbook.row(new Object[]{"a"});
            workbook.row(new Object[]{"b"});
        });

        assertThat(parts.get("xl/worksheets/sheet1.xml")).contains("<row r=\"1\">").contains("<row r=\"2\">");
        assertThat(XlsxWriter.reference(1, 1)).isEqualTo("A1");
        assertThat(XlsxWriter.reference(26, 3)).isEqualTo("Z3");
        assertThat(XlsxWriter.reference(27, 10)).isEqualTo("AA10");
        assertThat(XlsxWriter.reference(703, 1)).isEqualTo("AAA1");
    }

    @Test
    void truncatesCellsBeyondTheExcelLimit() throws Exception {
        String oversized = "x".repeat(XlsxWriter.MAX_CELL_CHARS + 100);
        Map<String, String> parts = write(workbook -> workbook.row(new Object[]{oversized}));

        String sheet = parts.get("xl/worksheets/sheet1.xml");
        int start = sheet.indexOf("preserve\">") + "preserve\">".length();
        assertThat(sheet.substring(start, sheet.indexOf("</t>", start))).hasSize(XlsxWriter.MAX_CELL_CHARS);
    }

    private interface Content {
        void write(XlsxWriter workbook) throws Exception;
    }

    private static Map<String, String> write(Content content) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (XlsxWriter workbook = new XlsxWriter(bytes, "查询结果")) {
            content.write(workbook);
        }
        Map<String, String> parts = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                parts.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return parts;
    }
}
