package com.example.dbadmin.core;

import com.example.dbadmin.dto.ApiDtos.DatabaseCapabilities;
import com.example.dbadmin.dto.ApiDtos.ObjectDetail;

import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class PostgreSqlDialect extends DefaultDialect {
    @Override
    public boolean supports(String dbType, String jdbcUrl) {
        return "postgresql".equalsIgnoreCase(dbType)
                || (jdbcUrl != null && jdbcUrl.toLowerCase(Locale.ROOT).startsWith("jdbc:postgresql:"));
    }

    @Override
    public DatabaseCapabilities capabilities() {
        return new DatabaseCapabilities(true, true, true, true, List.of(), List.of(), SchemaObjectCapabilities.postgresql());
    }

    @Override
    public void configureStreamingStatement(Connection connection, Statement statement, int fetchSize, int timeoutSeconds) throws java.sql.SQLException {
        if (connection.getAutoCommit()) connection.setAutoCommit(false);
        configureReadStatement(connection, statement, Math.max(fetchSize, 1), timeoutSeconds);
    }

    @Override
    public java.util.Optional<Long> approximateRowCount(Connection connection, String schemaName, String tableName) throws java.sql.SQLException {
        String sql = "SELECT n_live_tup FROM pg_stat_user_tables WHERE schemaname = ? AND relname = ?";
        try (java.sql.PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, schemaName == null || schemaName.isBlank() ? "public" : schemaName);
            statement.setString(2, tableName);
            try (java.sql.ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    long rows = rs.getLong(1);
                    return rs.wasNull() ? java.util.Optional.empty() : java.util.Optional.of(rows);
                }
                return java.util.Optional.empty();
            }
        }
    }

    @Override
    protected List<String> primaryKeySql(String table, ObjectDetail original, List<String> requestedPrimaryKeys) {
        List<String> requested = requestedPrimaryKeys == null
                ? List.of()
                : requestedPrimaryKeys.stream().filter(name -> name != null && !name.isBlank()).toList();
        if (sameNames(original.primaryKeys(), requested)) return List.of();
        List<String> sql = new ArrayList<>();
        if (!original.primaryKeys().isEmpty()) {
            if (original.primaryKeyName() == null || original.primaryKeyName().isBlank()) {
                throw new IllegalArgumentException("PostgreSQL 主键变更需要可识别的主键约束名。");
            }
            sql.add("ALTER TABLE " + table + " DROP CONSTRAINT " + quoteIdentifier(original.primaryKeyName()));
        }
        if (!requested.isEmpty()) {
            sql.add("ALTER TABLE " + table + " ADD PRIMARY KEY (" + String.join(", ", requested.stream().map(this::quoteIdentifier).toList()) + ")");
        }
        return sql;
    }
}
