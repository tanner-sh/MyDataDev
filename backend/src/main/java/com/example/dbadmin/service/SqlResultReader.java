package com.example.dbadmin.service;

import com.example.dbadmin.core.CellSerializer;
import com.example.dbadmin.core.DatabaseDialect;
import com.example.dbadmin.dto.ApiDtos.ResultColumn;
import com.example.dbadmin.dto.ApiDtos.SqlResult;
import com.example.dbadmin.model.DbConnection;

import java.io.InputStream;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 把 JDBC 结果集读成界面上那张结果网格（{@code SqlResult}）：行数、单元格数、总字符数三道上限，
 * 二进制只给描述不给内容，CLOB 只读一个窗口，以及截断原因（行数 / 单元格 / 文本）。
 *
 * <p>从 {@link SqlService} 拆出来。SQL 工作台的单条执行、脚本、MCP 查询与事务里的查询读的都是这一份，
 * 上限与截断说法因此处处一致。</p>
 */
final class SqlResultReader {
    static final int MAX_RESULT_CELLS = 200_000;
    static final long MAX_RESULT_TEXT_CHARS = 20_000_000;
    static final int MAX_CELL_TEXT_CHARS = 100_000;
    /** CLOB 先读回多少字符用于判断截断。查询结果一屏放不下太长的文本，取一个比单元格上限小得多的窗口。 */
    private static final int MAX_CLOB_WINDOW_CHARS = 10_000;
    /** 手动事务复用同一套结果读取与限额，见 SqlTransactionService。 */
    static SqlResult readResult(ResultSet rs, long startedNanos, int maxRows, DatabaseDialect dialect) throws Exception {
        return readResult(rs, startedNanos, maxRows, MAX_RESULT_CELLS, MAX_RESULT_TEXT_CHARS, dialect);
    }

    static SqlResult withColumnSources(SqlResult result, ResultSet rs, Connection connection, DbConnection dbConnection,
                                       long connectionId, String schemaName, String sql, DatabaseDialect dialect) {
        try {
            String namespace = schemaName == null || schemaName.isBlank() ? dialect.currentSchema(connection) : schemaName;
            var columns = ResultColumnSourceResolver.resolve(result.columns(), rs.getMetaData(), sql,
                    dbConnection.dbType(), connectionId, namespace, dialect);
            return new SqlResult(columns, result.rows(), result.affectedRows(), result.elapsedMs(), result.resultSet(),
                    result.maxRows(), result.truncated(), result.page(), result.sourceTable(), result.edit(), result.truncationReason());
        } catch (Exception ignored) {
            return result;
        }
    }

    static SqlResult readResult(ResultSet rs, long startedNanos, int maxRows, int cellBudget, long textBudget, DatabaseDialect dialect) throws Exception {
        return readResult(rs, startedNanos, maxRows, cellBudget, textBudget, MAX_CELL_TEXT_CHARS, dialect);
    }

    static SqlResult readResult(
            ResultSet rs,
            long startedNanos,
            int maxRows,
            int cellBudget,
            long textBudget,
            int cellTextBudget,
            DatabaseDialect dialect
    ) throws Exception {
        ResultSetMetaData metadata = rs.getMetaData();
        int columnCount = metadata.getColumnCount();
        List<ResultColumn> columns = new ArrayList<>();
        for (int index = 1; index <= columnCount; index++) {
            columns.add(new ResultColumn("c" + index, metadata.getColumnLabel(index), metadata.getColumnTypeName(index)));
        }
        int effectiveMaxRows = textBudget <= 0
                ? 0
                : Math.min(Math.max(maxRows, 0), Math.max(cellBudget, 0) / Math.max(columnCount, 1));
        List<List<Object>> rows = new ArrayList<>();
        long textChars = 0;
        boolean payloadLimitReached = false;
        boolean cellTextTruncated = false;
        while (rows.size() < effectiveMaxRows && rs.next()) {
            List<Object> row = new ArrayList<>(columnCount);
            for (int index = 1; index <= columnCount; index++) {
                int remainingText = (int) Math.min(cellTextBudget, Math.max(0, textBudget - textChars));
                CellValue cell = readCellWithTruncation(rs, metadata, index, remainingText);
                Object value = cell.value();
                cellTextTruncated |= cell.truncated();
                row.add(value);
                if (value instanceof CharSequence text) textChars += text.length();
            }
            rows.add(row);
            if (textChars >= textBudget) {
                payloadLimitReached = true;
                break;
            }
        }
        boolean moreRows = rs.next();
        boolean truncated = cellTextTruncated || payloadLimitReached || moreRows;
        return new SqlResult(
                columns,
                rows,
                -1,
                elapsed(startedNanos),
                true,
                effectiveMaxRows,
                truncated,
                null,
                ResultSetSourceResolver.resolve(metadata, dialect), null,
                cellTextTruncated || payloadLimitReached ? "text_limit" : moreRows ? (effectiveMaxRows < maxRows ? "cell_limit" : "row_limit") : "none"
        );
    }

