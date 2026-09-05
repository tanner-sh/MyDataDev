package com.example.dbadmin.service;

import com.example.dbadmin.config.AppProperties;
import com.example.dbadmin.api.ApiProblemException;
import com.example.dbadmin.core.DatabaseDialect;
import com.example.dbadmin.core.DialectRegistry;
import com.example.dbadmin.model.DbConnection;
import com.example.dbadmin.repo.AuditRepository;
import com.example.dbadmin.repo.SqlHistoryRepository;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.lang.ref.Cleaner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLXML;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.math.BigDecimal;
import java.math.BigInteger;

@Service
public class ExportService {
    private static final Cleaner TEMP_FILE_CLEANER = Cleaner.create();
    public static final int EXPORT_MAX_ROWS = 10_000;
    public static final long EXPORT_MAX_BYTES = 256L * 1024 * 1024;
    private static final int EXPORT_MAX_CELL_TEXT_CHARS = 100_000;
    private static final int SQL_EXPORT_MAX_CELL_BYTES = 64 * 1024 * 1024;
    private static final int SQL_EXPORT_MAX_CELL_CHARS = 64 * 1024 * 1024;
    private final ConnectionService connections;
    private final DialectRegistry dialectRegistry;
    private final AppProperties properties;
    private final ObjectMapper mapper;
    private final SqlStatementClassifier classifier;
    private final SqlScriptSplitter splitter;
    private final AuditRepository audit;
    private final SqlHistoryRepository history;
    private final ExecutionGuard executionGuard;

    public ExportService(
            ConnectionService connections,
            DialectRegistry dialectRegistry,
            AppProperties properties,
            ObjectMapper mapper,
            SqlStatementClassifier classifier,
            SqlScriptSplitter splitter,
            AuditRepository audit,
            SqlHistoryRepository history,
            ExecutionGuard executionGuard
    ) {
        this.connections = connections;
        this.dialectRegistry = dialectRegistry;
        this.properties = properties;
        this.mapper = mapper;
        this.classifier = classifier;
        this.splitter = splitter;
        this.audit = audit;
        this.history = history;
        this.executionGuard = executionGuard;
    }

    public void export(long connectionId, String sql, String format, String actor, OutputStream output) throws Exception {
        stream(connectionId, sql, format, actor, null, null, null, output);
    }

    /**
     * Checks the request and returns the single statement it will run.
     *
     * <p>The controller calls this before opening the response stream so a
     * rejection still becomes an HTTP status rather than a truncated download;
     * {@link #stream} calls it again because it is a public entry point in its
     * own right, and reuses the returned statement instead of splitting the SQL
     * a second time.</p>
     */
    public String validate(long connectionId, String sql, String format, String productionConfirmation) {
        String normalizedFormat = normalizeFormat(format);
        var statements = splitter.split(sql);
        if (statements.size() != 1 || !classifier.isQuery(statements.get(0).sql())) {
            throw new IllegalArgumentException("导出仅支持单条查询语句，不会执行写入或 DDL。");
        }
        DbConnection dbConnection = connections.require(connectionId);
        executionGuard.requireQueryAllowed(dbConnection, classifier.classify(statements.get(0).sql()), productionConfirmation);
        if (normalizedFormat.isBlank()) throw new IllegalArgumentException("导出格式不能为空。");
        return statements.get(0).sql();
    }

    public void stream(long connectionId, String sql, String format, String actor,
                       String productionConfirmation, OutputStream rawOutput) throws Exception {
        stream(connectionId, sql, format, actor, productionConfirmation, null, null, rawOutput);
    }

    public void stream(long connectionId, String sql, String format, String actor,
                       String productionConfirmation, String schemaName, OutputStream rawOutput) throws Exception {
        stream(connectionId, sql, format, actor, productionConfirmation, schemaName, null, rawOutput);
    }

