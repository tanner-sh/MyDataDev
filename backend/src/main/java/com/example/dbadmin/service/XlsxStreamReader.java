package com.example.dbadmin.service;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 把 .xlsx 的第一个工作表读成一行行字符串，供导入管线复用。
 *
 * <p>不引第三方库，与 {@link XlsxWriter} 同一条约定：那边只写 OOXML 的一个子集，这边只读
 * 需要的那几个部件。读比写简单 —— 除了共享字符串表和一件绕不开的事：<b>日期</b>。</p>
 *
 * <p>Excel 里的日期在 XML 里就是个数字（{@code 45678}），是不是日期只写在样式里。不解析
 * styles.xml 的话，每一个日期列都会变成一串五位数悄悄写进库 —— 而导入的表里有日期列几乎
 * 是必然。所以这里读 {@code cellXfs} 的 numFmtId，内置日期格式号加上自定义格式里出现
 * y/m/d/h 的，都按日期还原成 ISO 文本，再由数据库按目标列类型转换。</p>
 *
 * <p>两处内存约束要说清楚：共享字符串表整张读进内存（Excel 会去重，通常远小于表格本身），
 * 工作表本身是流式读的。上传的文件先落到临时文件再打开 —— ZIP 要随机访问，而共享字符串表
 * 在 ZIP 里的位置不保证排在工作表前面。</p>
 */
final class XlsxStreamReader implements ImportRowSource {
    /** 一行最多多少列。与导入的列数上限同源，超过说明这不是一份用来导入的表格。 */
    static final int MAX_COLUMNS = DataImportService.MAX_COLUMNS;
    /** 内置的日期/时间格式号，见 ECMA-376 的 numFmt 预置表。 */
    private static final Set<Integer> DATE_FORMATS =
            Set.of(14, 15, 16, 17, 18, 19, 20, 21, 22, 45, 46, 47);
    /** Excel 的 1900 日期系统里，序列号 1 是 1900-01-01；它还多认一个不存在的 1900-02-29。 */
    private static final LocalDate EPOCH = LocalDate.of(1899, 12, 30);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Path tempFile;
    private final ZipFile zip;
    private final List<String> sharedStrings;
    private final List<Boolean> dateStyles;
    private final XMLStreamReader sheet;
    private final InputStream sheetStream;

    XlsxStreamReader(InputStream input) throws Exception {
        tempFile = Files.createTempFile("mydatadev-import-", ".xlsx");
        try {
            Files.copy(input, tempFile, StandardCopyOption.REPLACE_EXISTING);
            zip = new ZipFile(tempFile.toFile());
        } catch (Exception error) {
            Files.deleteIfExists(tempFile);
            throw error;
        }
        try {
            sharedStrings = readSharedStrings();
            dateStyles = readDateStyles();
            String sheetPath = firstSheetPath();
            ZipEntry entry = zip.getEntry(sheetPath);
            if (entry == null) throw new IllegalArgumentException("Excel 文件里找不到工作表：" + sheetPath);
            sheetStream = zip.getInputStream(entry);
            sheet = factory().createXMLStreamReader(sheetStream);
        } catch (Exception error) {
            closeQuietly();
            throw error;
        }
    }

    @Override
    public String label() {
        return "Excel";
    }

    /**
     * 读下一行；读完返回 {@code null}。
     *
     * <p>空行会被跳过而不是返回空列表：表格末尾常拖着一串被格式化过、但没有内容的行，
     * 把它们当成数据行会在导入时插进一批全空记录。</p>
     */
    @Override
    public List<String> readRow() throws XMLStreamException {
        while (sheet.hasNext()) {
            int event = sheet.next();
            if (event != XMLStreamConstants.START_ELEMENT || !"row".equals(sheet.getLocalName())) continue;
            List<String> row = readRowCells();
            if (row.stream().anyMatch(value -> value != null && !value.isEmpty())) return row;
        }
        return null;
    }

    private List<String> readRowCells() throws XMLStreamException {
        List<String> row = new ArrayList<>();
        while (sheet.hasNext()) {
            int event = sheet.next();
            if (event == XMLStreamConstants.END_ELEMENT && "row".equals(sheet.getLocalName())) break;
            if (event != XMLStreamConstants.START_ELEMENT || !"c".equals(sheet.getLocalName())) continue;
            int column = columnIndex(sheet.getAttributeValue(null, "r"), row.size());
            String type = sheet.getAttributeValue(null, "t");
            String styleIndex = sheet.getAttributeValue(null, "s");
            String value = readCellValue(type);
            // 稀疏表格：B 列有值而 A 列没有时，r="B1" 会把它落在正确的位置上。
            while (row.size() < column) row.add("");
            if (row.size() > MAX_COLUMNS) throw new IllegalArgumentException("Excel 列数超过 " + MAX_COLUMNS + "。");
            row.add(format(value, type, styleIndex));
        }
        return row;
    }