    /** Oracle 的 ROWNUM 分页会在最后多出一列行号，它不属于用户的结果。 */
    static SqlResult withoutHelperColumn(SqlResult result, String helperColumn) {
        int last = result.columns().size() - 1;
        if (helperColumn == null || last < 0 || !helperColumn.equalsIgnoreCase(result.columns().get(last).label())) return result;
        // 单元格可以是 SQL NULL，所以不能用 List.copyOf。
        List<List<Object>> rows = result.rows().stream().map(row -> row.size() > last ? (List<Object>) new ArrayList<>(row.subList(0, last)) : row).toList();
        return new SqlResult(result.columns().subList(0, last), rows, result.affectedRows(), result.elapsedMs(), result.resultSet(),
                result.maxRows(), result.truncated(), result.page(), result.sourceTable(), result.edit(), result.truncationReason());
    }

    private static Object serializableValue(Object value, int maxTextChars) throws Exception {
        return CellSerializer.serialize(value, maxTextChars, MAX_CLOB_WINDOW_CHARS);
    }

    static Object serializableValue(ResultSet rs, ResultSetMetaData metadata, int index, int maxTextChars) throws Exception {
        int jdbcType = metadata.getColumnType(index);
        if (Set.of(Types.BLOB, Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY).contains(jdbcType)) {
            String description = binaryDescription(rs, index);
            return description == null ? null : CellSerializer.truncate(description, "", maxTextChars);
        }
        return serializableValue(rs.getObject(index), maxTextChars);
    }

    private record CellValue(Object value, boolean truncated) { }

    private static CellValue readCellWithTruncation(ResultSet rs, ResultSetMetaData metadata, int index, int limit) throws Exception {
        int type = metadata.getColumnType(index);
        if (Set.of(Types.BLOB, Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY).contains(type)) {
            String text = binaryDescription(rs, index);
            return new CellValue(text == null ? null : CellSerializer.truncate(text, "", limit), text != null && text.length() > limit);
        }
        Object raw = rs.getObject(index);
        boolean clipped = false;
        if (raw instanceof Clob clob) clipped = clob.length() > Math.min(MAX_CLOB_WINDOW_CHARS, Math.max(0, limit));
        else if (raw != null && (!(raw instanceof Number) || raw instanceof Long || raw instanceof java.math.BigInteger || raw instanceof java.math.BigDecimal)) {
            clipped = !(raw instanceof Boolean) && CellSerializer.text(raw).length() > limit;
        }
        return new CellValue(serializableValue(raw, limit), clipped);
    }

    private static String binaryDescription(ResultSet rs, int index) throws Exception {
        try {
            Blob blob = rs.getBlob(index);
            if (blob != null) return "<BINARY " + blob.length() + " bytes>";
            if (rs.wasNull()) return null;
        } catch (Exception ignored) {
            // Some drivers expose VARBINARY only through a binary stream.
        }
        try (InputStream input = rs.getBinaryStream(index)) {
            if (input == null) return null;
            return CellSerializer.describeBinaryStream(input);
        }
    }

    static long textChars(SqlResult result) {
        long count = 0;
        for (List<Object> row : result.rows()) {
            for (Object value : row) {
                if (value instanceof CharSequence text) count += text.length();
            }
        }
        return count;
    }

    private static long elapsed(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    private SqlResultReader() {
    }
}
