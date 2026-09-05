package com.example.dbadmin.core;

import com.example.dbadmin.dto.ApiDtos.SqlResult;
import com.example.dbadmin.dto.ApiDtos.ColumnDesign;
import com.example.dbadmin.dto.ApiDtos.ColumnInfo;
import com.example.dbadmin.dto.ApiDtos.ObjectDetail;
import com.example.dbadmin.dto.ApiDtos.DatabaseCapabilities;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public class OracleDialect extends DefaultDialect {
    // Oracle-compatible unquoted identifiers must start with a letter. Keep this
    // helper unquoted so ResultSet metadata is consistent across Oracle and
    // OceanBase Oracle mode.
    private static final String PAGE_ROW_COLUMN = "DBADMIN_PAGE_RN";


    @Override
    public String castToText(String expression) {
        return "TO_CHAR(" + expression + ")";
    }

    @Override
    public DatabaseCapabilities capabilities() {
        return new DatabaseCapabilities(true, true, true, true, List.of("ORACLE_EXP"), List.of("ORACLE_IMP"), SchemaObjectCapabilities.oracleFamily());
    }

    @Override
    public boolean supports(String dbType, String jdbcUrl) {
        return "oracle".equalsIgnoreCase(dbType)
                || (jdbcUrl != null && jdbcUrl.toLowerCase(Locale.ROOT).startsWith("jdbc:oracle:"));
    }

    @Override
    public String pageQuery(String baseSql, int limit, int offset) {
        long upperBound = (long) offset + limit;
        return "SELECT * FROM (SELECT dbadmin_page_source.*, ROWNUM " + PAGE_ROW_COLUMN + " FROM (" + baseSql
                + ") dbadmin_page_source WHERE ROWNUM <= " + upperBound + ") WHERE " + PAGE_ROW_COLUMN + " > " + offset;
    }

    @Override
    public String paginationHelperColumn() {
        return PAGE_ROW_COLUMN;
    }

    @Override
    public String currentSchema(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA') FROM DUAL")) {
            if (rs.next()) {
                String schema = rs.getString(1);
                if (schema != null && !schema.isBlank()) {
                    return schema;
                }
            }
        } catch (Exception ignored) {
            // Fall back to the portable JDBC schema/catalog lookup.
        }
        return super.currentSchema(connection);
    }

    @Override
    public SqlResult explain(Connection connection, String sql, int maxRows, int timeoutSeconds) throws Exception {
        long started = System.nanoTime();
        try (Statement explain = connection.createStatement()) {
            explain.setQueryTimeout(timeoutSeconds);
            explain.execute("EXPLAIN PLAN FOR " + sql);
        }
        try (Statement display = connection.createStatement()) {
            display.setQueryTimeout(timeoutSeconds);
            display.setMaxRows(maxRows + 1);
            try (ResultSet rs = display.executeQuery("SELECT PLAN_TABLE_OUTPUT FROM TABLE(DBMS_XPLAN.DISPLAY())")) {
                return readResult(rs, (System.nanoTime() - started) / 1_000_000, maxRows);
            }
        }
    }

    @Override
    public Optional<String> nativeDdl(Connection connection, String schemaName, String objectName, String objectType) throws Exception {
        String ddlType = objectType != null && objectType.toUpperCase(Locale.ROOT).contains("VIEW") ? "VIEW" : "TABLE";
        String schema = schemaName == null || schemaName.isBlank() ? currentSchema(connection) : schemaName;
        String sql = "SELECT DBMS_METADATA.GET_DDL('" + ddlType + "', ?, ?) FROM DUAL";
        try (java.sql.PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, objectName);
            statement.setString(2, schema);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.ofNullable(rs.getString(1)) : Optional.empty();
            }
        }
    }

    @Override
    protected List<String> alterColumnSql(String table, String columnName, ColumnInfo original, ColumnDesign column) {
        boolean typeChanged = !sameType(original, column);
        boolean nullableChanged = original.nullable() != column.nullable();
        boolean defaultChanged = !java.util.Objects.equals(normalizeDefault(original.defaultValue()), normalizeDefault(column.defaultValue()));
        if (!typeChanged && !nullableChanged && !defaultChanged) {
            return List.of();
        }
        String definition = quoteIdentifier(columnName);
        if (typeChanged) {
            definition += " " + type(column.type(), column.size());
        }
        if (defaultChanged) {
            definition += blankToNull(column.defaultValue()) == null ? " DEFAULT NULL" : " DEFAULT " + column.defaultValue().trim();
        }
        if (nullableChanged) {
            definition += column.nullable() ? " NULL" : " NOT NULL";
        }
        return List.of("ALTER TABLE " + table + " MODIFY (" + definition + ")");
    }

    @Override
    protected String addColumnSql(String table, ColumnDesign column) {
        return "ALTER TABLE " + table + " ADD (" + columnDefinition(column) + ")";
    }

    @Override
    protected List<String> primaryKeySql(String table, ObjectDetail original, List<String> requestedPrimaryKeys) {
        List<String> requested = requestedPrimaryKeys == null ? List.of() : requestedPrimaryKeys.stream().filter(name -> name != null && !name.isBlank()).toList();
        if (sameNames(original.primaryKeys(), requested)) {
            return List.of();
        }
        List<String> sql = new ArrayList<>();
        if (!original.primaryKeys().isEmpty()) {
            if (original.primaryKeyName() == null || original.primaryKeyName().isBlank()) {
                throw new IllegalArgumentException("Oracle 主键变更需要可识别的主键约束名。");
            }
            sql.add("ALTER TABLE " + table + " DROP CONSTRAINT " + quoteIdentifier(original.primaryKeyName()));
        }
        if (!requested.isEmpty()) {
            sql.add("ALTER TABLE " + table + " ADD PRIMARY KEY (" + String.join(", ", requested.stream().map(this::quoteIdentifier).toList()) + ")");
        }
        return sql;
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

    @Override
    public String activeSessionsSql() {
        return """
                SELECT s.SID AS session_id, s.USERNAME AS session_user, s.MACHINE AS session_host,
                       s.SCHEMANAME AS session_database, s.STATUS AS session_state, s.PROGRAM AS session_command,
                       s.LAST_CALL_ET AS duration_seconds, q.SQL_TEXT AS session_sql
                FROM V$SESSION s LEFT JOIN V$SQL q ON q.SQL_ID = s.SQL_ID
                -- 同 MySQL/PostgreSQL：排除工具自己这条会话，否则自动刷新会让它常驻在
                -- 「正在执行」的第一条。
                WHERE s.TYPE = 'USER' AND s.SID <> SYS_CONTEXT('USERENV', 'SID')
                ORDER BY s.LAST_CALL_ET DESC
                """;
    }

    @Override
    public boolean supportsKillSession() {
        return true;
    }

    @Override
    public String killSessionSql(String sessionId) {
        // Oracle 需要 SID,SERIAL#；这里只接受调用方传入的 "sid,serial" 组合。
        if (!sessionId.matches("\\d+,\\d+")) {
            throw new IllegalArgumentException("Oracle 会话标识需要 SID,SERIAL# 形式。");
        }
        return "ALTER SYSTEM KILL SESSION '" + sessionId + "' IMMEDIATE";
    }
}