    public void stream(long connectionId, String sql, String format, String actor,
                       String productionConfirmation, String schemaName, List<String> targetTableParts, OutputStream rawOutput) throws Exception {
        String statementSql = validate(connectionId, sql, format, productionConfirmation);
        String normalizedFormat = normalizeFormat(format);
        List<String> normalizedTarget = normalizeTargetTableParts(targetTableParts, normalizedFormat);
        DbConnection dbConnection = connections.require(connectionId);
        DatabaseDialect dialect = dialectRegistry.dialectFor(dbConnection);
        long started = System.nanoTime();
        try (Connection connection = openConnection(connectionId, schemaName);
             ReadOnlyQueryScope ignored = ReadOnlyQueryScope.begin(connection, true);
             Statement statement = connection.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
            dialect.configureStreamingStatement(connection, statement, 500, properties.getSql().getTimeoutSeconds());
            statement.setMaxRows(EXPORT_MAX_ROWS + 1);
            try (ResultSet rs = statement.executeQuery(statementSql)) {
                write(rs, normalizedFormat, new SizeLimitedOutputStream(rawOutput, EXPORT_MAX_BYTES), dialect, normalizedTarget);
            }
            rawOutput.flush();
            long elapsed = (System.nanoTime() - started) / 1_000_000;
            audit.onConnection(actor, "SQL_EXPORT", connectionId, abbreviate(sql));
            history.insert(connectionId, sql, "EXPORT_" + normalizedFormat.toUpperCase(Locale.ROOT), "SUCCESS", elapsed, null, actor);
        } catch (Exception error) {
            long elapsed = (System.nanoTime() - started) / 1_000_000;
            history.insert(connectionId, sql, "EXPORT_" + normalizedFormat.toUpperCase(Locale.ROOT), "FAILED", elapsed, abbreviate(error.getMessage()), actor);
            throw error;
        }
    }

    public PreparedExport prepare(long connectionId, String sql, String format, String actor, String productionConfirmation) throws Exception {
        return prepare(connectionId, sql, format, actor, productionConfirmation, null);
    }

    public PreparedExport prepare(long connectionId, String sql, String format, String actor, String productionConfirmation, String schemaName) throws Exception {
        return prepare(connectionId, sql, format, actor, productionConfirmation, schemaName, null);
    }

    public PreparedExport prepare(long connectionId, String sql, String format, String actor, String productionConfirmation, String schemaName, List<String> targetTableParts) throws Exception {
        return prepareInternal(connectionId, requireSingleQuery(sql), null, format, actor, productionConfirmation,
                schemaName, targetTableParts, abbreviate(sql));
    }

    /**
     * 「导出的 SQL 必须是单条查询」这条规则的唯一定义，返回规范化后的那条语句。
     *
     * <p>定时导出在**保存任务时**就调它一次：一条写操作留到半夜由调度线程发现，代价是白等
     * 一晚上加一条谁也没看见的失败记录。</p>
     */
    public String requireSingleQuery(String sql) {
        var statements = splitter.split(sql);
        if (statements.size() != 1 || !classifier.isQuery(statements.get(0).sql())) {
            throw new IllegalArgumentException("导出仅支持单条查询语句，不会执行写入或 DDL。");
        }
        return statements.get(0).sql();
    }

    /**
     * 导出一条由产品自己生成的查询（目前是表数据导出）。
     *
     * <p>与上面那道门的区别只有两处：SQL 不是用户写的，所以不做「单条查询」那道文本校验；
     * 参数由调用方绑定，因为构造它的地方才知道每个值的 JDBC 类型。生产确认、只读作用域、
     * 行数与字节上限、审计与历史全部照旧 —— 导出是把数据带出系统，这几道不能因为 SQL 是
     * 我们自己拼的就省掉。</p>
     */
    public PreparedExport prepareGenerated(
            long connectionId,
            String sql,
            DataEditService.StatementBinder binder,
            String format,
            String actor,
            String productionConfirmation,
            String schemaName,
            String auditDetail
    ) throws Exception {
        return prepareInternal(connectionId, sql, binder, format, actor, productionConfirmation,
                schemaName, null, auditDetail);
    }