    private String readCellValue(String type) throws XMLStreamException {
        StringBuilder text = new StringBuilder();
        boolean capture = false;
        while (sheet.hasNext()) {
            int event = sheet.next();
            if (event == XMLStreamConstants.END_ELEMENT && "c".equals(sheet.getLocalName())) break;
            if (event == XMLStreamConstants.START_ELEMENT) {
                String name = sheet.getLocalName();
                // v 是普通取值，t 是 inlineStr 的文本；f（公式）一律跳过，只要算好的结果。
                capture = "v".equals(name) || "t".equals(name);
                if ("f".equals(name)) skipElement();
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                capture = false;
            } else if (capture && (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA)) {
                text.append(sheet.getText());
            }
        }
        return text.toString();
    }

    private void skipElement() throws XMLStreamException {
        int depth = 1;
        while (depth > 0 && sheet.hasNext()) {
            int event = sheet.next();
            if (event == XMLStreamConstants.START_ELEMENT) depth++;
            else if (event == XMLStreamConstants.END_ELEMENT) depth--;
        }
    }

    /** 按类型和样式把单元格还原成文本。 */
    private String format(String raw, String type, String styleIndex) {
        if (raw == null || raw.isEmpty()) return "";
        if ("s".equals(type)) {
            int index = parseInt(raw, -1);
            return index >= 0 && index < sharedStrings.size() ? sharedStrings.get(index) : "";
        }
        if ("b".equals(type)) return "1".equals(raw) ? "TRUE" : "FALSE";
        // inlineStr / str（公式结果）/ e（错误）都已经是文本了。
        if (type != null && !type.isBlank() && !"n".equals(type)) return raw;
        if (isDateStyle(styleIndex)) {
            String date = excelSerialToText(raw);
            if (date != null) return date;
        }
        return normalizeNumber(raw);
    }

    private boolean isDateStyle(String styleIndex) {
        int index = parseInt(styleIndex, -1);
        return index >= 0 && index < dateStyles.size() && dateStyles.get(index);
    }

    /**
     * 序列号转日期文本。
     *
     * <p>整数部分是天，小数部分是当天的时间。没有小数就只写日期 —— 给一个 DATE 列送
     * {@code 2026-09-05 00:00:00} 在一些库上会被拒绝，反过来给 TIMESTAMP 列送日期则没问题。</p>
     */
    static String excelSerialToText(String raw) {
        double serial;
        try {
            serial = Double.parseDouble(raw);
        } catch (NumberFormatException ignored) {
            return null;
        }
        if (serial < 1 || serial > 2_958_465) return null;
        long days = (long) Math.floor(serial);
        // Excel 沿用了 Lotus 的 1900-02-29 —— 那一天不存在，60 之后的序列号都要往回挪一天。
        LocalDate date = EPOCH.plusDays(days <= 59 ? days + 1 : days);
        long secondsOfDay = Math.round((serial - days) * 86_400);
        if (secondsOfDay <= 0) return date.format(DATE);
        if (secondsOfDay >= 86_400) return date.plusDays(1).format(DATE);
        return LocalDateTime.of(date, java.time.LocalTime.ofSecondOfDay(secondsOfDay)).format(DATE_TIME);
    }

    /** {@code 1.0} 这样的浮点写法在整数列上会被拒绝，能还原成整数就还原。 */
    static String normalizeNumber(String raw) {
        try {
            BigDecimal value = new BigDecimal(raw);
            return value.stripTrailingZeros().toPlainString();
        } catch (NumberFormatException ignored) {
            return raw;
        }
    }

    /** {@code r="AB12"} 里的列号，从 0 开始；解析不出来时退回当前位置。 */
    static int columnIndex(String reference, int fallback) {
        if (reference == null || reference.isEmpty()) return fallback;
        int column = 0;
        boolean seen = false;
        for (int index = 0; index < reference.length(); index++) {
            char ch = Character.toUpperCase(reference.charAt(index));
            if (ch < 'A' || ch > 'Z') break;
            column = column * 26 + (ch - 'A' + 1);
            seen = true;
        }
        return seen ? column - 1 : fallback;
    }

