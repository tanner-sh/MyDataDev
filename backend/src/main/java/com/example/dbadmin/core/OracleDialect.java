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
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public class OracleDialect extends DefaultDialect {
    // Oracle-compatible unquoted identifiers must start with a letter. Keep this
    // helper unquoted so ResultSet metadata is consistent across Oracle and
    // OceanBase Oracle mode.
    private static final String PAGE_ROW_COLUMN = "DBADMIN_PAGE_RN";

    @Override
    public DatabaseCapabilities capabilities() {
        return new DatabaseCapabilities(true, true, true, true, List.of("ORACLE_EXP"));
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
        StringBuilder sql = new StringBuilder("SELECT DBMS_METADATA.GET_DDL('")
                .append(ddlType)
                .append("', '")
                .append(objectName.replace("'", "''"))
                .append("'");
        if (schemaName != null && !schemaName.isBlank()) {
            sql.append(", '").append(schemaName.replace("'", "''")).append("'");
        }
        sql.append(") FROM DUAL");
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql.toString())) {
            if (rs.next()) {
                return Optional.ofNullable(rs.getString(1));
            }
        }
        return Optional.empty();
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
}
