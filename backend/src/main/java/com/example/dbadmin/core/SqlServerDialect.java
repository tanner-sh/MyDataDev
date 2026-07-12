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
        return new DatabaseCapabilities(true, true, false, false, List.of());
    }

    @Override
    public String pageQuery(String baseSql, int limit, int offset) {
        String normalized = baseSql.toLowerCase(Locale.ROOT);
        String ordered = normalized.contains(" order by ") ? baseSql : baseSql + " ORDER BY (SELECT NULL)";
        return ordered + " OFFSET " + offset + " ROWS FETCH NEXT " + limit + " ROWS ONLY";
    }

    @Override
    public String quoteIdentifier(String identifier) {
        return "[" + identifier.replace("]", "]]" ) + "]";
    }
}
