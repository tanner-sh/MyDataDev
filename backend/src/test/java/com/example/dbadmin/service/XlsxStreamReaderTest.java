package com.example.dbadmin.service;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class XlsxStreamReaderTest {
    @Test
    void readsSharedStringsAndInlineStringsAlike() throws Exception {
        byte[] file = workbook(Map.of(
                "xl/sharedStrings.xml", """
                        <sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                          <si><t>编号</t></si><si><t>名称</t></si><si><t>张三</t></si>
                        </sst>""",
                "xl/worksheets/sheet1.xml", """
                        <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>
                          <row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c></row>
                          <row r="2"><c r="A2"><v>1</v></c><c r="B2" t="s"><v>2</v></c></row>
                          <row r="3"><c r="A3"><v>2</v></c><c r="B3" t="inlineStr"><is><t>李四</t></is></c></row>
                        </sheetData></worksheet>"""));

        try (XlsxStreamReader reader = new XlsxStreamReader(new ByteArrayInputStream(file))) {
            assertThat(reader.readRow()).containsExactly("编号", "名称");
            assertThat(reader.readRow()).containsExactly("1", "张三");
            assertThat(reader.readRow()).containsExactly("2", "李四");
            assertThat(reader.readRow()).isNull();
        }
    }

    /**
     * 日期是这件事里唯一真正难的部分：文件里就是个数字，是不是日期只写在样式里。不解析样式的话，
     * 每个日期列都会变成一串五位数悄悄写进库。
     */
    @Test
    void restoresDatesFromTheStyleTableInsteadOfWritingSerialNumbers() throws Exception {
        byte[] file = workbook(Map.of(
                "xl/styles.xml", """
                        <styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                          <numFmts count="1"><numFmt numFmtId="176" formatCode="yyyy&quot;年&quot;m&quot;月&quot;"/></numFmts>
                          <cellXfs count="4">
                            <xf numFmtId="0"/><xf numFmtId="14"/><xf numFmtId="22"/><xf numFmtId="176"/>
                          </cellXfs>
                        </styleSheet>""",
                "xl/worksheets/sheet1.xml", """
                        <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>
                          <row r="1">
                            <c r="A1" s="0"><v>45900.5</v></c>
                            <c r="B1" s="1"><v>45900</v></c>
                            <c r="C1" s="2"><v>45900.5</v></c>
                            <c r="D1" s="3"><v>45900</v></c>
                          </row>
                        </sheetData></worksheet>"""));

        try (XlsxStreamReader reader = new XlsxStreamReader(new ByteArrayInputStream(file))) {
            // s=0 是常规格式：它就是个数字，不该被当成日期。
            assertThat(reader.readRow()).containsExactly(
                    "45900.5", "2025-08-31", "2025-08-31 12:00:00", "2025-08-31");
        }
    }

    /** 稀疏行：B 列有值而 A 列没有时，靠 r="B1" 才能落在正确的位置上。 */
    @Test
    void keepsSparseCellsInTheirColumns() throws Exception {
        byte[] file = workbook(Map.of("xl/worksheets/sheet1.xml", """
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>
                  <row r="1"><c r="B1" t="inlineStr"><is><t>只有第二列</t></is></c></row>
                </sheetData></worksheet>"""));

        try (XlsxStreamReader reader = new XlsxStreamReader(new ByteArrayInputStream(file))) {
            assertThat(reader.readRow()).containsExactly("", "只有第二列");
        }
    }

    /** 表格末尾常拖着一串格式化过但没有内容的行，当成数据行会插进一批全空记录。 */
    @Test
    void skipsRowsThatOnlyCarryFormatting() throws Exception {
        byte[] file = workbook(Map.of("xl/worksheets/sheet1.xml", """
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>
                  <row r="1"><c r="A1" t="inlineStr"><is><t>值</t></is></c></row>
                  <row r="2"><c r="A2" s="1"/><c r="B2" s="1"/></row>
                  <row r="3"><c r="A3" t="inlineStr"><is><t>尾</t></is></c></row>
                </sheetData></worksheet>"""));

        try (XlsxStreamReader reader = new XlsxStreamReader(new ByteArrayInputStream(file))) {
            assertThat(reader.readRow()).containsExactly("值");
            assertThat(reader.readRow()).containsExactly("尾");
            assertThat(reader.readRow()).isNull();
        }
    }

    /** 只要公式算好的结果，不要公式本身。 */
    @Test
    void takesTheComputedValueOfAFormulaCell() throws Exception {
        byte[] file = workbook(Map.of("xl/worksheets/sheet1.xml", """
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>
                  <row r="1"><c r="A1"><f>SUM(B1:C1)</f><v>7</v></c></row>
                </sheetData></worksheet>"""));

        try (XlsxStreamReader reader = new XlsxStreamReader(new ByteArrayInputStream(file))) {
            assertThat(reader.readRow()).containsExactly("7");
        }
    }

    /** 工作表位置以 workbook 的关系为准，不是所有写出来的文件都叫 sheet1.xml。 */
    @Test
    void followsTheWorkbookRelationshipToFindTheFirstSheet() throws Exception {
        Map<String, String> parts = new LinkedHashMap<>();
        parts.put("xl/workbook.xml", """
                <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
                          xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                  <sheets><sheet name="数据" sheetId="1" r:id="rId7"/></sheets>
                </workbook>""");
        parts.put("xl/_rels/workbook.xml.rels", """
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId7" Target="worksheets/renamed.xml"/>
                </Relationships>""");
        parts.put("xl/worksheets/renamed.xml", """
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>
                  <row r="1"><c r="A1" t="inlineStr"><is><t>找到了</t></is></c></row>
                </sheetData></worksheet>""");

        try (XlsxStreamReader reader = new XlsxStreamReader(new ByteArrayInputStream(zip(parts)))) {
            assertThat(reader.readRow()).containsExactly("找到了");
        }
    }

    /** xlsx 是用户上传的文件，解析发生在服务器上：DTD 一旦启用，一份表格就能读走本机文件。 */
    @Test
    void refusesDocumentTypeDefinitions() throws Exception {
        byte[] file = workbook(Map.of("xl/worksheets/sheet1.xml", """
                <!DOCTYPE worksheet [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>
                  <row r="1"><c r="A1" t="inlineStr"><is><t>&xxe;</t></is></c></row>
                </sheetData></worksheet>"""));

        assertThatThrownBy(() -> {
            try (XlsxStreamReader reader = new XlsxStreamReader(new ByteArrayInputStream(file))) {
                reader.readRow();
            }
        }).isInstanceOf(Exception.class);
    }

    @Test
    void rejectsSomethingThatIsNotAWorkbookAtAll() {
        assertThatThrownBy(() -> new XlsxStreamReader(
                new ByteArrayInputStream("这不是 xlsx".getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(Exception.class);
    }

    @Test
    void normalizesNumbersAndColumnReferences() {
        assertThat(XlsxStreamReader.normalizeNumber("1.0")).isEqualTo("1");
        assertThat(XlsxStreamReader.normalizeNumber("1.2300")).isEqualTo("1.23");
        assertThat(XlsxStreamReader.normalizeNumber("abc")).isEqualTo("abc");
        assertThat(XlsxStreamReader.columnIndex("A1", 9)).isZero();
        assertThat(XlsxStreamReader.columnIndex("AB12", 9)).isEqualTo(27);
        assertThat(XlsxStreamReader.columnIndex(null, 9)).isEqualTo(9);
    }

    /** 把 0.00 误判成日期会把金额悄悄变成 1970 年的某一天，比漏判偏门格式糟得多。 */
    @Test
    void readsFormatCodesConservatively() {
        assertThat(XlsxStreamReader.isDateFormatCode("yyyy-mm-dd")).isTrue();
        assertThat(XlsxStreamReader.isDateFormatCode("h:mm:ss")).isTrue();
        assertThat(XlsxStreamReader.isDateFormatCode("0.00")).isFalse();
        assertThat(XlsxStreamReader.isDateFormatCode("[Red]#,##0.00")).isFalse();
        assertThat(XlsxStreamReader.isDateFormatCode("\"元\"#,##0")).isFalse();
        assertThat(XlsxStreamReader.isDateFormatCode(null)).isFalse();
    }

    /** 1900-02-29 在现实里不存在，Excel 却认它 —— 序列号 60 之前的日期要往回挪一天。 */
    @Test
    void handlesTheLotusLeapYearBug() {
        assertThat(XlsxStreamReader.excelSerialToText("1")).isEqualTo("1900-01-01");
        assertThat(XlsxStreamReader.excelSerialToText("59")).isEqualTo("1900-02-28");
        assertThat(XlsxStreamReader.excelSerialToText("61")).isEqualTo("1900-03-01");
        assertThat(XlsxStreamReader.excelSerialToText("不是数字")).isNull();
    }

    private static byte[] workbook(Map<String, String> parts) throws Exception {
        Map<String, String> all = new LinkedHashMap<>(parts);
        all.putIfAbsent("xl/workbook.xml", """
                <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
                          xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                  <sheets><sheet name="Sheet1" sheetId="1" r:id="rId1"/></sheets>
                </workbook>""");
        return zip(all);
    }

    private static byte[] zip(Map<String, String> parts) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, String> part : parts.entrySet()) {
                zip.putNextEntry(new ZipEntry(part.getKey()));
                zip.write(part.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return buffer.toByteArray();
    }

    /** 用仓库自己的 XlsxWriter 写一份再读回来：两个方向对的是同一个 OOXML 子集。 */
    @Test
    void roundTripsWhatTheProductItselfWrites() throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (XlsxWriter writer = new XlsxWriter(buffer, "导出")) {
            writer.row(new Object[]{"ID", "名称"});
            writer.row(new Object[]{1, "张三"});
            writer.row(new Object[]{2, "李四"});
        }

        try (XlsxStreamReader reader = new XlsxStreamReader(new ByteArrayInputStream(buffer.toByteArray()))) {
            assertThat(reader.readRow()).containsExactly("ID", "名称");
            assertThat(reader.readRow()).containsExactly("1", "张三");
            assertThat(reader.readRow()).containsExactly("2", "李四");
            assertThat(reader.readRow()).isNull();
        }
    }
}
