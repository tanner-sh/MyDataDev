package com.example.dbadmin.core;

import com.example.dbadmin.dto.ApiDtos.DatabaseCapabilities;
import com.example.dbadmin.dto.ApiDtos.ObjectDetail;

import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

public class PostgreSqlDialect extends DefaultDialect {


    /**
     * PostgreSQL 用 ON CONFLICT。更新档必须给出冲突目标（主键列），所以目标表没有主键时
     * 只能拒绝 —— 猜一个列去当冲突键，猜错就是把别人的数据覆盖掉。
     */
    @Override
    public ImportConflictStyle importConflictStyle(String mode, java.util.List<String> columns, java.util.List<String> keyColumns) {
        String normalized = mode == null ? "" : mode.toUpperCase(java.util.Locale.ROOT);
        return switch (normalized) {
            case "INSERT" -> ImportConflictStyle.plain();
            case "SKIP" -> new ImportConflictStyle("INSERT INTO", " ON CONFLICT DO NOTHING");
            case "UPSERT" -> {
                if (keyColumns == null || keyColumns.isEmpty()) yield null;
                String target = keyColumns.stream().map(this::quoteIdentifier)
                        .collect(java.util.stream.Collectors.joining(", "));
                String updates = columns.stream()
                        .filter(column -> keyColumns.stream().noneMatch(key -> key.equalsIgnoreCase(column)))
                        .map(column -> quoteIdentifier(column) + " = EXCLUDED." + quoteIdentifier(column))
                        .collect(java.util.stream.Collectors.joining(", "));
                // 除主键外没有别的列可更新时，「更新已存在」实际就等于「跳过」。
                yield updates.isBlank()
                        ? new ImportConflictStyle("INSERT INTO", " ON CONFLICT (" + target + ") DO NOTHING")
                        : new ImportConflictStyle("INSERT INTO", " ON CONFLICT (" + target + ") DO UPDATE SET " + updates);
            }
            default -> null;
        };
    }

    @Override
    public String castToText(String expression) {
        return "CAST(" + expression + " AS TEXT)";
    }

    @Override
    public boolean supports(String dbType, String jdbcUrl) {
        return "postgresql".equalsIgnoreCase(dbType)
                || (jdbcUrl != null && jdbcUrl.toLowerCase(Locale.ROOT).startsWith("jdbc:postgresql:"));
    }

    @Override
    public DatabaseCapabilities capabilities() {
        return new DatabaseCapabilities(true, true, true, true, List.of("PG_DUMP"), List.of("PG_RESTORE"), SchemaObjectCapabilities.postgresql());
    }

    @Override
    public void configureStreamingStatement(Connection connection, Statement statement, int fetchSize, int timeoutSeconds) throws java.sql.SQLException {
        if (connection.getAutoCommit()) connection.setAutoCommit(false);
        configureReadStatement(connection, statement, Math.max(fetchSize, 1), timeoutSeconds);
    }

    @Override
    public String scriptLiteral(Object value) {
        if (value instanceof byte[] bytes) return scriptBinaryLiteral(bytes);
        if (value instanceof CharSequence text && text.toString().indexOf('\\') >= 0) {
            return "E'" + text.toString().replace("\\", "\\\\").replace("'", "''") + "'";
        }
        return super.scriptLiteral(value);
    }

    @Override
    public String scriptBinaryLiteral(byte[] value) {
        return "decode('" + HexFormat.of().formatHex(value) + "', 'hex')";
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

    @Override
    public String activeSessionsSql() {
        return """
                SELECT pid AS session_id, usename AS session_user, client_addr AS session_host,
                       datname AS session_database, state AS session_state, backend_type AS session_command,
                       EXTRACT(EPOCH FROM (now() - query_start))::bigint AS duration_seconds, query AS session_sql
                FROM pg_stat_activity
                WHERE pid <> pg_backend_pid()
                ORDER BY query_start NULLS LAST
                """;
    }

    @Override
    public boolean supportsKillSession() {
        return true;
    }

    @Override
    public String killSessionSql(String sessionId) {
        return "SELECT pg_terminate_backend(" + Long.parseLong(sessionId) + ")";
    }
}
