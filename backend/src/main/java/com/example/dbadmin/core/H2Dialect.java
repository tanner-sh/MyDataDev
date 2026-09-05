package com.example.dbadmin.core;

import com.example.dbadmin.dto.ApiDtos.DatabaseCapabilities;

import java.util.List;
import java.util.Locale;

public class H2Dialect extends DefaultDialect {
    /** H2 2.x 认 PostgreSQL 那套 ON CONFLICT，直接复用它的写法。 */
    private final PostgreSqlDialect conflictStyles = new PostgreSqlDialect();

    @Override
    public ImportConflictStyle importConflictStyle(String mode, List<String> columns, List<String> keyColumns) {
        return conflictStyles.importConflictStyle(mode, columns, keyColumns);
    }

    @Override
    public boolean supports(String dbType, String jdbcUrl) {
        return "h2".equalsIgnoreCase(dbType)
                || (jdbcUrl != null && jdbcUrl.toLowerCase(Locale.ROOT).startsWith("jdbc:h2:"));
    }

    @Override
    public DatabaseCapabilities capabilities() {
        return new DatabaseCapabilities(true, true, true, true, List.of(), List.of(), SchemaObjectCapabilities.h2());
    }
}