    private PreparedExport prepareInternal(
            long connectionId,
            String sql,
            DataEditService.StatementBinder binder,
            String format,
            String actor,
            String productionConfirmation,
            String schemaName,
            List<String> targetTableParts,
            String auditDetail
    ) throws Exception {
        String normalizedFormat = normalizeFormat(format);
        List<String> normalizedTarget = normalizeTargetTableParts(targetTableParts, normalizedFormat);
        DbConnection dbConnection = connections.require(connectionId);
        executionGuard.requireQueryAllowed(dbConnection, SqlStatementClassifier.Kind.QUERY, productionConfirmation);
        DatabaseDialect dialect = dialectRegistry.dialectFor(dbConnection);
        long started = System.nanoTime();
        Path file = Files.createTempFile("dbadmin-export-", "." + normalizedFormat);
        try (Connection connection = openConnection(connectionId, schemaName);
             // Export is contractually read-only even when the saved
             // connection itself is writable. Roll back SELECT routines with
             // transactional side effects and apply the JDBC read-only hint.
             ReadOnlyQueryScope ignored = ReadOnlyQueryScope.begin(connection, true);
             Statement statement = binder == null
                     ? connection.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)
                     : connection.prepareStatement(sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
             OutputStream rawOutput = Files.newOutputStream(file);
             OutputStream output = new SizeLimitedOutputStream(rawOutput, EXPORT_MAX_BYTES)) {
            dialect.configureStreamingStatement(connection, statement, 500, properties.getSql().getTimeoutSeconds());
            statement.setMaxRows(EXPORT_MAX_ROWS + 1);
            if (binder != null) binder.bind((java.sql.PreparedStatement) statement);
            boolean truncated;
            try (ResultSet rs = binder == null
                    ? statement.executeQuery(sql)
                    : ((java.sql.PreparedStatement) statement).executeQuery()) {
                truncated = write(rs, normalizedFormat, output, dialect, normalizedTarget);
            }
            long elapsed = (System.nanoTime() - started) / 1_000_000;
            audit.onConnection(actor, "SQL_EXPORT", connectionId, auditDetail);
            history.insert(connectionId, sql, "EXPORT_" + normalizedFormat.toUpperCase(Locale.ROOT), "SUCCESS", elapsed, null, actor);
            return new PreparedExport(file, normalizedFormat, truncated, Files.size(file));
        } catch (Exception e) {
            Files.deleteIfExists(file);
            long elapsed = (System.nanoTime() - started) / 1_000_000;
            history.insert(connectionId, sql, "EXPORT_" + normalizedFormat.toUpperCase(Locale.ROOT), "FAILED", elapsed, abbreviate(e.getMessage()), actor);
            throw e;
        }
    }

    private boolean write(ResultSet rs, String format, OutputStream output, DatabaseDialect dialect, List<String> targetTableParts) throws Exception {
        return switch (format) {
            case "json" -> writeJson(rs, output);
            case "csv" -> writeCsv(rs, output);
            case "sql" -> writeSql(rs, output, dialect, targetTableParts);
            case "xml" -> writeXml(rs, output);
            case "markdown" -> writeMarkdown(rs, output);
            case "xlsx" -> writeXlsx(rs, output);
            default -> throw new IllegalArgumentException("不支持的导出格式：" + format);
        };
    }

    private Connection openConnection(long connectionId, String schemaName) throws Exception {
        return schemaName == null || schemaName.isBlank()
                ? connections.open(connectionId)
                : connections.open(connectionId, schemaName);
    }

    private boolean writeJson(ResultSet rs, OutputStream output) throws Exception {
        ResultSetMetaData metadata = rs.getMetaData();
        try (JsonGenerator json = mapper.getFactory().createGenerator(output)) {
            json.writeStartObject();
            json.writeArrayFieldStart("columns");
            for (int index = 1; index <= metadata.getColumnCount(); index++) json.writeString(metadata.getColumnLabel(index));
            json.writeEndArray();
            json.writeArrayFieldStart("rows");
            int rows = 0;
            while (rows < EXPORT_MAX_ROWS && rs.next()) {
                json.writeStartArray();
                for (int index = 1; index <= metadata.getColumnCount(); index++) json.writeObject(exportValue(rs.getObject(index)));
                json.writeEndArray();
                rows++;
            }
            boolean truncated = rs.next();
            json.writeEndArray();
            json.writeBooleanField("truncated", truncated);
            json.writeNumberField("maxRows", EXPORT_MAX_ROWS);
            json.writeEndObject();
            return truncated;
        }
    }