    private List<String> readSharedStrings() throws Exception {
        List<String> strings = new ArrayList<>();
        ZipEntry entry = zip.getEntry("xl/sharedStrings.xml");
        if (entry == null) return strings;
        try (InputStream stream = zip.getInputStream(entry)) {
            XMLStreamReader reader = factory().createXMLStreamReader(stream);
            StringBuilder current = null;
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    if ("si".equals(reader.getLocalName())) current = new StringBuilder();
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    if ("si".equals(reader.getLocalName()) && current != null) {
                        strings.add(current.toString());
                        current = null;
                    }
                } else if (current != null
                        && (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA)) {
                    // 富文本会把一个字符串拆成多个 <r><t>，拼起来就是它显示出来的样子。
                    current.append(reader.getText());
                }
            }
            reader.close();
        }
        return strings;
    }

    /** cellXfs 里每个样式是不是日期格式，下标就是单元格的 {@code s} 属性。 */
    private List<Boolean> readDateStyles() throws Exception {
        List<Boolean> styles = new ArrayList<>();
        ZipEntry entry = zip.getEntry("xl/styles.xml");
        if (entry == null) return styles;
        Map<Integer, String> customFormats = new HashMap<>();
        try (InputStream stream = zip.getInputStream(entry)) {
            XMLStreamReader reader = factory().createXMLStreamReader(stream);
            boolean inCellXfs = false;
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String name = reader.getLocalName();
                    if ("numFmt".equals(name)) {
                        customFormats.put(parseInt(reader.getAttributeValue(null, "numFmtId"), -1),
                                reader.getAttributeValue(null, "formatCode"));
                    } else if ("cellXfs".equals(name)) {
                        inCellXfs = true;
                    } else if (inCellXfs && "xf".equals(name)) {
                        int formatId = parseInt(reader.getAttributeValue(null, "numFmtId"), 0);
                        styles.add(DATE_FORMATS.contains(formatId)
                                || isDateFormatCode(customFormats.get(formatId)));
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT && "cellXfs".equals(reader.getLocalName())) {
                    inCellXfs = false;
                }
            }
            reader.close();
        }
        return styles;
    }

    /**
     * 自定义格式码是不是日期。
     *
     * <p>只看方括号外的 y/m/d/h/s：{@code [Red]0.00} 里的红色标记、{@code "月"} 这类字面量
     * 里的字母都不算格式符。宁可漏判几个偏门格式（导入时就是一串数字，用户看得见），
     * 也不要把 {@code 0.00} 误判成日期 —— 那会把金额悄悄变成 1970 年的某一天。</p>
     */
    static boolean isDateFormatCode(String code) {
        if (code == null || code.isBlank()) return false;
        boolean inBracket = false;
        boolean inQuote = false;
        for (int index = 0; index < code.length(); index++) {
            char ch = code.charAt(index);
            if (ch == '"') inQuote = !inQuote;
            else if (ch == '[') inBracket = true;
            else if (ch == ']') inBracket = false;
            else if (!inBracket && !inQuote) {
                char lower = Character.toLowerCase(ch);
                if (lower == 'y' || lower == 'd' || lower == 'h') return true;
                // m 在 Excel 里既是月也是分钟，两种都说明这是日期或时间格式。
                if (lower == 'm') return true;
            }
        }
        return false;
    }

    /** 工作簿里第一个工作表的部件路径。 */
    private String firstSheetPath() throws Exception {
        String relationId = null;
        ZipEntry workbook = zip.getEntry("xl/workbook.xml");
        if (workbook != null) {
            try (InputStream stream = zip.getInputStream(workbook)) {
                XMLStreamReader reader = factory().createXMLStreamReader(stream);
                while (reader.hasNext() && relationId == null) {
                    if (reader.next() == XMLStreamConstants.START_ELEMENT && "sheet".equals(reader.getLocalName())) {
                        relationId = reader.getAttributeValue(
                                "http://schemas.openxmlformats.org/officeDocument/2006/relationships", "id");
                        if (relationId == null) relationId = reader.getAttributeValue(null, "id");
                    }
                }
                reader.close();
            }
        }
        String target = relationId == null ? null : resolveRelation(relationId);
        if (target != null) return target.startsWith("/") ? target.substring(1) : "xl/" + target;
        // 关系解析不出来时退回约定俗成的位置：绝大多数写出来的文件就在这儿。
        return "xl/worksheets/sheet1.xml";
    }

    private String resolveRelation(String relationId) throws Exception {
        ZipEntry rels = zip.getEntry("xl/_rels/workbook.xml.rels");
        if (rels == null) return null;
        try (InputStream stream = zip.getInputStream(rels)) {
            XMLStreamReader reader = factory().createXMLStreamReader(stream);
            while (reader.hasNext()) {
                if (reader.next() == XMLStreamConstants.START_ELEMENT
                        && "Relationship".equals(reader.getLocalName())
                        && relationId.equals(reader.getAttributeValue(null, "Id"))) {
                    String target = reader.getAttributeValue(null, "Target");
                    reader.close();
                    return target;
                }
            }
            reader.close();
        }
        return null;
    }

    /**
     * XML 解析器一律关掉外部实体与 DTD。
     *
     * <p>xlsx 是用户上传的文件，而这里的解析发生在服务器上：留着 DTD 支持，一份精心构造的
     * 表格就能读走本机文件或者把内存撑爆（XXE 与实体展开炸弹）。</p>
     */
    private static XMLInputFactory factory() {
        XMLInputFactory factory = XMLInputFactory.newInstance();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.IS_COALESCING, true);
        return factory;
    }

    private static int parseInt(String value, int fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    @Override
    public void close() {
        closeQuietly();
    }

    private void closeQuietly() {
        try {
            if (sheet != null) sheet.close();
        } catch (XMLStreamException ignored) {
            // 关闭失败不影响导入结果，下面的临时文件删除更重要。
        }
        try {
            if (sheetStream != null) sheetStream.close();
        } catch (IOException ignored) {
            // 同上。
        }
        try {
            if (zip != null) zip.close();
        } catch (IOException ignored) {
            // 同上。
        }
        try {
            Files.deleteIfExists(tempFile);
        } catch (IOException ignored) {
            // 临时文件删不掉时留给操作系统清理，不值得让一次成功的导入失败。
        }
    }

}
