package com.example.dbadmin.core;

import com.example.dbadmin.dto.ApiDtos.SqlResult;
import com.example.dbadmin.dto.ApiDtos.ColumnDesign;
import com.example.dbadmin.dto.ApiDtos.ColumnInfo;
import com.example.dbadmin.dto.ApiDtos.ObjectDetail;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class OracleDialect extends DefaultDialect {
    @Override
    public boolean supports(String dbType, String jdbcUrl) {
        return "oracle".equalsIgnoreCase(dbType)
                || (jdbcUrl != null && jdbcUrl.toLowerCase().startsWith("jdbc:oracle:"));
    }

    @Override
    public String pageQuery(String baseSql, int limit, int offset) {
        int upperBound = offset + limit;
        return "SELECT * FROM (SELECT inner_query.*, ROWNUM dbadmin_rn FROM (" + baseSql
                + ") inner_query WHERE ROWNUM <= " + upperBound + ") WHERE dbadmin_rn > " + offset;
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
            display.setMaxRows(maxRows);
            try (ResultSet rs = display.executeQuery("SELECT PLAN_TABLE_OUTPUT FROM TABLE(DBMS_XPLAN.DISPLAY())")) {
                return readResult(rs, (System.nanoTime() - started) / 1_000_000);
            }
        }
    }

    @Override
    public Optional<String> nativeDdl(Connection connection, String schemaName, String objectName, String objectType) throws Exception {
        String ddlType = objectType != null && objectType.toUpperCase().contains("VIEW") ? "VIEW" : "TABLE";
        StringBuilder sql = new StringBuilder("SELECT DBMS_METADATA.GET_DDL('")
                .append(ddlType)
                .append("', '")
                .append(objectName.replace("'", "''").toUpperCase())
                .append("'");
        if (schemaName != null && !schemaName.isBlank()) {
            sql.append(", '").append(schemaName.replace("'", "''").toUpperCase()).append("'");
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
}
