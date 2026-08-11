package com.example.dbadmin.service;

import com.example.dbadmin.core.DatabaseDialect;
import com.example.dbadmin.dto.ApiDtos.ResultSourceTable;

import java.sql.ResultSetMetaData;
import java.util.List;
import java.util.Locale;

final class ResultSetSourceResolver {
    private ResultSetSourceResolver() {
    }

    static ResultSourceTable resolve(ResultSetMetaData metadata, DatabaseDialect dialect) {
        String tableName = null;
        String normalizedTable = null;
        String namespaceName = null;
        String normalizedNamespace = null;
        try {
            for (int index = 1; index <= metadata.getColumnCount(); index++) {
                String candidateTable = trimToNull(metadata.getTableName(index));
                if (candidateTable == null) continue;
                String candidateTableKey = candidateTable.toLowerCase(Locale.ROOT);
                if (normalizedTable != null && !normalizedTable.equals(candidateTableKey)) return null;
                if (tableName == null) {
                    tableName = candidateTable;
                    normalizedTable = candidateTableKey;
                }

                String candidateNamespace = trimToNull(dialect.namespaceKind() == DatabaseDialect.NamespaceKind.CATALOG
                        ? metadata.getCatalogName(index)
                        : metadata.getSchemaName(index));
                if (candidateNamespace == null) continue;
                String candidateNamespaceKey = candidateNamespace.toLowerCase(Locale.ROOT);
                if (normalizedNamespace != null && !normalizedNamespace.equals(candidateNamespaceKey)) return null;
                if (namespaceName == null) {
                    namespaceName = candidateNamespace;
                    normalizedNamespace = candidateNamespaceKey;
                }
            }
        } catch (Exception ignored) {
            return null;
        }
        if (tableName == null) return null;
        return new ResultSourceTable(namespaceName == null ? List.of(tableName) : List.of(namespaceName, tableName));
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
    }
}
