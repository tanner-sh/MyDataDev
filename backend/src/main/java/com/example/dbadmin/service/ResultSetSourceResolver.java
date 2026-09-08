package com.example.dbadmin.service;

import com.example.dbadmin.core.DatabaseDialect;
import com.example.dbadmin.dto.ApiDtos.ResultSourceTable;

import java.sql.ResultSetMetaData;
import java.util.List;
import java.util.Locale;

final class ResultSetSourceResolver {
    /**
     * 来源定不下来的两种原因。给用户的说明必须分清它们。
     *
     * <p>Oracle 的 ojdbc 对每一列的 {@code getTableName()} 都返回空串 —— 它压根不带这份元数据回来。
     * 于是一条再普通不过的 {@code select * from T where ...} 也解析不出来源表，此前界面给的说明是
     * 「查询结果不是来自单张表」：一句与事实相反的话，用户会去 SQL 里找那个并不存在的联表。</p>
     */
    enum UnknownSource {
        /** 驱动一列都没报表名。 */
        NO_TABLE_NAMES,
        /** 各列报的表名对不上，确实来自多张表。 */
        MULTIPLE_TABLES
    }

    private ResultSetSourceResolver() {
    }

    static UnknownSource classifyUnknownSource(ResultSetMetaData metadata, int columnCount) {
        try {
            for (int index = 1; index <= columnCount; index++) {
                if (trimToNull(metadata.getTableName(index)) != null) return UnknownSource.MULTIPLE_TABLES;
            }
        } catch (Exception ignored) {
            return UnknownSource.NO_TABLE_NAMES;
        }
        return UnknownSource.NO_TABLE_NAMES;
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
