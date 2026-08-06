package com.example.dbadmin.core;

import com.example.dbadmin.dto.ApiDtos.DatabaseCapabilities;

import java.util.List;
import java.util.Locale;

public class SqlServerDialect extends DefaultDialect {
    @Override
    public boolean supports(String dbType, String jdbcUrl) {
        String type = dbType == null ? "" : dbType.toLowerCase(Locale.ROOT);
        String url = jdbcUrl == null ? "" : jdbcUrl.toLowerCase(Locale.ROOT);
        return type.equals("sqlserver") || url.startsWith("jdbc:sqlserver:");
    }

    @Override
    public DatabaseCapabilities capabilities() {
        return new DatabaseCapabilities(true, true, false, false, List.of(), List.of(), SchemaObjectCapabilities.sqlServer());
    }

    @Override
    public String pageQuery(String baseSql, int limit, int offset) {
        String normalized = baseSql.toLowerCase(Locale.ROOT);
        String ordered = normalized.matches("(?s).*\\border\\s+by\\b.*") ? baseSql : baseSql + " ORDER BY (SELECT NULL)";
        return ordered + " OFFSET " + offset + " ROWS FETCH NEXT " + limit + " ROWS ONLY";
    }

    @Override
    public String quoteIdentifier(String identifier) {
        return "[" + identifier.replace("]", "]]" ) + "]";
    }

    @Override
    public java.util.Optional<Long> approximateRowCount(java.sql.Connection connection, String schemaName, String tableName) throws java.sql.SQLException {
        String sql = "SELECT SUM(p.rows) FROM sys.partitions p INNER JOIN sys.objects o ON p.object_id = o.object_id " +
                     "INNER JOIN sys.schemas s ON o.schema_id = s.schema_id WHERE p.index_id IN (0, 1) AND s.name = ? AND o.name = ?";
        try (java.sql.PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, schemaName == null || schemaName.isBlank() ? "dbo" : schemaName);
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
}
