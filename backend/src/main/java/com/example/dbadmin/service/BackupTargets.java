package com.example.dbadmin.service;

import com.example.dbadmin.core.DatabaseDialect;
import com.example.dbadmin.core.DialectRegistry;
import com.example.dbadmin.model.BackupTask;
import com.example.dbadmin.model.DbConnection;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 备份目标：把任务上的「范围 + Schema + 表名」解析成数据库里真实存在的物理表。
 *
 * <p>从 BackupService 拆出来。保存任务时（校验目标存在、名字折成数据库返回的精确大小写）与执行时
 * （SQL 备份要逐表写、原生工具要拼参数）走的是同一份解析，所以单独放一处。</p>
 */
final class BackupTargets {
    private final ConnectionService connections;
    private final DialectRegistry dialectRegistry;

    BackupTargets(ConnectionService connections, DialectRegistry dialectRegistry) {
        this.connections = connections;
        this.dialectRegistry = dialectRegistry;
    }

    static List<String> requestedTableNames(List<String> tableNames, String legacyTableName) {
        List<String> requested = tableNames == null ? new ArrayList<>() : new ArrayList<>(tableNames);
        String legacy = blankToNull(legacyTableName);
        if (requested.isEmpty() && legacy != null) {
            requested.add(legacy);
        } else if (!requested.isEmpty() && legacy != null) {
            String first = blankToNull(requested.get(0));
            if (!legacy.equals(first)) {
                throw new IllegalArgumentException("tableName 必须与 tableNames 的第一项一致。");
            }
        }
        if (requested.size() > 100) {
            throw new IllegalArgumentException("一次最多选择 100 张表。");
        }
        Set<String> unique = new HashSet<>();
        List<String> normalized = new ArrayList<>(requested.size());
        for (String value : requested) {
            String name = blankToNull(value);
            if (name == null) {
                throw new IllegalArgumentException("表名不能为空。");
            }
            if (!unique.add(name)) {
                throw new IllegalArgumentException("不能重复选择表：" + name);
            }
            normalized.add(name);
        }
        return List.copyOf(normalized);
    }

    static void validateRequestTargetShape(String rawScope, String schemaName, List<String> tableNames) {
        String schema = blankToNull(schemaName);
        switch (rawScope) {
            case "DATABASE" -> {
                if (schema != null) {
                    throw new IllegalArgumentException("全库备份不能指定 Schema/数据库。");
                }
                if (!tableNames.isEmpty()) {
                    throw new IllegalArgumentException("全库备份不能指定表。");
                }
            }
            case "SCHEMA" -> {
                if (schema == null) {
                    throw new IllegalArgumentException("请选择 Schema/数据库。");
                }
                if (!tableNames.isEmpty()) {
                    throw new IllegalArgumentException("Schema/数据库备份不能指定表。");
                }
            }
            case "TABLES" -> {
                if (schema == null) {
                    throw new IllegalArgumentException("请选择 Schema/数据库。");
                }
            }
            case "TABLE" -> {
                // Legacy single-table tasks may omit the namespace; resolve it from the connection.
            }
            default -> {
                // validateScope reports unsupported values.
            }
        }
    }

    static String validateScope(String scope, List<String> tableNames) {
        String normalized = normalizeScope(scope);
        if (!Set.of("DATABASE", "SCHEMA", "TABLES").contains(normalized)) {
            throw new IllegalArgumentException("不支持的备份范围：" + scope);
        }
        if ("TABLES".equals(normalized) && tableNames.isEmpty()) {
            throw new IllegalArgumentException("表备份至少需要选择一张表。");
        }
        return normalized;
    }

    private static String normalizeScope(String scope) {
        String normalized = scope == null ? "" : scope.toUpperCase(Locale.ROOT);
        return "TABLE".equals(normalized) ? "TABLES" : normalized;
    }

    ResolvedTarget resolveRequestedTarget(DbConnection dbConnection, String scope, String schemaName, List<String> tableNames) throws Exception {
        if ("DATABASE".equals(scope)) {
            return new ResolvedTarget(scope, null, List.of());
        }
        try (Connection connection = connections.open(dbConnection.id())) {
            DatabaseDialect dialect = dialectRegistry.dialectFor(dbConnection);
            if ("SCHEMA".equals(scope)) {
                String requestedNamespace = blankToNull(schemaName);
                if (requestedNamespace == null) requestedNamespace = blankToNull(dialect.currentSchema(connection));
                if (requestedNamespace == null) {
                    throw new IllegalArgumentException("无法确定当前 Schema/数据库，请重新选择备份目标。");
                }
                return new ResolvedTarget(scope, resolveNamespace(connection, dialect, requestedNamespace), List.of());
            }
            return resolveTarget(connection, dialect, scope, schemaName, tableNames);
        }
    }