    private boolean writeCsv(ResultSet rs, OutputStream output) throws Exception {
        ResultSetMetaData metadata = rs.getMetaData();
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8));
        writer.write('\uFEFF');
        List<String> header = new ArrayList<>();
        for (int index = 1; index <= metadata.getColumnCount(); index++) header.add(csvValue(metadata.getColumnLabel(index)));
        writer.write(String.join(",", header));
        writer.newLine();
        int rows = 0;
        while (rows < EXPORT_MAX_ROWS && rs.next()) {
            List<String> values = new ArrayList<>();
            for (int index = 1; index <= metadata.getColumnCount(); index++) values.add(csvValue(exportValue(rs.getObject(index))));
            writer.write(String.join(",", values));
            writer.newLine();
            rows++;
        }
        boolean truncated = rs.next();
        writer.flush();
        return truncated;
    }

    /**
     * Markdown 表格。
     *
     * <p>与前端 queryResultExport.ts 的 serializeMarkdown 写的是同一种表格，转义规则必须一致：
     * 单元格里不能出现裸竖线（会把整行的列数撑乱，后面所有列错位），也不能跨行（换行折成
     * {@code <br>} 而不是丢弃，丢掉会让多行文本看起来像被截断了）。</p>
     *
     * <p>数值列右对齐，和界面结果表格的规矩一致 —— 贴出去之后才对得上位、能比较大小。</p>
     */
    private boolean writeMarkdown(ResultSet rs, OutputStream output) throws Exception {
        ResultSetMetaData metadata = rs.getMetaData();
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8));
        int columnCount = metadata.getColumnCount();
        List<String> header = new ArrayList<>();
        List<String> alignment = new ArrayList<>();
        for (int index = 1; index <= columnCount; index++) {
            header.add(markdownValue(metadata.getColumnLabel(index)));
            alignment.add(isNumericColumn(metadata.getColumnType(index)) ? "---:" : "---");
        }
        writer.write("| " + String.join(" | ", header) + " |");
        writer.newLine();
        writer.write("| " + String.join(" | ", alignment) + " |");
        writer.newLine();
        int rows = 0;
        while (rows < EXPORT_MAX_ROWS && rs.next()) {
            List<String> values = new ArrayList<>();
            for (int index = 1; index <= columnCount; index++) values.add(markdownValue(exportValue(rs.getObject(index))));
            writer.write("| " + String.join(" | ", values) + " |");
            writer.newLine();
            rows++;
        }
        boolean truncated = rs.next();
        writer.flush();
        return truncated;
    }

    private static boolean isNumericColumn(int jdbcType) {
        return Set.of(Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT, Types.FLOAT,
                Types.REAL, Types.DOUBLE, Types.NUMERIC, Types.DECIMAL).contains(jdbcType);
    }

    private String markdownValue(Object value) {
        if (value == null) return "";
        return value.toString()
                .replace("\\", "\\\\")
                .replace("|", "\\|")
                .replace("\r\n", "<br>")
                .replace("\n", "<br>")
                .replace("\r", "<br>");
    }

    private boolean writeSql(ResultSet rs, OutputStream output, DatabaseDialect dialect, List<String> requestedTargetTableParts) throws Exception {
        ResultSetMetaData metadata = rs.getMetaData();
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8));
        List<String> targetTableParts = requestedTargetTableParts;
        if (targetTableParts == null || targetTableParts.isEmpty()) {
            var sourceTable = ResultSetSourceResolver.resolve(metadata, dialect);
            targetTableParts = sourceTable == null ? List.of() : sourceTable.nameParts();
        }
        if (targetTableParts.isEmpty()) {
            throw new ApiProblemException(
                    HttpStatus.CONFLICT,
                    "SQL_EXPORT_TARGET_REQUIRED",
                    "无法从查询结果确定唯一的 INSERT 目标表，请指定目标表后重试。"
            );
        }
        String targetTable = targetTableParts.stream().map(dialect::quoteIdentifier).collect(Collectors.joining("."));
        List<String> columns = uniqueColumnNames(metadata).stream().map(dialect::quoteIdentifier).toList();
        int rows = 0;
        while (rows < EXPORT_MAX_ROWS && rs.next()) {
            List<String> values = new ArrayList<>();
            for (int index = 1; index <= metadata.getColumnCount(); index++) {
                values.add(dialect.scriptLiteral(sqlExportValue(rs, metadata, index)));
            }
            writer.write("INSERT INTO " + targetTable + " (" + String.join(", ", columns) + ") VALUES (" + String.join(", ", values) + ");");
            writer.newLine();
            rows++;
        }
        if (rows == 0) writer.write("-- 查询结果为空，未生成 INSERT 语句。\n");
        boolean truncated = rs.next();
        if (truncated) writer.write("-- 结果已在 " + EXPORT_MAX_ROWS + " 行处截断。\n");
        writer.flush();
        return truncated;
    }

    /**
     * 写 xlsx。
     *
     * <p>相对 CSV 的意义在于类型不会被 Excel 重新猜一遍：文本列不会被解释成日期或科学
     * 计数法。数值仍按数值写出，这样在 Excel 里能直接求和排序。</p>
     */
    private boolean writeXlsx(ResultSet rs, OutputStream output) throws Exception {
        ResultSetMetaData metadata = rs.getMetaData();
        int columnCount = metadata.getColumnCount();
        try (XlsxWriter workbook = new XlsxWriter(output, "查询结果")) {
            Object[] header = new Object[columnCount];
            for (int index = 1; index <= columnCount; index++) header[index - 1] = metadata.getColumnLabel(index);
            workbook.row(header);
            int rows = 0;
            while (rows < EXPORT_MAX_ROWS && rs.next()) {
                Object[] cells = new Object[columnCount];
                for (int index = 1; index <= columnCount; index++) cells[index - 1] = xlsxValue(rs.getObject(index));
                workbook.row(cells);
                rows++;
            }
            return rs.next();
        }
    }

    /**
     * xlsx 的单元格取值。
     *
     * <p>{@link #exportValue} 出于 JSON 精度考虑把 Long/BigInteger/BigDecimal 转成了字符串，
     * 这里要还原成数值 —— 否则在 Excel 里既不能求和也不能按数值排序。</p>
     *
     * <p>但 Excel 用 IEEE 754 双精度存数字，超过 15 位有效数字的整数会被静默改写（19 位的
     * 雪花 ID 末尾会变成 0）。这类值宁可按文本写出：导出结果和库里不一致，比不能求和严重
     * 得多。</p>
     */
    private Object xlsxValue(Object value) throws Exception {
        if (value instanceof Long || value instanceof BigInteger || value instanceof BigDecimal) {
            Number number = (Number) value;
            return exceedsExcelPrecision(number) ? number.toString() : number;
        }
        return exportValue(value);
    }

    private static boolean exceedsExcelPrecision(Number number) {
        BigInteger digits = number instanceof BigDecimal decimal
                ? decimal.unscaledValue().abs()
                : number instanceof BigInteger integer ? integer.abs() : BigInteger.valueOf(number.longValue()).abs();
        return digits.toString().length() > 15;
    }

    private boolean writeXml(ResultSet rs, OutputStream output) throws Exception {
        ResultSetMetaData metadata = rs.getMetaData();
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8));
        writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<result>\n  <rows>\n");
        int rows = 0;
        while (rows < EXPORT_MAX_ROWS && rs.next()) {
            writer.write("    <row>\n");
            for (int index = 1; index <= metadata.getColumnCount(); index++) {
                writer.write("      <column name=\"" + xmlValue(metadata.getColumnLabel(index)) + "\">" + xmlValue(exportValue(rs.getObject(index))) + "</column>\n");
            }
            writer.write("    </row>\n");
            rows++;
        }
        boolean truncated = rs.next();
        writer.write("  </rows>\n  <truncated>" + truncated + "</truncated>\n  <maxRows>" + EXPORT_MAX_ROWS + "</maxRows>\n</result>\n");
        writer.flush();
        return truncated;
    }

    private List<String> uniqueColumnNames(ResultSetMetaData metadata) throws Exception {
        List<String> result = new ArrayList<>();
        Set<String> used = new java.util.HashSet<>();
        for (int index = 1; index <= metadata.getColumnCount(); index++) {
            String base = metadata.getColumnLabel(index);
            String name = base;
            int suffix = 2;
            while (!used.add(name.toLowerCase(Locale.ROOT))) name = base + "_" + suffix++;
            result.add(name);
        }
        return result;
    }

    private Object exportValue(Object value) throws Exception {
        if (value == null) return null;
        if (value instanceof Clob clob) {
            long length = clob.length();
            int visible = (int) Math.min(length, 10_000);
            String text = clob.getSubString(1, visible);
            return length > visible ? text + "… <CLOB 已截断，共 " + length + " 字符>" : text;
        }
        if (value instanceof Blob blob) return "<BLOB " + blob.length() + " bytes>";
        if (value instanceof byte[] bytes) return "<BINARY " + bytes.length + " bytes>";
        if (value instanceof Long || value instanceof BigInteger || value instanceof BigDecimal) return value.toString();
        if (value instanceof CharSequence text) {
            String string = text.toString();
            return string.length() > EXPORT_MAX_CELL_TEXT_CHARS
                    ? truncateText(string, "… <文本已截断，共 " + string.length() + " 字符>", EXPORT_MAX_CELL_TEXT_CHARS)
                    : string;
        }
        if (value instanceof Float number && !Float.isFinite(number)) return number.toString();
        if (value instanceof Double number && !Double.isFinite(number)) return number.toString();
        if (value instanceof Number || value instanceof Boolean) return value;
        return truncateText(value.toString(), "", EXPORT_MAX_CELL_TEXT_CHARS);
    }

    private Object sqlExportValue(ResultSet rs, ResultSetMetaData metadata, int index) throws Exception {
        int jdbcType = metadata.getColumnType(index);
        if (Set.of(Types.BLOB, Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY).contains(jdbcType)) {
            try (InputStream input = rs.getBinaryStream(index)) {
                if (input == null) return null;
                return readBinaryCell(input);
            }
        }
        Object value = rs.getObject(index);
        if (value instanceof Blob blob) {
            try (InputStream input = blob.getBinaryStream()) {
                return readBinaryCell(input);
            }
        }
        if (value instanceof byte[] bytes) {
            requireSqlCellSize(bytes.length, "二进制");
            return bytes;
        }
        if (value instanceof Clob clob) {
            try (Reader reader = clob.getCharacterStream()) {
                return readTextCell(reader);
            }
        }
        if (value instanceof SQLXML xml) return requireSqlTextCell(xml.getString());
        if (value instanceof CharSequence text) return requireSqlTextCell(text.toString());
        return value;
    }

    private byte[] readBinaryCell(InputStream input) throws IOException {
        byte[] value = input.readNBytes(SQL_EXPORT_MAX_CELL_BYTES + 1);
        requireSqlCellSize(value.length, "二进制");
        return value;
    }

    private String readTextCell(Reader reader) throws IOException {
        StringBuilder value = new StringBuilder();
        char[] buffer = new char[16 * 1024];
        int read;
        while ((read = reader.read(buffer)) >= 0) {
            if (read == 0) continue;
            if (value.length() > SQL_EXPORT_MAX_CELL_CHARS - read) {
                throw new IOException("SQL 导出中的文本单元格超过 64 MB 限制，请缩小查询范围。");
            }
            value.append(buffer, 0, read);
        }
        return value.toString();
    }

    private String requireSqlTextCell(String value) throws IOException {
        requireSqlCellSize(value.length(), "文本");
        return value;
    }

    private void requireSqlCellSize(int size, String kind) throws IOException {
        int limit = "文本".equals(kind) ? SQL_EXPORT_MAX_CELL_CHARS : SQL_EXPORT_MAX_CELL_BYTES;
        if (size > limit) throw new IOException("SQL 导出中的" + kind + "单元格超过 64 MB 限制，请缩小查询范围。");
    }

    private String normalizeFormat(String format) {
        String normalized = format == null ? "" : format.toLowerCase(Locale.ROOT);
        if (!Set.of("csv", "json", "sql", "xml", "markdown", "xlsx").contains(normalized)) throw new IllegalArgumentException("不支持的导出格式：" + format);
        return normalized;
    }

    private List<String> normalizeTargetTableParts(List<String> values, String format) {
        if (!"sql".equals(format) || values == null || values.isEmpty()) return List.of();
        if (values.size() > 3) throw new IllegalArgumentException("INSERT 目标表最多支持三级限定名称。");
        List<String> normalized = values.stream().map(value -> value == null ? "" : value.trim()).toList();
        if (normalized.stream().anyMatch(String::isBlank)) throw new IllegalArgumentException("INSERT 目标表名称不能为空。");
        return List.copyOf(normalized);
    }

    private String csvValue(Object value) {
        String text = value == null ? "" : value.toString();
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }

    private String xmlValue(Object value) {
        if (value == null) return "";
        StringBuilder validXml = new StringBuilder();
        value.toString().codePoints().forEach(codePoint -> {
            boolean allowed = codePoint == 0x9 || codePoint == 0xA || codePoint == 0xD
                    || codePoint >= 0x20 && codePoint <= 0xD7FF
                    || codePoint >= 0xE000 && codePoint <= 0xFFFD
                    || codePoint >= 0x10000 && codePoint <= 0x10FFFF;
            validXml.appendCodePoint(allowed ? codePoint : 0xFFFD);
        });
        return validXml.toString().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;");
    }

    private String abbreviate(String value) {
        if (value == null) return "";
        return value.length() <= 2_000 ? value : value.substring(0, 2_000);
    }

    private String truncateText(String prefixSource, String marker, int maxChars) {
        if (maxChars <= 0) return "";
        if (prefixSource.length() <= maxChars && marker.isEmpty()) return prefixSource;
        if (marker.length() >= maxChars) return prefixSource.substring(0, Math.min(prefixSource.length(), maxChars));
        int prefixLength = Math.min(prefixSource.length(), maxChars - marker.length());
        return prefixSource.substring(0, prefixLength) + marker;
    }

    private static final class SizeLimitedOutputStream extends OutputStream {
        private final OutputStream delegate;
        private final long maximumBytes;
        private long written;

        private SizeLimitedOutputStream(OutputStream delegate, long maximumBytes) {
            this.delegate = delegate;
            this.maximumBytes = maximumBytes;
        }

        @Override
        public void write(int value) throws IOException {
            requireCapacity(1);
            delegate.write(value);
            written++;
        }

        @Override
        public void write(byte[] values, int offset, int length) throws IOException {
            requireCapacity(length);
            delegate.write(values, offset, length);
            written += length;
        }

        @Override
        public void flush() throws IOException {
            delegate.flush();
        }

        @Override
        public void close() throws IOException {
            // The owner controls the underlying response/file stream. JSON
            // generators may close this wrapper while the servlet still needs
            // to finish the response and persist the export outcome.
            delegate.flush();
        }

        private void requireCapacity(int additionalBytes) throws IOException {
            if (additionalBytes < 0 || written > maximumBytes - additionalBytes) {
                throw new IOException("导出文件超过 " + (maximumBytes / 1024 / 1024) + " MB 限制，请缩小查询范围。");
            }
        }
    }

    public static final class PreparedExport {
        private final Path path;
        private final String format;
        private final boolean truncated;
        private final long size;
        private final Cleaner.Cleanable cleanable;

        private PreparedExport(Path path, String format, boolean truncated, long size) {
            this.path = path;
            this.format = format;
            this.truncated = truncated;
            this.size = size;
            this.cleanable = TEMP_FILE_CLEANER.register(this, new TempFileCleanup(path));
        }

        public Path path() {
            return path;
        }

        public String format() {
            return format;
        }

        public boolean truncated() {
            return truncated;
        }

        public long size() {
            return size;
        }

        public void writeTo(OutputStream output) throws IOException {
            try (InputStream input = Files.newInputStream(path)) {
                input.transferTo(output);
            } finally {
                cleanable.clean();
            }
        }

        public void discard() {
            cleanable.clean();
        }
    }

    private record TempFileCleanup(Path path) implements Runnable {
        @Override
        public void run() {
            try {
                Files.deleteIfExists(path);
            } catch (IOException ignored) {
                // The operating system temp directory remains the final
                // fallback when a disconnected client still holds the file.
            }
        }
    }
}
