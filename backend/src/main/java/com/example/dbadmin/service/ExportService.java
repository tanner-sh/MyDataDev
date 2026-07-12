package com.example.dbadmin.service;

import com.example.dbadmin.config.AppProperties;
import com.example.dbadmin.core.DatabaseDialect;
import com.example.dbadmin.core.DialectRegistry;
import com.example.dbadmin.model.DbConnection;
import com.example.dbadmin.repo.AuditRepository;
import com.example.dbadmin.repo.SqlHistoryRepository;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.lang.ref.Cleaner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
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
        PreparedExport prepared = prepare(connectionId, sql, format, actor, null);
        prepared.writeTo(output);
    }

    public PreparedExport prepare(long connectionId, String sql, String format, String actor, String productionConfirmation) throws Exception {
        String normalizedFormat = normalizeFormat(format);
        var statements = splitter.split(sql);
        if (statements.size() != 1 || !classifier.isQuery(statements.get(0).sql())) {
            throw new IllegalArgumentException("导出仅支持单条查询语句，不会执行写入或 DDL。");
        }
        DbConnection dbConnection = connections.require(connectionId);
        executionGuard.requireQueryAllowed(dbConnection, classifier.classify(statements.get(0).sql()), productionConfirmation);
        DatabaseDialect dialect = dialectRegistry.dialectFor(dbConnection);
        long started = System.nanoTime();
        Path file = Files.createTempFile("dbadmin-export-", "." + normalizedFormat);
        try (Connection connection = connections.open(connectionId);
             // Export is contractually read-only even when the saved
             // connection itself is writable. Roll back SELECT routines with
             // transactional side effects and apply the JDBC read-only hint.
             ReadOnlyQueryScope ignored = ReadOnlyQueryScope.begin(connection, true);
             Statement statement = connection.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
             OutputStream rawOutput = Files.newOutputStream(file);
             OutputStream output = new SizeLimitedOutputStream(rawOutput, EXPORT_MAX_BYTES)) {
            dialect.configureStreamingStatement(connection, statement, 500, properties.getSql().getTimeoutSeconds());
            statement.setMaxRows(EXPORT_MAX_ROWS + 1);
            boolean truncated;
            try (ResultSet rs = statement.executeQuery(statements.get(0).sql())) {
                truncated = write(rs, normalizedFormat, output);
            }
            long elapsed = (System.nanoTime() - started) / 1_000_000;
            audit.log(actor, "SQL_EXPORT", "connection:" + connectionId, abbreviate(sql));
            history.insert(connectionId, sql, "EXPORT_" + normalizedFormat.toUpperCase(Locale.ROOT), "SUCCESS", elapsed, null, actor);
            return new PreparedExport(file, normalizedFormat, truncated, Files.size(file));
        } catch (Exception e) {
            Files.deleteIfExists(file);
            long elapsed = (System.nanoTime() - started) / 1_000_000;
            history.insert(connectionId, sql, "EXPORT_" + normalizedFormat.toUpperCase(Locale.ROOT), "FAILED", elapsed, abbreviate(e.getMessage()), actor);
            throw e;
        }
    }

    private boolean write(ResultSet rs, String format, OutputStream output) throws Exception {
        return switch (format) {
            case "json" -> writeJson(rs, output);
            case "csv" -> writeCsv(rs, output);
            case "sql" -> writeSql(rs, output);
            case "xml" -> writeXml(rs, output);
            default -> throw new IllegalArgumentException("不支持的导出格式：" + format);
        };
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

    private boolean writeSql(ResultSet rs, OutputStream output) throws Exception {
        ResultSetMetaData metadata = rs.getMetaData();
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8));
        List<String> columns = uniqueColumnNames(metadata).stream().map(this::sqlIdentifier).toList();
        int rows = 0;
        while (rows < EXPORT_MAX_ROWS && rs.next()) {
            List<String> values = new ArrayList<>();
            for (int index = 1; index <= metadata.getColumnCount(); index++) values.add(sqlLiteral(sqlExportValue(rs.getObject(index))));
            writer.write("INSERT INTO query_result (" + String.join(", ", columns) + ") VALUES (" + String.join(", ", values) + ");");
            writer.newLine();
            rows++;
        }
        if (rows == 0) writer.write("-- 查询结果为空，未生成 INSERT 语句。\n");
        boolean truncated = rs.next();
        if (truncated) writer.write("-- 结果已在 " + EXPORT_MAX_ROWS + " 行处截断。\n");
        writer.flush();
        return truncated;
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

    private Object sqlExportValue(Object value) throws Exception {
        if (value instanceof Long || value instanceof BigInteger || value instanceof BigDecimal) return value;
        return exportValue(value);
    }

    private String normalizeFormat(String format) {
        String normalized = format == null ? "" : format.toLowerCase(Locale.ROOT);
        if (!Set.of("csv", "json", "sql", "xml").contains(normalized)) throw new IllegalArgumentException("不支持的导出格式：" + format);
        return normalized;
    }

    private String csvValue(Object value) {
        String text = value == null ? "" : value.toString();
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }

    private String sqlIdentifier(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private String sqlLiteral(Object value) {
        if (value == null) return "NULL";
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        return "'" + value.toString().replace("'", "''") + "'";
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
            delegate.close();
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

        public void writeTo(OutputStream output) throws Exception {
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
