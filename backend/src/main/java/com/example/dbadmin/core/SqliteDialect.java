package com.example.dbadmin.core;

import com.example.dbadmin.dto.ApiDtos.DatabaseCapabilities;
import com.example.dbadmin.dto.ApiDtos.SqlResult;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;

public class SqliteDialect extends DefaultDialect {
    @Override
    public boolean supports(String dbType, String jdbcUrl) {
        String type = dbType == null ? "" : dbType.toLowerCase(Locale.ROOT);
        String url = jdbcUrl == null ? "" : jdbcUrl.toLowerCase(Locale.ROOT);
        return type.equals("sqlite") || url.startsWith("jdbc:sqlite:");
    }

    @Override
    public DatabaseCapabilities capabilities() {
        return new DatabaseCapabilities(true, true, false, true, List.of());
    }

    @Override
    public SqlResult explain(Connection connection, String sql, int maxRows, int timeoutSeconds) throws Exception {
        long started = System.nanoTime();
        try (Statement statement = connection.createStatement()) {
            configureReadStatement(connection, statement, Math.min(maxRows + 1, 200), timeoutSeconds);
            try (ResultSet rs = statement.executeQuery("EXPLAIN QUERY PLAN " + sql)) {
                return readResult(rs, (System.nanoTime() - started) / 1_000_000, maxRows);
            }
        }
    }
}
