package com.example.dbadmin.core;

import com.example.dbadmin.dto.ApiDtos.DatabaseCapabilities;

import java.util.List;
import java.util.Locale;

public class H2Dialect extends DefaultDialect {
    /** H2 默认把未加引号的标识符折成大写。 */
    @Override
    public String foldUnquotedIdentifier(String identifier) {
        return identifier == null ? null : identifier.toUpperCase(Locale.ROOT);
    }

    /** H2 only supports the DO NOTHING subset; UPSERT uses its native MERGE. */
    private final PostgreSqlDialect conflictStyles = new PostgreSqlDialect();

    @Override
    public ImportConflictStyle importConflictStyle(String mode, List<String> columns, List<String> keyColumns) {
        if ("UPSERT".equalsIgnoreCase(mode)) {
            if (keyColumns.isEmpty()) return null;
            return new ImportConflictStyle("MERGE INTO", "", "KEY (" + String.join(", ", keyColumns.stream().map(this::quoteIdentifier).toList()) + ") VALUES");
        }
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
