package com.example.dbadmin.core;

import com.example.dbadmin.dto.ApiDtos.SqlResult;
import com.example.dbadmin.dto.ApiDtos.DatabaseCapabilities;
import com.example.dbadmin.dto.ApiDtos.ResultColumn;
import com.example.dbadmin.dto.ApiDtos.ColumnDesign;
import com.example.dbadmin.dto.ApiDtos.ColumnInfo;
import com.example.dbadmin.dto.ApiDtos.IndexDesign;
import com.example.dbadmin.dto.ApiDtos.IndexInfo;
import com.example.dbadmin.dto.ApiDtos.ObjectDetail;
import com.example.dbadmin.dto.ApiDtos.TableDesignRequest;

import java.sql.Connection;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.math.BigDecimal;
import java.math.BigInteger;

public class DefaultDialect implements DatabaseDialect {
    private static final int MAX_EXPLAIN_RESULT_CELLS = 50_000;
    private static final long MAX_EXPLAIN_TEXT_CHARS = 5_000_000;
    private static final int MAX_EXPLAIN_CELL_TEXT_CHARS = 100_000;
    @Override
    public DatabaseCapabilities capabilities() {
        return new DatabaseCapabilities(true, false, false, false, List.of());
    }

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
        long started = System.nanoTime();
        try (Statement statement = connection.createStatement()) {
            configureReadStatement(connection, statement, Math.min(maxRows + 1, 200), timeoutSeconds);
            statement.setMaxRows(maxRows + 1);
            try (ResultSet rs = statement.executeQuery("EXPLAIN " + sql)) {
                return readResult(rs, (System.nanoTime() - started) / 1_000_000, maxRows);
            }
        }
    }

    @Override
    public List<String> alterTableSql(String schemaName, String tableName, ObjectDetail original, TableDesignRequest design) {
        List<String> columnSql = new ArrayList<>();
        String table = table(schemaName, tableName);
        Map<String, ColumnInfo> originalColumns = original.columns().stream()
                .collect(Collectors.toMap(ColumnInfo::name, column -> column, (left, right) -> {
                    throw new IllegalArgumentException("数据库返回了重复字段名：" + left.name());
                }, LinkedHashMap::new));
        Set<String> requestedOriginals = new LinkedHashSet<>();

        if (design.columns() != null) {
            for (ColumnDesign column : design.columns()) {
                String submittedOriginalName = blankToNull(column.originalName());
                ColumnInfo originalColumn = submittedOriginalName == null
                        ? null
                        : resolveExactOrUniqueFolded(originalColumns, submittedOriginalName, "原字段");
                if (submittedOriginalName != null && originalColumn == null) {
                    throw new IllegalArgumentException("表结构已发生变化，原字段不存在：" + submittedOriginalName + "。请刷新对象后重新设计。");
                }
                String originalName = originalColumn == null ? null : originalColumn.name();
                if (originalName != null && !requestedOriginals.add(originalName)) {
                    throw new IllegalArgumentException("同一个原字段不能重复编辑：" + originalName);
                }
                if (column.deleted()) {
                    if (originalName != null) {
                        columnSql.add("ALTER TABLE " + table + " DROP COLUMN " + quoteIdentifier(originalName));
                    }
                    continue;
                }
                if (originalName == null) {
                    columnSql.add(addColumnSql(table, column));
                    continue;
                }
                String currentName = originalName;
                if (!originalName.equals(column.name())) {
                    columnSql.add(renameColumnSql(table, originalName, column));
                    currentName = column.name();
                }
                if (originalName.equals(column.name()) || !renameIncludesDefinition()) {
                    columnSql.addAll(alterColumnSql(table, currentName, originalColumn, column));
                }
            }
        }

        for (ColumnInfo originalColumn : original.columns()) {
            if (!requestedOriginals.contains(originalColumn.name())) {
                throw new IllegalArgumentException("表结构已发生变化，检测到设计器未加载的字段：" + originalColumn.name() + "。请刷新对象后重新设计；删除字段必须显式标记。");
            }
        }

        List<String> indexSql = indexSql(table, editableIndexes(original), design.indexes());
        List<String> primaryKeySql = primaryKeySql(table, original, design.primaryKeys());
        List<String> sql = new ArrayList<>();
        primaryKeySql.stream().filter(this::isDropStatement).forEach(sql::add);
        indexSql.stream().filter(this::isDropStatement).forEach(sql::add);
        sql.addAll(columnSql);
        indexSql.stream().filter(line -> !isDropStatement(line)).forEach(sql::add);
        primaryKeySql.stream().filter(line -> !isDropStatement(line)).forEach(sql::add);
        return sql;
    }

    private boolean isDropStatement(String sql) {
        return sql != null && sql.stripLeading().toUpperCase(Locale.ROOT).contains(" DROP ")
                || sql != null && sql.stripLeading().toUpperCase(Locale.ROOT).startsWith("DROP ");
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
        Map<String, OriginalIndex> originalNames = groupedIndexes(originalIndexes);
        Map<IndexDesign, OriginalIndex> resolvedOriginals = new java.util.IdentityHashMap<>();
        Set<String> requestedOriginalNames = new LinkedHashSet<>();
        for (IndexDesign index : requested) {
            String submittedOriginalName = blankToNull(index.originalName());
            if (submittedOriginalName == null) continue;
            OriginalIndex original = resolveExactOrUniqueFolded(originalNames, submittedOriginalName, "原索引");
            if (original == null) {
                throw new IllegalArgumentException("表结构已发生变化，原索引不存在：" + submittedOriginalName + "。请刷新对象后重新设计。");
            }
            if (!requestedOriginalNames.add(original.name())) {
                throw new IllegalArgumentException("同一个原索引不能重复编辑：" + original.name());
            }
            resolvedOriginals.put(index, original);
        }
        for (OriginalIndex original : originalNames.values()) {
            if (!requestedOriginalNames.contains(original.name())) {
                throw new IllegalArgumentException("表结构已发生变化，检测到设计器未加载的索引：" + original.name() + "。请刷新对象后重新设计；删除索引必须显式标记。");
            }
        }
        for (IndexDesign index : requested) {
            OriginalIndex original = resolvedOriginals.get(index);
            if (index.deleted()) {
                if (original != null) sql.add(dropIndexSql(table, original.name()));
                continue;
            }
            boolean isNew = original == null;
            boolean renamed = original != null && !original.name().equals(index.name());
            boolean definitionChanged = original != null
                    && (original.unique() != index.unique() || !sameNames(original.columns(), index.columns()));
            if (isNew || renamed || definitionChanged) {
                if (!isNew) {
                    sql.add(dropIndexSql(table, original.name()));
                }
                sql.add("CREATE " + (index.unique() ? "UNIQUE " : "") + "INDEX " + quoteIdentifier(index.name()) + " ON " + table
                        + " (" + index.columns().stream().map(this::quoteIdentifier).collect(Collectors.joining(", ")) + ")");
            }
        }
        return sql;
    }

    private List<IndexInfo> editableIndexes(ObjectDetail original) {
        Map<String, OriginalIndex> grouped = groupedIndexes(original.indexes());
        Set<String> primaryIndexNames = grouped.entrySet().stream()
                .filter(entry -> isPrimaryBackingIndex(original, entry.getValue()))
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
        if (primaryIndexNames.isEmpty()) return original.indexes();
        return original.indexes().stream()
                .filter(index -> index.name() == null || !primaryIndexNames.contains(index.name()))
                .toList();
    }

    private boolean isPrimaryBackingIndex(ObjectDetail original, OriginalIndex index) {
        if (original.primaryKeys().isEmpty()) return false;
        String primaryKeyName = blankToNull(original.primaryKeyName());
        return primaryKeyName != null && primaryKeyName.equals(index.name())
                || index.unique() && sameNames(index.columns(), original.primaryKeys());
    }

    private Map<String, OriginalIndex> groupedIndexes(List<IndexInfo> indexes) {
        Map<String, List<IndexInfo>> grouped = indexes.stream()
                .filter(index -> index.name() != null && !index.name().isBlank())
                .collect(Collectors.groupingBy(IndexInfo::name, LinkedHashMap::new, Collectors.toList()));
        Map<String, OriginalIndex> result = new LinkedHashMap<>();
        for (Map.Entry<String, List<IndexInfo>> entry : grouped.entrySet()) {
            List<IndexInfo> rows = entry.getValue().stream()
                    .sorted(java.util.Comparator.comparingInt(IndexInfo::ordinalPosition))
                    .toList();
            result.put(entry.getKey(), new OriginalIndex(
                    rows.get(0).name(),
                    rows.stream().map(IndexInfo::columnName).toList(),
                    rows.get(0).unique()
            ));
        }
        return result;
    }

    protected String dropIndexSql(String table, String indexName) {
        int namespaceSeparator = table.lastIndexOf('.');
        String namespace = namespaceSeparator < 0 ? "" : table.substring(0, namespaceSeparator + 1);
        return "DROP INDEX " + namespace + quoteIdentifier(indexName);
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
            if (!Objects.equals(left.get(i), right.get(i))) {
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

    private <T> T resolveExactOrUniqueFolded(Map<String, T> values, String requestedName, String label) {
        T exact = values.get(requestedName);
        if (exact != null) return exact;
        List<Map.Entry<String, T>> folded = values.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(requestedName))
                .toList();
        if (folded.size() == 1) return folded.get(0).getValue();
        if (folded.size() > 1) {
            throw new IllegalArgumentException(label + "名称大小写不明确，请使用数据库返回的精确名称：" + requestedName);
        }
        return null;
    }

    protected SqlResult readResult(ResultSet rs, long elapsedMs, int maxRows) throws Exception {
        ResultSetMetaData md = rs.getMetaData();
        List<ResultColumn> columns = new ArrayList<>();
        for (int i = 1; i <= md.getColumnCount(); i++) {
            columns.add(new ResultColumn("c" + i, md.getColumnLabel(i), md.getColumnTypeName(i)));
        }
        int effectiveMaxRows = Math.min(Math.max(maxRows, 0), MAX_EXPLAIN_RESULT_CELLS / Math.max(md.getColumnCount(), 1));
        List<List<Object>> rows = new ArrayList<>();
        long textChars = 0;
        boolean payloadLimitReached = false;
        while (rows.size() < effectiveMaxRows && rs.next()) {
            List<Object> row = new ArrayList<>(md.getColumnCount());
            for (int i = 1; i <= md.getColumnCount(); i++) {
                int remainingText = (int) Math.min(MAX_EXPLAIN_CELL_TEXT_CHARS, Math.max(0, MAX_EXPLAIN_TEXT_CHARS - textChars));
                Object value = explainValue(rs.getObject(i), remainingText);
                row.add(value);
                if (value instanceof CharSequence text) textChars += text.length();
            }
            rows.add(row);
            if (textChars >= MAX_EXPLAIN_TEXT_CHARS) {
                payloadLimitReached = true;
                break;
            }
        }
        boolean truncated = payloadLimitReached || rs.next();
        return new SqlResult(columns, rows, -1, elapsedMs, true, effectiveMaxRows, truncated);
    }

    private Object explainValue(Object value, int maxTextChars) throws Exception {
        if (value == null) return null;
        if (value instanceof Clob clob) {
            long length = clob.length();
            int visible = (int) Math.min(length, Math.min(MAX_EXPLAIN_CELL_TEXT_CHARS, Math.max(maxTextChars, 0)));
            String text = visible == 0 ? "" : clob.getSubString(1, visible);
            return length > visible ? truncateText(text, "… <CLOB 已截断，共 " + length + " 字符>", maxTextChars) : text;
        }
        if (value instanceof Blob blob) return truncateText("<BLOB " + blob.length() + " bytes>", "", maxTextChars);
        if (value instanceof byte[] bytes) return truncateText("<BINARY " + bytes.length + " bytes>", "", maxTextChars);
        if (value instanceof Long || value instanceof BigInteger || value instanceof BigDecimal) {
            return truncateText(value.toString(), "", maxTextChars);
        }
        if (value instanceof CharSequence text) {
            String string = text.toString();
            return string.length() > maxTextChars
                    ? truncateText(string, "… <文本已截断，共 " + string.length() + " 字符>", maxTextChars)
                    : string;
        }
        if (value instanceof Float number && !Float.isFinite(number)) return number.toString();
        if (value instanceof Double number && !Double.isFinite(number)) return number.toString();
        if (value instanceof Number || value instanceof Boolean) return value;
        return truncateText(value.toString(), "", maxTextChars);
    }

    private String truncateText(String prefixSource, String marker, int maxChars) {
        if (maxChars <= 0) return "";
        if (prefixSource.length() <= maxChars && marker.isEmpty()) return prefixSource;
        if (marker.length() >= maxChars) return prefixSource.substring(0, Math.min(prefixSource.length(), maxChars));
        int prefixLength = Math.min(prefixSource.length(), maxChars - marker.length());
        return prefixSource.substring(0, prefixLength) + marker;
    }

    private record OriginalIndex(String name, List<String> columns, boolean unique) {
    }
}
