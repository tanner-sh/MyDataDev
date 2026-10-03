package com.example.dbadmin.core;

import com.example.dbadmin.dto.ApiDtos.ColumnDesign;
import com.example.dbadmin.dto.ApiDtos.ColumnInfo;
import com.example.dbadmin.dto.ApiDtos.DatabaseCapabilities;
import com.example.dbadmin.dto.ApiDtos.ResultColumn;
import com.example.dbadmin.dto.ApiDtos.SqlResult;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

public class DamengDialect extends DefaultDialect {
    /** 达梦沿用 Oracle 那套折叠规则。 */
    @Override
    public String foldUnquotedIdentifier(String identifier) {
        return identifier == null ? null : identifier.toUpperCase(Locale.ROOT);
    }

    /** 与备份一直以来的判断一致，达梦逐行写。 */
    @Override
    public boolean supportsMultiRowValues() {
        return false;
    }


    @Override
    public String castToText(String expression) {
        return "TO_CHAR(" + expression + ")";
    }

    @Override
    public DatabaseCapabilities capabilities() {
        return new DatabaseCapabilities(true, true, true, true, List.of(), List.of(), SchemaObjectCapabilities.oracleFamily());
    }

    @Override
    public boolean supports(String dbType, String jdbcUrl) {
        String type = dbType == null ? "" : dbType.toLowerCase(Locale.ROOT);
        String url = jdbcUrl == null ? "" : jdbcUrl.toLowerCase(Locale.ROOT);
        return type.equals("dm") || type.equals("dameng") || url.startsWith("jdbc:dm:");
    }

    @Override
    public String backupConstraintName(String schema, String table, String name) {
        if (!name.matches("CONS[0-9]+")) return name;
        return "MDD_FK_" + java.util.UUID.nameUUIDFromBytes((schema + "." + table + "." + name)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString().replace("-", "");
    }

    @Override
    public java.util.Optional<String> backupIndexName(Connection connection, String schema, String table, String index) throws Exception {
        try (var statement = connection.prepareStatement("SELECT i.INDEX_TYPE, i.GENERATED, c.CONSTRAINT_TYPE FROM ALL_INDEXES i LEFT JOIN ALL_CONSTRAINTS c ON c.OWNER=i.OWNER AND c.INDEX_NAME=i.INDEX_NAME AND c.CONSTRAINT_TYPE='P' WHERE i.TABLE_OWNER=? AND i.TABLE_NAME=? AND i.INDEX_NAME=?")) {
            statement.setQueryTimeout(10);
            statement.setString(1, schema); statement.setString(2, table); statement.setString(3, index);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) throw new SQLException("无法识别达梦索引属性：" + index);
                if ("P".equals(rows.getString(3)) || "CLUSTER".equals(rows.getString(1)) && "Y".equals(rows.getString(2)))
                    return java.util.Optional.empty();
                // DM reserves INDEX<number> names. Keep generated UNIQUE semantics with a portable name.
                if ("Y".equals(rows.getString(2))) return java.util.Optional.of("MDD_BAK_" + java.util.UUID.nameUUIDFromBytes(
                        (schema + "." + table + "." + index).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString().replace("-", ""));
                return java.util.Optional.of(index);
            }
        }
    }

    /** EXPLAIN FOR 返回结构化估算计划；不执行目标查询，也不修改会话模式。 */
    @Override
    public SqlResult explain(Connection connection, String sql, int maxRows, int timeoutSeconds) throws Exception {
        long started = System.nanoTime();
        int limit = Math.min(Math.max(maxRows, 0), 50_000);
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(timeoutSeconds);
            statement.setMaxRows(limit + 1);
            if (connection.isReadOnly()) {
                // DM8 的 EXPLAIN FOR 写计划存储，不能在只读事务中使用。原生 EXPLAIN
                // 通过驱动扩展返回文本，不解除外层 ReadOnlyQueryScope 的保护。
                statement.execute("EXPLAIN " + sql);
                Class<?> driverStatement = Class.forName("dm.jdbc.driver.DmdbStatement");
                Object nativeStatement = statement.unwrap(driverStatement);
                String plan;
                try {
                    plan = (String) driverStatement.getMethod("getExplain").invoke(nativeStatement);
                } catch (ReflectiveOperationException error) {
                    throw new SQLException("无法读取达梦驱动返回的文本执行计划。", error);
                }
                return readTextPlan(plan, limit, (System.nanoTime() - started) / 1_000_000);
            }
            try (ResultSet result = statement.executeQuery("EXPLAIN FOR " + sql)) {
                return readResult(result, (System.nanoTime() - started) / 1_000_000, limit);
            }
        }
    }

    static SqlResult readTextPlan(String plan, int limit, long elapsedMs) throws SQLException {
        if (plan == null || plan.isBlank()) throw new SQLException("达梦驱动未返回文本执行计划。");
        boolean truncated = plan.length() > 5_000_000;
        String bounded = truncated ? plan.substring(0, 5_000_000) : plan;
        List<List<Object>> rows = new ArrayList<>();
        var lines = bounded.lines().filter(line -> !line.isBlank()).iterator();
        while (lines.hasNext() && rows.size() < limit) {
            String line = lines.next();
            if (line.length() > 100_000) { line = line.substring(0, 100_000) + "…"; truncated = true; }
            rows.add(List.of(line));
        }
        return new SqlResult(List.of(new ResultColumn("c1", "DM_PLAN", "VARCHAR")), rows, -1,
                elapsedMs, true, limit, truncated || lines.hasNext());
    }

    @Override
    protected List<String> alterColumnSql(String table, String columnName, ColumnInfo original, ColumnDesign column) {
        boolean typeChanged = !sameType(original, column);
        boolean nullableChanged = original.nullable() != column.nullable();
        boolean defaultChanged = !java.util.Objects.equals(normalizeDefault(original.defaultValue()), normalizeDefault(column.defaultValue()));
        List<String> statements = new ArrayList<>();
        if (typeChanged || nullableChanged || defaultChanged) {
            String definition = quoteIdentifier(columnName);
            if (typeChanged) definition += " " + type(column.type(), column.size());
            if (defaultChanged) definition += blankToNull(column.defaultValue()) == null
                    ? " DEFAULT NULL" : " DEFAULT " + column.defaultValue().trim();
            if (nullableChanged) definition += column.nullable() ? " NULL" : " NOT NULL";
            statements.add("ALTER TABLE " + table + " MODIFY (" + definition + ")");
        }
        if (commentChanged(original.remarks(), column.remarks()))
            statements.addAll(columnCommentSql(table, columnName, column.remarks()));
        return statements;
    }

    @Override
    public String literal(Object value) {
        if (value instanceof Boolean bool) {
            return bool ? "1" : "0";
        }
        return super.literal(value);
    }

    @Override
    public String scriptBinaryLiteral(byte[] value) {
        return "hextoraw('" + HexFormat.of().formatHex(value) + "')";
    }
}
