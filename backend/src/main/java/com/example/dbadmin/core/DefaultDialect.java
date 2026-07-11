package com.example.dbadmin.core;

import com.example.dbadmin.dto.ApiDtos.SqlResult;
import com.example.dbadmin.dto.ApiDtos.ColumnDesign;
import com.example.dbadmin.dto.ApiDtos.ColumnInfo;
import com.example.dbadmin.dto.ApiDtos.IndexDesign;
import com.example.dbadmin.dto.ApiDtos.IndexInfo;
import com.example.dbadmin.dto.ApiDtos.ObjectDetail;
import com.example.dbadmin.dto.ApiDtos.TableDesignRequest;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public class DefaultDialect implements DatabaseDialect {
    @Override
    public boolean supports(String dbType, String jdbcUrl) {
        return true;
    }

    @Override
    public String pageQuery(String baseSql, int limit, int offset) {
        return baseSql + " LIMIT " + limit + " OFFSET " + offset;
    }

    @Override
    public SqlResult explain(Connection connection, String sql, int maxRows, int timeoutSeconds) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(timeoutSeconds);
            statement.setMaxRows(maxRows + 1);
            try (ResultSet rs = statement.executeQuery("EXPLAIN " + sql)) {
                return readResult(rs, 0, maxRows);
            }
        }
    }

    @Override
    public List<String> alterTableSql(String schemaName, String tableName, ObjectDetail original, TableDesignRequest design) {
        List<String> sql = new ArrayList<>();
        String table = table(schemaName, tableName);
        Map<String, ColumnInfo> originalColumns = original.columns().stream()
                .collect(Collectors.toMap(column -> key(column.name()), column -> column, (left, right) -> left, LinkedHashMap::new));
        Set<String> requestedOriginals = design.columns() == null ? Set.of() : design.columns().stream()
                .map(ColumnDesign::originalName)
                .filter(name -> name != null && !name.isBlank())
                .map(this::key)
                .collect(Collectors.toSet());

        if (design.columns() != null) {
            for (ColumnDesign column : design.columns()) {
                String originalName = blankToNull(column.originalName());
                if (column.deleted()) {
                    if (originalName != null) {
                        sql.add("ALTER TABLE " + table + " DROP COLUMN " + quoteIdentifier(originalName));
                    }
                    continue;
                }
                if (originalName == null) {
                    sql.add(addColumnSql(table, column));
                    continue;
                }
                ColumnInfo originalColumn = originalColumns.get(key(originalName));
                if (originalColumn == null) {
                    continue;
                }
                String currentName = originalName;
                if (!originalName.equals(column.name())) {
                    sql.add(renameColumnSql(table, originalName, column));
                    currentName = column.name();
                }
                if (originalName.equals(column.name()) || !renameIncludesDefinition()) {
                    sql.addAll(alterColumnSql(table, currentName, originalColumn, column));
                }
            }
        }

        for (ColumnInfo originalColumn : original.columns()) {
            if (!requestedOriginals.contains(key(originalColumn.name()))) {
                sql.add("ALTER TABLE " + table + " DROP COLUMN " + quoteIdentifier(originalColumn.name()));
            }
        }

        sql.addAll(indexSql(table, original.indexes(), design.indexes()));
        sql.addAll(primaryKeySql(table, original, design.primaryKeys()));
        return sql;
    }

    protected List<String> alterColumnSql(String table, String columnName, ColumnInfo original, ColumnDesign column) {
        List<String> sql = new ArrayList<>();
        if (!sameType(original, column)) {
            sql.add("ALTER TABLE " + table + " ALTER COLUMN " + quoteIdentifier(columnName) + " TYPE " + type(column.type(), column.size()));
        }
        if (original.nullable() != column.nullable()) {
            sql.add("ALTER TABLE " + table + " ALTER COLUMN " + quoteIdentifier(columnName) + (column.nullable() ? " DROP NOT NULL" : " SET NOT NULL"));
        }
        if (!Objects.equals(normalizeDefault(original.defaultValue()), normalizeDefault(column.defaultValue()))) {
            sql.add("ALTER TABLE " + table + " ALTER COLUMN " + quoteIdentifier(columnName)
                    + (blankToNull(column.defaultValue()) == null ? " DROP DEFAULT" : " SET DEFAULT " + column.defaultValue().trim()));
        }
        return sql;
    }

    protected String addColumnSql(String table, ColumnDesign column) {
        return "ALTER TABLE " + table + " ADD COLUMN " + columnDefinition(column);
    }

    protected String renameColumnSql(String table, String originalName, ColumnDesign column) {
        return "ALTER TABLE " + table + " RENAME COLUMN " + quoteIdentifier(originalName) + " TO " + quoteIdentifier(column.name());
    }

    protected boolean renameIncludesDefinition() {
        return false;
    }

    protected List<String> primaryKeySql(String table, ObjectDetail original, List<String> requestedPrimaryKeys) {
        List<String> requested = requestedPrimaryKeys == null ? List.of() : requestedPrimaryKeys.stream().filter(name -> name != null && !name.isBlank()).toList();
        if (sameNames(original.primaryKeys(), requested)) {
            return List.of();
        }
        List<String> sql = new ArrayList<>();
        if (!original.primaryKeys().isEmpty()) {
            sql.add("ALTER TABLE " + table + " DROP PRIMARY KEY");
        }
        if (!requested.isEmpty()) {
            sql.add("ALTER TABLE " + table + " ADD PRIMARY KEY (" + requested.stream().map(this::quoteIdentifier).collect(Collectors.joining(", ")) + ")");
        }
        return sql;
    }

    protected List<String> indexSql(String table, List<IndexInfo> originalIndexes, List<IndexDesign> requestedIndexes) {
        List<IndexDesign> requested = requestedIndexes == null ? List.of() : requestedIndexes;
        List<String> sql = new ArrayList<>();
        Set<String> requestedOriginals = requested.stream()
                .map(IndexDesign::originalName)
                .filter(name -> name != null && !name.isBlank())
                .map(this::key)
                .collect(Collectors.toSet());
        Map<String, String> originalNames = originalIndexes.stream()
                .filter(index -> index.name() != null && !index.name().isBlank())
                .collect(Collectors.toMap(index -> key(index.name()), IndexInfo::name, (left, right) -> left, LinkedHashMap::new));
        for (Map.Entry<String, String> original : originalNames.entrySet()) {
            boolean explicitlyDeleted = requested.stream().anyMatch(index -> index.deleted() && original.getKey().equals(key(index.originalName())));
            if (explicitlyDeleted || !requestedOriginals.contains(original.getKey())) {
                sql.add(dropIndexSql(table, original.getValue()));
            }
        }
        for (IndexDesign index : requested) {
            if (index.deleted()) {
                continue;
            }
            String originalName = blankToNull(index.originalName());
            boolean isNew = originalName == null || !originalNames.containsKey(key(originalName));
            boolean renamed = originalName != null && !originalName.equals(index.name());
            if (isNew || renamed) {
                if (renamed) {
                    sql.add(dropIndexSql(table, originalName));
                }
                sql.add("CREATE " + (index.unique() ? "UNIQUE " : "") + "INDEX " + quoteIdentifier(index.name()) + " ON " + table
                        + " (" + index.columns().stream().map(this::quoteIdentifier).collect(Collectors.joining(", ")) + ")");
            }
        }
        return sql;
    }

    protected String dropIndexSql(String table, String indexName) {
        return "DROP INDEX " + quoteIdentifier(indexName);
    }

    protected String columnDefinition(ColumnDesign column) {
        return quoteIdentifier(column.name()) + " " + type(column.type(), column.size())
                + (blankToNull(column.defaultValue()) == null ? "" : " DEFAULT " + column.defaultValue().trim())
                + (column.nullable() ? "" : " NOT NULL");
    }

    protected String table(String schemaName, String tableName) {
        return qualifiedName(schemaName, tableName);
    }

    protected String type(String type, Integer size) {
        String normalized = type == null || type.isBlank() ? "VARCHAR" : type.trim();
        if (size != null && size > 0 && !normalized.contains("(")) {
            String upper = normalized.toUpperCase(Locale.ROOT);
            if (upper.contains("CHAR") || upper.contains("BINARY")) {
                return normalized + "(" + size + ")";
            }
        }
        return normalized;
    }

    protected boolean sameType(ColumnInfo original, ColumnDesign requested) {
        return type(original.type(), original.size()).equalsIgnoreCase(type(requested.type(), requested.size()));
    }

    protected boolean sameNames(List<String> left, List<String> right) {
        if (left.size() != right.size()) {
            return false;
        }
        for (int i = 0; i < left.size(); i++) {
            if (!key(left.get(i)).equals(key(right.get(i)))) {
                return false;
            }
        }
        return true;
    }

    protected String normalizeDefault(String value) {
        String normalized = blankToNull(value);
        return normalized == null ? null : normalized.trim();
    }

    protected String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    protected String key(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    protected SqlResult readResult(ResultSet rs, long elapsedMs, int maxRows) throws Exception {
        ResultSetMetaData md = rs.getMetaData();
        List<String> columns = new ArrayList<>();
        for (int i = 1; i <= md.getColumnCount(); i++) {
            columns.add(md.getColumnLabel(i));
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        while (rows.size() < maxRows && rs.next()) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 1; i <= md.getColumnCount(); i++) {
                row.put(columns.get(i - 1), rs.getObject(i));
            }
            rows.add(row);
        }
        boolean truncated = rows.size() == maxRows && rs.next();
        return new SqlResult(columns, rows, -1, elapsedMs, true, maxRows, truncated);
    }
}