    ResolvedTarget resolveTaskTarget(BackupTask task, DbConnection dbConnection) throws Exception {
        String scope = normalizeScope(task.scope());
        if ("DATABASE".equals(scope)) {
            return new ResolvedTarget(scope, null, List.of());
        }
        try (Connection connection = connections.open(dbConnection.id())) {
            return resolveTarget(connection, dialectRegistry.dialectFor(dbConnection), task.scope(), task.schemaName(), task.tableNames());
        }
    }

    ResolvedTarget resolveTarget(Connection connection, DatabaseDialect dialect, String rawScope, String schemaName, List<String> rawTableNames) throws Exception {
        List<String> tableNames = requestedTableNames(rawTableNames, null);
        String scope = validateScope(rawScope, tableNames);
        if ("DATABASE".equals(scope)) {
            if (dialect.namespaceKind() == DatabaseDialect.NamespaceKind.CATALOG) {
                String currentNamespace = blankToNull(dialect.currentSchema(connection));
                if (currentNamespace != null) currentNamespace = resolveNamespace(connection, dialect, currentNamespace);
                return new ResolvedTarget(scope, currentNamespace, physicalTables(connection, dialect, currentNamespace));
            }
            List<TableRef> allTables = new ArrayList<>();
            for (String namespace : availableNamespaces(connection, dialect)) {
                allTables.addAll(physicalTables(connection, dialect, namespace));
            }
            return new ResolvedTarget(scope, null, List.copyOf(allTables));
        }
        String requestedNamespace = blankToNull(schemaName);
        if (requestedNamespace == null) {
            requestedNamespace = blankToNull(dialect.currentSchema(connection));
        }
        if (requestedNamespace == null) {
            throw new IllegalArgumentException("无法确定当前 Schema/数据库，请重新选择备份目标。");
        }
        String namespace = resolveNamespace(connection, dialect, requestedNamespace);
        List<TableRef> availableTables = physicalTables(connection, dialect, namespace);
        if ("SCHEMA".equals(scope)) {
            return new ResolvedTarget(scope, namespace, availableTables);
        }
        Map<String, TableRef> exactTables = new LinkedHashMap<>();
        Map<String, List<TableRef>> foldedTables = new LinkedHashMap<>();
        for (TableRef table : availableTables) {
            exactTables.putIfAbsent(table.name(), table);
            foldedTables.computeIfAbsent(table.name().toLowerCase(Locale.ROOT), ignored -> new ArrayList<>()).add(table);
        }
        List<TableRef> selected = new ArrayList<>(tableNames.size());
        List<String> missing = new ArrayList<>();
        List<String> ambiguous = new ArrayList<>();
        Set<String> selectedActualNames = new HashSet<>();
        for (String tableName : tableNames) {
            TableRef table = exactTables.get(tableName);
            if (table == null) {
                List<TableRef> folded = foldedTables.getOrDefault(tableName.toLowerCase(Locale.ROOT), List.of());
                if (folded.size() == 1) {
                    table = folded.get(0);
                } else if (folded.size() > 1) {
                    ambiguous.add(tableName);
                    continue;
                }
            }
            if (table == null) {
                missing.add(tableName);
            } else if (!selectedActualNames.add(table.name())) {
                throw new IllegalArgumentException("多个目标解析到了同一张表：" + table.name());
            } else {
                selected.add(table);
            }
        }
        if (!ambiguous.isEmpty()) {
            throw new IllegalArgumentException("以下表名大小写不明确，请使用数据库返回的精确名称：" + String.join(", ", ambiguous));
        }
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("以下表不存在或不是物理表：" + String.join(", ", missing));
        }
        return new ResolvedTarget(scope, namespace, List.copyOf(selected));
    }

    private String resolveNamespace(Connection connection, DatabaseDialect dialect, String requestedNamespace) throws Exception {
        List<String> available = availableNamespaces(connection, dialect);
        if (available.contains(requestedNamespace)) {
            return requestedNamespace;
        }
        List<String> folded = available.stream().filter(namespace -> namespace.equalsIgnoreCase(requestedNamespace)).toList();
        if (folded.size() == 1) {
            return folded.get(0);
        }
        if (folded.size() > 1) {
            throw new IllegalArgumentException("Schema/数据库名称大小写不明确，请使用精确名称：" + requestedNamespace);
        }
        throw new IllegalArgumentException("未找到 Schema/数据库：" + requestedNamespace);
    }

    private List<String> availableNamespaces(Connection connection, DatabaseDialect dialect) throws Exception {
        Set<String> namespaces = new java.util.LinkedHashSet<>();
        String current = blankToNull(dialect.currentSchema(connection));
        if (current != null && !isSystemSchema(current)) {
            namespaces.add(current);
        }
        DatabaseMetaData meta = connection.getMetaData();
        try (ResultSet rs = dialect.namespaceKind() == DatabaseDialect.NamespaceKind.CATALOG
                ? meta.getCatalogs()
                : meta.getSchemas()) {
            String column = dialect.namespaceKind() == DatabaseDialect.NamespaceKind.CATALOG ? "TABLE_CAT" : "TABLE_SCHEM";
            while (rs.next()) {
                String namespace = blankToNull(rs.getString(column));
                if (namespace != null && !isSystemSchema(namespace)) {
                    namespaces.add(namespace);
                }
            }
        }
        return namespaces.stream()
                .sorted(String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.naturalOrder()))
                .toList();
    }

    private List<TableRef> physicalTables(Connection connection, DatabaseDialect dialect, String namespace) throws Exception {
        DatabaseMetaData meta = connection.getMetaData();
        DatabaseDialect.MetadataScope metadataScope = dialect.metadataScope(connection, namespace);
        String schemaPattern = metadataScope.schemaPattern() == null ? null : metadataExactPattern(meta, metadataScope.schemaPattern());
        List<TableRef> tables = new ArrayList<>();
        try (ResultSet rs = meta.getTables(metadataScope.catalog(), schemaPattern, "%", new String[]{"TABLE", "BASE TABLE"})) {
            while (rs.next()) {
                String foundNamespace = blankToNull(dialect.resultNamespace(rs));
                if (namespace != null && foundNamespace != null && !namespace.equals(foundNamespace)) {
                    continue;
                }
                String effectiveNamespace = foundNamespace == null ? namespace : foundNamespace;
                String name = blankToNull(rs.getString("TABLE_NAME"));
                String type = blankToNull(rs.getString("TABLE_TYPE"));
                if (name != null && ("TABLE".equalsIgnoreCase(type) || "BASE TABLE".equalsIgnoreCase(type)) && !isSystemSchema(effectiveNamespace)) {
                    tables.add(new TableRef(effectiveNamespace, name));
                }
            }
        }
        tables.sort(Comparator
                .comparing((TableRef table) -> table.schema() == null ? "" : table.schema(), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(table -> table.schema() == null ? "" : table.schema())
                .thenComparing(TableRef::name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(TableRef::name));
        return List.copyOf(tables);
    }

    static String metadataExactPattern(DatabaseMetaData meta, String value) throws Exception {
        String escape = meta.getSearchStringEscape();
        if (escape == null || escape.isEmpty()) return value;
        return value.replace(escape, escape + escape)
                .replace("%", escape + "%")
                .replace("_", escape + "_");
    }

    static String tableKey(TableRef table) {
        return (table.schema() == null ? "" : table.schema().toLowerCase(Locale.ROOT)) + "\u0000" + table.name().toLowerCase(Locale.ROOT);
    }

    static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static boolean isSystemSchema(String schema) {
        if (schema == null) {
            return false;
        }
        String s = schema.toUpperCase(Locale.ROOT);
        return s.equals("INFORMATION_SCHEMA")
                || s.equals("SYS")
                || s.equals("SYSTEM")
                || s.equals("PG_CATALOG")
                || s.equals("SQLJ")
                || s.startsWith("SYS_")
                || s.equals("MYSQL")
                || s.startsWith("PG_TOAST")
                || s.startsWith("PG_TEMP_");
    }

    record TableRef(String schema, String name) {
    }

    record ResolvedTarget(String scope, String namespace, List<TableRef> tables) {
    }
}
