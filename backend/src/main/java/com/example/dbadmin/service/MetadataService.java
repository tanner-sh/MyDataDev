package com.example.dbadmin.service;

import com.example.dbadmin.api.ApiProblemException;
import com.example.dbadmin.core.DatabaseDialect;
import com.example.dbadmin.core.DialectRegistry;
import com.example.dbadmin.dto.ApiDtos.ColumnInfo;
import com.example.dbadmin.dto.ApiDtos.CompletionCatalogResponse;
import com.example.dbadmin.dto.ApiDtos.BackupTargetItem;
import com.example.dbadmin.dto.ApiDtos.BackupTargetPage;
import com.example.dbadmin.dto.ApiDtos.DbObject;
import com.example.dbadmin.dto.ApiDtos.IndexInfo;
import com.example.dbadmin.dto.ApiDtos.MetadataResponse;
import com.example.dbadmin.dto.ApiDtos.ObjectDetail;
import com.example.dbadmin.dto.ApiDtos.ObjectDdlResponse;
import com.example.dbadmin.dto.ApiDtos.ObjectRelation;
import com.example.dbadmin.dto.ApiDtos.ObjectRelations;
import com.example.dbadmin.dto.ApiDtos.ObjectRowCountResponse;
import com.example.dbadmin.dto.ApiDtos.ObjectStructure;
import com.example.dbadmin.dto.ApiDtos.TableDesignRequest;
import com.example.dbadmin.dto.ApiDtos.TableDesignResponse;
import com.example.dbadmin.model.DbConnection;
import com.example.dbadmin.repo.AuditRepository;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.Base64;

@Service
public class MetadataService {
    private final ConnectionService connections;
    private final DialectRegistry dialectRegistry;
    private final AuditRepository audit;
    private final MetadataCacheService cache;
    private final ExecutionGuard executionGuard;
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_$#]*");
    private static final long MAX_METADATA_OFFSET = 1_000_000;
    private static final int MAX_SEARCH_LENGTH = 200;

    @Autowired
    public MetadataService(ConnectionService connections, DialectRegistry dialectRegistry, AuditRepository audit, MetadataCacheService cache, ExecutionGuard executionGuard) {
        this.connections = connections;
        this.dialectRegistry = dialectRegistry;
        this.audit = audit;
        this.cache = cache;
        this.executionGuard = executionGuard;
    }

    public MetadataService(ConnectionService connections, DialectRegistry dialectRegistry, AuditRepository audit, MetadataCacheService cache) {
        this(connections, dialectRegistry, audit, cache, new ExecutionGuard());
    }

    public MetadataResponse inspect(long connectionId, String schemaFilter, String keyword, Integer page, Integer pageSize, boolean refresh) throws Exception {
        DbConnection dbConnection = connections.require(connectionId);
        DatabaseDialect dialect = dialectRegistry.dialectFor(dbConnection);
        if (refresh) {
            cache.evictConnection(connectionId);
        }
        MetadataCacheService.SchemaCatalogSnapshot schemaCatalog = cache.schemaCatalog(connectionId).orElse(null);
        if (schemaCatalog == null) {
            try (Connection connection = connections.open(connectionId)) {
                schemaCatalog = loadSchemaCatalog(connectionId, connection, dbConnection, dialect);
            }
        }
        String selectedSchema = selectedSchema(schemaCatalog, schemaFilter);
        int normalizedPage = Math.max(page == null ? 0 : page, 0);
        int normalizedPageSize = Math.min(Math.max(pageSize == null ? 200 : pageSize, 1), 500);
        String normalizedKeyword = searchTerm(keyword);
        var cachedPage = cache.metadataPage(connectionId, selectedSchema, normalizedKeyword, normalizedPage, normalizedPageSize);
        boolean cacheHit = cachedPage.isPresent();
        MetadataCacheService.CachedValue<MetadataCacheService.MetadataObjectPage> pageValue = cachedPage.orElse(null);
        if (pageValue == null) {
            try (Connection connection = connections.open(connectionId)) {
                MetadataCacheService.MetadataObjectPage loaded = queryObjectPage(
                        connection, dialect, selectedSchema, normalizedKeyword, MatchMode.CONTAINS,
                        normalizedPage, normalizedPageSize, false
                );
                pageValue = cache.putMetadataPage(connectionId, selectedSchema, normalizedKeyword, normalizedPage, normalizedPageSize, loaded);
            }
        }
        MetadataCacheService.MetadataObjectPage objectPage = pageValue.value();
        return new MetadataResponse(
                schemaCatalog.schemas(),
                schemaCatalog.currentSchema(),
                selectedSchema,
                dialect.namespaceKind().name(),
                objectPage.objects(),
                objectPage.total(),
                objectPage.totalExact(),
                normalizedPage,
                normalizedPageSize,
                objectPage.hasMore(),
                pageValue.cachedAt().toString(),
                cacheHit
        );
    }

    public CompletionCatalogResponse completionCatalog(long connectionId, String requestedNamespace, String prefix, Integer limit, boolean refresh) throws Exception {
        DbConnection dbConnection = connections.require(connectionId);
        DatabaseDialect dialect = dialectRegistry.dialectFor(dbConnection);
        if (refresh) cache.evictConnection(connectionId);
        MetadataCacheService.SchemaCatalogSnapshot catalog = cache.schemaCatalog(connectionId).orElse(null);
        boolean cacheHit = catalog != null;
        if (catalog == null) {
            try (Connection connection = connections.open(connectionId)) {
                catalog = loadSchemaCatalog(connectionId, connection, dbConnection, dialect);
            }
        }
        String selectedSchema = selectedSchema(catalog, requestedNamespace);
        int safeLimit = Math.min(Math.max(limit == null ? 100 : limit, 1), 100);
        MetadataCacheService.MetadataObjectPage matches;
        try (Connection connection = connections.open(connectionId)) {
            matches = queryObjectPage(
                    connection, dialect, selectedSchema, searchTerm(prefix), MatchMode.PREFIX,
                    0, safeLimit, false
            );
        }
        return new CompletionCatalogResponse(
                dialect.namespaceKind().name(),
                selectedSchema,
                matches.objects(),
                Instant.now().toString(),
                cacheHit,
                matches.hasMore()
        );
    }

    public CompletionCatalogResponse completionCatalog(long connectionId, String requestedNamespace, boolean refresh) throws Exception {
        return completionCatalog(connectionId, requestedNamespace, null, 100, refresh);
    }

    public BackupTargetPage backupTargetNamespaces(long connectionId, String keyword, Integer page, Integer pageSize, boolean refresh) throws Exception {
        DbConnection dbConnection = connections.require(connectionId);
        DatabaseDialect dialect = dialectRegistry.dialectFor(dbConnection);
        if (refresh) {
            cache.evictConnection(connectionId);
        }
        MetadataCacheService.SchemaCatalogSnapshot catalog = cache.schemaCatalog(connectionId).orElse(null);
        if (catalog == null) {
            try (Connection connection = connections.open(connectionId)) {
                catalog = loadSchemaCatalog(connectionId, connection, dbConnection, dialect);
            }
        }
        String normalizedKeyword = searchTerm(keyword);
        if (normalizedKeyword != null) {
            normalizedKeyword = normalizedKeyword.toLowerCase(Locale.ROOT);
        }
        String filter = normalizedKeyword;
        List<String> filtered = catalog.schemas().stream()
                .filter(name -> filter == null || name.toLowerCase(Locale.ROOT).contains(filter))
                .toList();
        PageSlice<String> slice = page(filtered, page, pageSize);
        String currentNamespace = trimToNull(catalog.currentSchema());
        List<BackupTargetItem> items = slice.items().stream()
                .map(name -> new BackupTargetItem(name, currentNamespace != null && name.equals(currentNamespace)))
                .toList();
        return new BackupTargetPage(
                dialect.namespaceKind().name(), currentNamespace, currentNamespace, items,
                filtered.size(), slice.page(), slice.pageSize(), slice.hasMore()
        );
    }

    public BackupTargetPage backupTargetTables(long connectionId, String namespaceName, String keyword, Integer page, Integer pageSize, boolean refresh) throws Exception {
        DbConnection dbConnection = connections.require(connectionId);
        DatabaseDialect dialect = dialectRegistry.dialectFor(dbConnection);
        if (refresh) {
            cache.evictConnection(connectionId);
        }
        MetadataCacheService.SchemaCatalogSnapshot catalog = cache.schemaCatalog(connectionId).orElse(null);
        if (catalog == null) {
            try (Connection connection = connections.open(connectionId)) {
                catalog = loadSchemaCatalog(connectionId, connection, dbConnection, dialect);
            }
        }
        String requested = trimToNull(namespaceName);
        if (requested == null) {
            throw new IllegalArgumentException("请选择 Schema/数据库。");
        }
        String selected = selectedSchema(catalog, requested);
        if (!catalog.schemas().contains(selected)) {
            throw new IllegalArgumentException("未找到 Schema/数据库：" + requested);
        }
        int normalizedPage = Math.max(page == null ? 0 : page, 0);
        int normalizedPageSize = Math.min(Math.max(pageSize == null ? 50 : pageSize, 1), 500);
        MetadataCacheService.MetadataObjectPage objectPage;
        try (Connection connection = connections.open(connectionId)) {
            objectPage = queryObjectPage(
                    connection, dialect, selected, searchTerm(keyword), MatchMode.CONTAINS,
                    normalizedPage, normalizedPageSize, true
            );
        }
        List<BackupTargetItem> items = objectPage.objects().stream()
                .map(object -> new BackupTargetItem(object.name(), false))
                .toList();
        String currentNamespace = trimToNull(catalog.currentSchema());
        return new BackupTargetPage(
                dialect.namespaceKind().name(), currentNamespace, selected, items,
                objectPage.total(), normalizedPage, normalizedPageSize, objectPage.hasMore(), objectPage.totalExact()
        );
    }

    public void invalidateConnection(long connectionId) {
        cache.evictConnection(connectionId);
    }

    private MetadataCacheService.SchemaCatalogSnapshot loadSchemaCatalog(long connectionId, Connection connection, DbConnection dbConnection, DatabaseDialect dialect) throws Exception {
        DatabaseMetaData meta = connection.getMetaData();
        String currentSchema = trimToNull(dialect.currentSchema(connection));
        if (currentSchema == null) {
            currentSchema = trimToNull(dbConnection.username());
        }
        if (currentSchema == null) {
            currentSchema = "";
        }
        boolean currentIsCatalog = dialect.namespaceKind() == DatabaseDialect.NamespaceKind.CATALOG;
        LinkedHashSet<String> available = new LinkedHashSet<>();
        if (!currentSchema.isBlank()) {
            available.add(currentSchema);
        }
        available.addAll(namespaces(meta, dialect));
        String resolvedCurrentSchema = currentSchema;
        List<String> schemaNames = available.stream()
                .sorted((left, right) -> {
                    if (left.equals(right)) return 0;
                    if (!resolvedCurrentSchema.isBlank() && left.equals(resolvedCurrentSchema)) return -1;
                    if (!resolvedCurrentSchema.isBlank() && right.equals(resolvedCurrentSchema)) return 1;
                    int folded = left.compareToIgnoreCase(right);
                    return folded != 0 ? folded : left.compareTo(right);
                })
                .toList();
        return cache.putSchemaCatalog(connectionId, schemaNames, currentSchema, currentIsCatalog);
    }

    private MetadataCacheService.MetadataObjectPage queryObjectPage(
            Connection connection,
            DatabaseDialect dialect,
            String selectedSchema,
            String keyword,
            MatchMode matchMode,
            int page,
            int pageSize,
            boolean physicalOnly
    ) throws Exception {
        DatabaseMetaData meta = connection.getMetaData();
        DatabaseDialect.MetadataScope scope = dialect.metadataScope(connection, selectedSchema);
        String schemaPattern = scope.schemaPattern() == null ? null : metadataPattern(meta, scope.schemaPattern(), MatchMode.EXACT);
        String tablePattern = keyword == null ? "%" : metadataPattern(meta, keyword, matchMode);
        long offset = (long) page * pageSize;
        if (offset > MAX_METADATA_OFFSET) {
            throw new IllegalArgumentException("元数据分页偏移过大，请使用搜索条件缩小范围。");
        }
        long matched = 0;
        boolean exhausted = true;
        List<DbObject> objects = new ArrayList<>(pageSize + 1);
        String[] types = physicalOnly ? new String[]{"TABLE", "BASE TABLE"} : new String[]{"TABLE", "VIEW"};
        try (ResultSet rs = meta.getTables(scope.catalog(), schemaPattern, tablePattern, types)) {
            while (rs.next()) {
                String schema = dialect.resultNamespace(rs);
                String name = rs.getString("TABLE_NAME");
                String type = rs.getString("TABLE_TYPE");
                if (selectedSchema != null && !selectedSchema.isBlank() && schema != null && !selectedSchema.equals(schema)) {
                    continue;
                }
                if (isSystemSchema(schema) && (selectedSchema == null || !selectedSchema.equals(schema))) {
                    continue;
                }
                if (!matchesName(name, keyword, matchMode)) continue;
                if (physicalOnly && !isPhysicalTable(type)) continue;
                matched++;
                if (matched <= offset) continue;
                objects.add(new DbObject(schema, name, type, List.of(), List.of()));
                if (objects.size() > pageSize) {
                    exhausted = false;
                    break;
                }
            }
        }
        boolean hasMore = objects.size() > pageSize;
        if (hasMore) objects = new ArrayList<>(objects.subList(0, pageSize));
        int total = (int) Math.min(Integer.MAX_VALUE, exhausted ? matched : offset + objects.size() + 1L);
        return new MetadataCacheService.MetadataObjectPage(objects, total, exhausted, hasMore);
    }

    private String metadataPattern(DatabaseMetaData meta, String value, MatchMode mode) throws Exception {
        String normalized = value;
        // Exact values come from the JDBC catalog and may be quoted,
        // case-sensitive identifiers. Only fold free-text search patterns.
        if (mode != MatchMode.EXACT) {
            if (meta.storesUpperCaseIdentifiers()) normalized = value.toUpperCase(Locale.ROOT);
            else if (meta.storesLowerCaseIdentifiers()) normalized = value.toLowerCase(Locale.ROOT);
        }
        String escape = meta.getSearchStringEscape();
        if (escape == null) escape = "";
        String escaped = normalized;
        if (!escape.isEmpty()) {
            escaped = escaped.replace(escape, escape + escape)
                    .replace("%", escape + "%")
                    .replace("_", escape + "_");
        }
        return switch (mode) {
            case EXACT -> escaped;
            case PREFIX -> escaped + "%";
            case CONTAINS -> "%" + escaped + "%";
        };
    }

    private boolean matchesName(String name, String keyword, MatchMode mode) {
        if (keyword == null || keyword.isBlank()) return true;
        String candidate = name == null ? "" : name.toLowerCase(Locale.ROOT);
        String expected = keyword.toLowerCase(Locale.ROOT);
        return mode == MatchMode.PREFIX ? candidate.startsWith(expected) : candidate.contains(expected);
    }

    public ObjectStructure structure(long connectionId, String schemaName, String objectName) throws Exception {
        return structure(connectionId, schemaName, objectName, false);
    }

    public ObjectStructure structure(long connectionId, String schemaName, String objectName, boolean refresh) throws Exception {
        if (!refresh) {
            var cached = cache.structure(connectionId, schemaName, objectName);
            if (cached.isPresent()) {
                return cached.get();
            }
        }
        try (Connection connection = connections.open(connectionId)) {
            DatabaseMetaData meta = connection.getMetaData();
            DatabaseDialect dialect = dialectRegistry.dialectFor(connections.require(connectionId));
            DatabaseDialect.MetadataScope scope = dialect.metadataScope(connection, schemaName);
            DbObject object = findObject(meta, scope, dialect, objectName);
            DatabaseDialect.MetadataScope objectScope = dialect.metadataScope(connection, object.schemaName());
            ObjectStructure structure = new ObjectStructure(
                    object.schemaName(),
                    object.name(),
                    object.type(),
                    columns(meta, objectScope.catalog(), objectScope.schemaPattern(), object.name()),
                    indexes(meta, objectScope.catalog(), objectScope.schemaPattern(), object.name())
            );
            cache.putStructure(connectionId, object.schemaName(), object.name(), structure);
            return structure;
        }
    }

    public List<String> primaryOrUniqueColumns(long connectionId, String schema, String table) throws Exception {
        return rowIdentity(connectionId, schema, table).columns();
    }

    public RowIdentity rowIdentity(long connectionId, String schema, String table) throws Exception {
        DbConnection dbConnection = connections.require(connectionId);
        try (Connection connection = connections.open(connectionId)) {
            return rowIdentity(connection, dbConnection, schema, table);
        }
    }

    public RowIdentity rowIdentity(Connection connection, DbConnection dbConnection, String schema, String table) throws Exception {
        var cached = cache.rowIdentity(dbConnection.id(), schema, table);
        if (cached.isPresent()) return cached.get();
        DatabaseMetaData meta = connection.getMetaData();
        DatabaseDialect dialect = dialectRegistry.dialectFor(dbConnection);
        DatabaseDialect.MetadataScope scope = dialect.metadataScope(connection, schema);
        List<String> primary = primaryKeys(meta, scope.catalog(), scope.schemaPattern(), table);
        if (!primary.isEmpty()) {
            RowIdentity identity = new RowIdentity(primary, "PRIMARY_KEY", true);
            cache.putRowIdentity(dbConnection.id(), schema, table, identity);
            return identity;
        }

        Map<String, Boolean> nullable = columns(meta, scope.catalog(), scope.schemaPattern(), table).stream()
                .collect(Collectors.toMap(ColumnInfo::name, ColumnInfo::nullable, (left, right) -> left));
        Map<String, TreeMap<Short, String>> uniqueIndexes = new HashMap<>();
        Set<String> invalidUniqueIndexes = new HashSet<>();
        try (ResultSet rs = meta.getIndexInfo(scope.catalog(), scope.schemaPattern(), table, true, false)) {
            while (rs.next()) {
                String name = rs.getString("INDEX_NAME");
                String column = rs.getString("COLUMN_NAME");
                if (name == null || rs.getBoolean("NON_UNIQUE")) continue;
                String filterCondition = rs.getString("FILTER_CONDITION");
                if (column == null || filterCondition != null && !filterCondition.isBlank()) {
                    invalidUniqueIndexes.add(name);
                    continue;
                }
                short position = rs.getShort("ORDINAL_POSITION");
                uniqueIndexes.computeIfAbsent(name, ignored -> new TreeMap<>()).put(position, column);
            }
        }
        RowIdentity identity = uniqueIndexes.entrySet().stream()
                .filter(entry -> !invalidUniqueIndexes.contains(entry.getKey()))
                .map(entry -> new RowIdentity(new ArrayList<>(entry.getValue().values()), "UNIQUE_INDEX", true))
                .filter(candidate -> !candidate.columns().isEmpty())
                .filter(candidate -> candidate.columns().stream().allMatch(column -> Boolean.FALSE.equals(nullable.get(column))))
                .min(Comparator.comparingInt((RowIdentity candidate) -> candidate.columns().size())
                        .thenComparing(candidate -> String.join("\u0000", candidate.columns()), String.CASE_INSENSITIVE_ORDER))
                .orElseGet(() -> new RowIdentity(List.of(), "NONE", false));
        cache.putRowIdentity(dbConnection.id(), schema, table, identity);
        return identity;
    }

    public ObjectDetail detail(long connectionId, String schemaName, String objectName) throws Exception {
        return detail(connectionId, schemaName, objectName, false);
    }

    public ObjectDetail detail(long connectionId, String schemaName, String objectName, boolean refresh) throws Exception {
        if (refresh) {
            // An explicit object refresh means every dependent panel (DDL,
            // relations, structure and row identity) must observe the same
            // live snapshot, not only the summary record.
            cache.evictObject(connectionId, schemaName, objectName);
        } else {
            var cached = cache.detail(connectionId, schemaName, objectName);
            if (cached.isPresent()) {
                return cached.get();
            }
        }
        try (Connection connection = connections.open(connectionId)) {
            DatabaseMetaData meta = connection.getMetaData();
            DbConnection dbConnection = connections.require(connectionId);
            DatabaseDialect dialect = dialectRegistry.dialectFor(dbConnection);
            DatabaseDialect.MetadataScope scope = dialect.metadataScope(connection, schemaName);
            DbObject object = findObject(meta, scope, dialect, objectName);
            DatabaseDialect.MetadataScope objectScope = dialect.metadataScope(connection, object.schemaName());
            List<ColumnInfo> cols = columns(meta, objectScope.catalog(), objectScope.schemaPattern(), object.name());
            List<IndexInfo> idx = indexes(meta, objectScope.catalog(), objectScope.schemaPattern(), object.name());
            PrimaryKeyInfo pk = primaryKeyInfo(meta, objectScope.catalog(), objectScope.schemaPattern(), object.name());
            ObjectDetail detail = new ObjectDetail(
                    object.schemaName(), object.name(), object.type(), cols, idx, pk.columns(), pk.name(),
                    structureVersion(object.schemaName(), object.name(), object.type(), cols, idx, pk)
            );
            cache.putDetail(connectionId, object.schemaName(), object.name(), detail);
            return detail;
        }
    }

    public ObjectDdlResponse ddl(long connectionId, String schemaName, String objectName, boolean refresh) throws Exception {
        if (!refresh) {
            var cached = cache.ddl(connectionId, schemaName, objectName);
            if (cached.isPresent()) return cached.get();
        }
        try (Connection connection = connections.open(connectionId)) {
            DatabaseMetaData meta = connection.getMetaData();
            DbConnection dbConnection = connections.require(connectionId);
            DatabaseDialect dialect = dialectRegistry.dialectFor(dbConnection);
            DatabaseDialect.MetadataScope scope = dialect.metadataScope(connection, schemaName);
            DbObject object = findObject(meta, scope, dialect, objectName);
            DatabaseDialect.MetadataScope objectScope = dialect.metadataScope(connection, object.schemaName());
            List<ColumnInfo> columns = columns(meta, objectScope.catalog(), objectScope.schemaPattern(), object.name());
            List<String> primaryKeys = primaryKeys(meta, objectScope.catalog(), objectScope.schemaPattern(), object.name());
            DdlResult result = ddl(connection, dialect, object.schemaName(), object.name(), object.type(), columns, primaryKeys);
            ObjectDdlResponse response = new ObjectDdlResponse(result.sql(), result.source());
            cache.putDdl(connectionId, object.schemaName(), object.name(), response);
            return response;
        }
    }

    public ObjectRowCountResponse rowCount(long connectionId, String schemaName, String objectName) throws Exception {
        long started = System.nanoTime();
        DbConnection dbConnection = connections.require(connectionId);
        DatabaseDialect dialect = dialectRegistry.dialectFor(dbConnection);
        try (Connection connection = connections.open(connectionId);
             ReadOnlyQueryScope ignored = ReadOnlyQueryScope.begin(connection, true);
             Statement statement = connection.createStatement()) {
            dialect.configureReadStatement(connection, statement, 1, 15);
            try (ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM " + dialect.qualifiedName(schemaName, objectName))) {
                Long value = rs.next() ? rs.getLong(1) : null;
                return new ObjectRowCountResponse(value, true, (System.nanoTime() - started) / 1_000_000);
            }
        }
    }

    public ObjectRelations relations(long connectionId, String schemaName, String objectName) throws Exception {
        return relations(connectionId, schemaName, objectName, false);
    }

    public ObjectRelations relations(long connectionId, String schemaName, String objectName, boolean refresh) throws Exception {
        if (!refresh) {
            var cached = cache.relations(connectionId, schemaName, objectName);
            if (cached.isPresent()) {
                return cached.get();
            }
        }
        try (Connection connection = connections.open(connectionId)) {
            DatabaseMetaData meta = connection.getMetaData();
            DatabaseDialect dialect = dialectRegistry.dialectFor(connections.require(connectionId));
            DatabaseDialect.MetadataScope scope = dialect.metadataScope(connection, schemaName);
            DbObject object = findObject(meta, scope, dialect, objectName);
            DatabaseDialect.MetadataScope objectScope = dialect.metadataScope(connection, object.schemaName());
            ObjectRelations relations = new ObjectRelations(
                    relations(meta.getImportedKeys(objectScope.catalog(), objectScope.schemaPattern(), object.name()), dialect),
                    relations(meta.getExportedKeys(objectScope.catalog(), objectScope.schemaPattern(), object.name()), dialect)
            );
            cache.putRelations(connectionId, object.schemaName(), object.name(), relations);
            return relations;
        }
    }

    public TableDesignResponse previewDesign(long connectionId, TableDesignRequest request) throws Exception {
        List<String> sql = designSql(connectionId, request);
        return new TableDesignResponse(sql, sql.isEmpty() ? "没有检测到结构变更。" : "已生成 " + sql.size() + " 条 DDL。");
    }

    public TableDesignResponse executeDesign(long connectionId, TableDesignRequest request, String actor, String productionConfirmation) throws Exception {
        DbConnection dbConnection = connections.require(connectionId);
        executionGuard.requireMutationAllowed(dbConnection, productionConfirmation);
        String expected = qualifiedName(request.schemaName(), request.tableName());
        if (!expected.equals(request.confirmation())) {
            throw new IllegalArgumentException("确认文本不匹配，请输入完整表名：" + expected);
        }
        List<String> sql = designSql(connectionId, request);
        if (sql.isEmpty()) {
            return new TableDesignResponse(List.of(), "没有检测到结构变更。");
        }
        int executed = 0;
        try (Connection connection = connections.open(connectionId); Statement statement = connection.createStatement()) {
            for (String line : sql) {
                statement.execute(line);
                executed++;
            }
        } catch (Exception error) {
            String failedStatement = executed < sql.size() ? sql.get(executed) : "";
            String message = executed == 0
                    ? "DDL 执行失败，数据库未确认任何语句成功。"
                    : "DDL 部分执行失败：已有 " + executed + " 条语句成功，请刷新对象并核对实际结构。";
            ApiProblemException problem = new ApiProblemException(
                    org.springframework.http.HttpStatus.CONFLICT,
                    executed == 0 ? "DDL_EXECUTION_FAILED" : "DDL_PARTIALLY_APPLIED",
                    message,
                    Map.of(
                            "executedStatements", List.copyOf(sql.subList(0, executed)),
                            "failedStatement", failedStatement
                    )
            );
            problem.initCause(error);
            throw problem;
        } finally {
            // DDL auto-commits on several supported databases. A failed batch
            // can still have changed the object, so cached metadata is unsafe.
            cache.evictObject(connectionId, request.schemaName(), request.tableName());
        }
        audit.log(actor, "TABLE_DESIGN_EXECUTE", "connection:" + connectionId + " table:" + expected, String.join("\n", sql));
        return new TableDesignResponse(sql, "已执行 " + sql.size() + " 条 DDL。");
    }

    public TableDesignResponse executeDesign(long connectionId, TableDesignRequest request, String actor) throws Exception {
        return executeDesign(connectionId, request, actor, null);
    }

    private List<String> designSql(long connectionId, TableDesignRequest request) throws Exception {
        validateDesign(request);
        // Always compare the submitted design with live metadata. The browser
        // may have kept the designer open while another session changed the
        // table, and cached metadata must never turn a new column into a drop.
        ObjectDetail original = detail(connectionId, request.schemaName(), request.tableName(), true);
        if (request.structureVersion() == null || request.structureVersion().isBlank()) {
            throw new IllegalArgumentException("表结构设计缺少版本信息，请刷新对象后重新设计。");
        }
        if (!request.structureVersion().equals(original.structureVersion())) {
            throw new ApiProblemException(
                    org.springframework.http.HttpStatus.CONFLICT,
                    "STALE_TABLE_DESIGN",
                    "表结构已被其他会话修改，请刷新对象后重新设计。"
            );
        }
        DbConnection dbConnection = connections.require(connectionId);
        DatabaseDialect dialect = dialectRegistry.dialectFor(dbConnection);
        if (!dialect.capabilities().tableDesign()) {
            throw new IllegalStateException("当前数据库类型暂不支持表结构设计。请使用数据库原生工具执行 DDL。");
        }
        return dialect.alterTableSql(original.schemaName(), original.name(), original, request);
    }

    private List<String> namespaces(DatabaseMetaData meta, DatabaseDialect dialect) throws Exception {
        List<String> namespaces = new ArrayList<>();
        try (ResultSet rs = dialect.namespaceKind() == DatabaseDialect.NamespaceKind.CATALOG
                ? meta.getCatalogs()
                : meta.getSchemas()) {
            String column = dialect.namespaceKind() == DatabaseDialect.NamespaceKind.CATALOG ? "TABLE_CAT" : "TABLE_SCHEM";
            while (rs.next()) {
                String namespace = rs.getString(column);
                if (!isSystemSchema(namespace)) {
                    namespaces.add(namespace);
                }
            }
        }
        return namespaces;
    }

    private String selectedSchema(MetadataCacheService.SchemaCatalogSnapshot catalog, String requestedSchema) {
        String requested = trimToNull(requestedSchema);
        if (requested == null) {
            requested = trimToNull(catalog.currentSchema());
        }
        if (requested == null && !catalog.schemas().isEmpty()) {
            requested = catalog.schemas().get(0);
        }
        if (requested == null) {
            return "";
        }
        if (catalog.schemas().contains(requested)) return requested;
        String selected = requested;
        List<String> folded = catalog.schemas().stream()
                .filter(schema -> schema.equalsIgnoreCase(selected))
                .toList();
        if (folded.size() == 1) return folded.get(0);
        if (folded.size() > 1) {
            throw new IllegalArgumentException("Schema/数据库名称大小写不明确，请使用精确名称：" + requested);
        }
        return requested;
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private String searchTerm(String value) {
        String normalized = trimToNull(value);
        if (normalized != null && normalized.length() > MAX_SEARCH_LENGTH) {
            throw new IllegalArgumentException("搜索关键字最多 " + MAX_SEARCH_LENGTH + " 个字符。");
        }
        return normalized;
    }

    private boolean isSystemSchema(String schema) {
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

    private boolean isPhysicalTable(String type) {
        return type != null && ("TABLE".equalsIgnoreCase(type) || "BASE TABLE".equalsIgnoreCase(type));
    }

    private <T> PageSlice<T> page(List<T> items, Integer page, Integer pageSize) {
        int normalizedPage = Math.max(page == null ? 0 : page, 0);
        int normalizedPageSize = Math.min(Math.max(pageSize == null ? 50 : pageSize, 1), 500);
        long offset = (long) normalizedPage * normalizedPageSize;
        if (offset >= items.size()) {
            return new PageSlice<>(List.of(), normalizedPage, normalizedPageSize, false);
        }
        int from = (int) offset;
        int to = Math.min(from + normalizedPageSize, items.size());
        return new PageSlice<>(items.subList(from, to), normalizedPage, normalizedPageSize, to < items.size());
    }

    private DbObject findObject(DatabaseMetaData meta, DatabaseDialect.MetadataScope scope, DatabaseDialect dialect, String objectName) throws Exception {
        String schemaPattern = scope.schemaPattern() == null ? null : metadataPattern(meta, scope.schemaPattern(), MatchMode.EXACT);
        String objectPattern = metadataPattern(meta, objectName, MatchMode.EXACT);
        try (ResultSet rs = meta.getTables(scope.catalog(), schemaPattern, objectPattern, new String[]{"TABLE", "VIEW"})) {
            while (rs.next()) {
                String foundSchema = dialect.resultNamespace(rs);
                String foundName = rs.getString("TABLE_NAME");
                String type = rs.getString("TABLE_TYPE");
                if (!isSystemSchema(foundSchema) && objectName.equals(foundName)) {
                    return new DbObject(foundSchema, foundName, type, List.of(), List.of());
                }
            }
        }
        List<DbObject> foldedMatches = new ArrayList<>();
        try (ResultSet rs = meta.getTables(scope.catalog(), schemaPattern, "%", new String[]{"TABLE", "VIEW"})) {
            while (rs.next()) {
                String foundSchema = dialect.resultNamespace(rs);
                String foundName = rs.getString("TABLE_NAME");
                String type = rs.getString("TABLE_TYPE");
                if (!isSystemSchema(foundSchema) && foundName != null && foundName.equalsIgnoreCase(objectName)) {
                    foldedMatches.add(new DbObject(foundSchema, foundName, type, List.of(), List.of()));
                }
            }
        }
        if (foldedMatches.size() == 1) return foldedMatches.get(0);
        if (foldedMatches.size() > 1) throw new IllegalArgumentException("对象名称大小写不明确，请使用数据库返回的精确名称：" + objectName);
        throw new IllegalArgumentException("未找到数据库对象：" + objectName);
    }

    private List<String> primaryKeys(DatabaseMetaData meta, String catalog, String schema, String table) throws Exception {
        return primaryKeyInfo(meta, catalog, schema, table).columns();
    }

    private PrimaryKeyInfo primaryKeyInfo(DatabaseMetaData meta, String catalog, String schema, String table) throws Exception {
        TreeMap<Short, String> ordered = new TreeMap<>();
        String pkName = null;
        try (ResultSet rs = meta.getPrimaryKeys(catalog, schema, table)) {
            while (rs.next()) {
                ordered.put(rs.getShort("KEY_SEQ"), rs.getString("COLUMN_NAME"));
                if (pkName == null) {
                    pkName = rs.getString("PK_NAME");
                }
            }
        }
        return new PrimaryKeyInfo(new ArrayList<>(ordered.values()), pkName);
    }

    private DdlResult ddl(Connection connection, DatabaseDialect dialect, String schema, String table, String type, List<ColumnInfo> columns, List<String> primaryKeys) throws Exception {
        var nativeDdl = dialect.nativeDdl(connection, schema, table, type);
        if (nativeDdl.isPresent() && !nativeDdl.get().isBlank()) {
            return new DdlResult(nativeDdl.get(), "NATIVE");
        }
        return new DdlResult(generatedDdl(dialect, schema, table, type, columns, primaryKeys), "GENERATED");
    }

    private String generatedDdl(DatabaseDialect dialect, String schema, String table, String type, List<ColumnInfo> columns, List<String> primaryKeys) {
        if (isView(type)) {
            return "-- 视图定义反查暂未实现。\nCREATE VIEW " + dialect.qualifiedName(schema, table) + " AS\n-- 请使用数据库原生工具查看完整视图定义。";
        }
        List<String> lines = new ArrayList<>();
        for (ColumnInfo column : columns) {
            lines.add("  " + dialect.quoteIdentifier(column.name()) + " " + columnType(column) + (column.nullable() ? "" : " NOT NULL"));
        }
        if (!primaryKeys.isEmpty()) {
            lines.add("  PRIMARY KEY (" + String.join(", ", primaryKeys.stream().map(dialect::quoteIdentifier).toList()) + ")");
        }
        return "CREATE TABLE " + dialect.qualifiedName(schema, table) + " (\n" + String.join(",\n", lines) + "\n);";
    }

    private String columnType(ColumnInfo column) {
        String type = column.type() == null || column.type().isBlank() ? "VARCHAR" : column.type();
        String upper = type.toUpperCase(Locale.ROOT);
        if (!type.contains("(") && column.size() > 0 && column.size() < 1_000_000 && (upper.contains("CHAR") || upper.contains("BINARY"))) {
            return type + "(" + column.size() + ")";
        }
        return type;
    }

    private boolean isView(String type) {
        return type != null && type.toUpperCase(Locale.ROOT).contains("VIEW");
    }

    private List<ColumnInfo> columns(DatabaseMetaData meta, String catalog, String schema, String table) throws Exception {
        List<ColumnInfo> cols = new ArrayList<>();
        String schemaPattern = schema == null ? null : metadataPattern(meta, schema, MatchMode.EXACT);
        String tablePattern = metadataPattern(meta, table, MatchMode.EXACT);
        try (ResultSet rs = meta.getColumns(catalog, schemaPattern, tablePattern, "%")) {
            while (rs.next()) {
                cols.add(new ColumnInfo(
                        rs.getString("COLUMN_NAME"),
                        rs.getString("TYPE_NAME"),
                        rs.getInt("COLUMN_SIZE"),
                        // Unknown nullability is unsafe for row identity and
                        // must be treated as nullable.
                        rs.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls,
                        rs.getString("REMARKS"),
                        rs.getInt("ORDINAL_POSITION"),
                        rs.getString("COLUMN_DEF")
                ));
            }
        }
        return cols;
    }

    private String structureVersion(
            String schema,
            String table,
            String type,
            List<ColumnInfo> columns,
            List<IndexInfo> indexes,
            PrimaryKeyInfo primaryKey
    ) throws Exception {
        StringBuilder canonical = new StringBuilder();
        appendVersionValue(canonical, schema);
        appendVersionValue(canonical, table);
        appendVersionValue(canonical, type);
        columns.stream()
                .sorted(Comparator.comparingInt(ColumnInfo::ordinalPosition).thenComparing(ColumnInfo::name))
                .forEach(column -> {
                    appendVersionValue(canonical, column.name());
                    appendVersionValue(canonical, column.type());
                    appendVersionValue(canonical, String.valueOf(column.size()));
                    appendVersionValue(canonical, String.valueOf(column.nullable()));
                    appendVersionValue(canonical, column.defaultValue());
                });
        indexes.stream()
                .sorted(Comparator.comparing(IndexInfo::name, Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparingInt(IndexInfo::ordinalPosition)
                        .thenComparing(IndexInfo::columnName, Comparator.nullsFirst(Comparator.naturalOrder())))
                .forEach(index -> {
                    appendVersionValue(canonical, index.name());
                    appendVersionValue(canonical, index.columnName());
                    appendVersionValue(canonical, String.valueOf(index.unique()));
                    appendVersionValue(canonical, String.valueOf(index.ordinalPosition()));
                });
        appendVersionValue(canonical, primaryKey.name());
        primaryKey.columns().forEach(column -> appendVersionValue(canonical, column));
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    }

    private void appendVersionValue(StringBuilder target, String value) {
        String normalized = value == null ? "" : value;
        target.append(normalized.length()).append(':').append(normalized).append('|');
    }

    private List<IndexInfo> indexes(DatabaseMetaData meta, String catalog, String schema, String table) throws Exception {
        List<IndexInfo> indexes = new ArrayList<>();
        Set<String> unsupported = new HashSet<>();
        try (ResultSet rs = meta.getIndexInfo(catalog, schema, table, false, false)) {
            while (rs.next()) {
                String name = rs.getString("INDEX_NAME");
                String col = rs.getString("COLUMN_NAME");
                if (name == null) continue;
                String filterCondition = rs.getString("FILTER_CONDITION");
                if (col == null || filterCondition != null && !filterCondition.isBlank()) {
                    unsupported.add(name);
                    continue;
                }
                indexes.add(new IndexInfo(name, col, !rs.getBoolean("NON_UNIQUE"), rs.getInt("ORDINAL_POSITION")));
            }
        }
        return indexes.stream().filter(index -> !unsupported.contains(index.name())).toList();
    }

    private List<ObjectRelation> relations(ResultSet rs, DatabaseDialect dialect) throws Exception {
        try (rs) {
            List<ObjectRelation> relations = new ArrayList<>();
            while (rs.next()) {
                relations.add(new ObjectRelation(
                        rs.getString("FK_NAME"),
                        relationNamespace(rs, dialect, "PKTABLE"),
                        rs.getString("PKTABLE_NAME"),
                        rs.getString("PKCOLUMN_NAME"),
                        relationNamespace(rs, dialect, "FKTABLE"),
                        rs.getString("FKTABLE_NAME"),
                        rs.getString("FKCOLUMN_NAME")
                ));
            }
            return relations;
        }
    }

    private String relationNamespace(ResultSet rs, DatabaseDialect dialect, String prefix) throws Exception {
        String column = prefix + (dialect.namespaceKind() == DatabaseDialect.NamespaceKind.CATALOG ? "_CAT" : "_SCHEM");
        return rs.getString(column);
    }

    private void validateDesign(TableDesignRequest request) {
        validateIdentifier(request.tableName(), "表名");
        if (request.schemaName() != null && !request.schemaName().isBlank()) {
            validateIdentifier(request.schemaName(), "Schema");
        }
        if (request.columns() == null || request.columns().stream().noneMatch(column -> !column.deleted())) {
            throw new IllegalArgumentException("表设计至少需要保留一个字段。");
        }
        Set<String> columnNames = new LinkedHashSet<>();
        for (var column : request.columns()) {
            if (column.deleted()) continue;
            validateIdentifier(column.name(), "字段名");
            if (column.originalName() != null && !column.originalName().isBlank()) {
                validateIdentifier(column.originalName(), "原字段名");
            }
            if (column.type() == null || column.type().isBlank()) {
                throw new IllegalArgumentException("字段类型不能为空。");
            }
            validateSqlFragment(column.type(), "字段类型");
            if (column.defaultValue() != null && !column.defaultValue().isBlank()) {
                validateSqlFragment(column.defaultValue(), "默认值");
            }
            if (!columnNames.add(column.name())) {
                throw new IllegalArgumentException("字段名不能重复：" + column.name());
            }
        }
        if (request.primaryKeys() != null) {
            Set<String> primaryKeyNames = new HashSet<>();
            for (String primaryKey : request.primaryKeys()) {
                validateIdentifier(primaryKey, "主键字段");
                if (!columnNames.contains(primaryKey)) {
                    throw new IllegalArgumentException("主键字段不存在：" + primaryKey);
                }
                if (!primaryKeyNames.add(primaryKey)) {
                    throw new IllegalArgumentException("主键字段不能重复：" + primaryKey);
                }
            }
        }
        if (request.indexes() != null) {
            Set<String> indexNames = new HashSet<>();
            Set<String> originalIndexNames = new HashSet<>();
            for (var index : request.indexes()) {
                if (index.originalName() != null && !index.originalName().isBlank()) {
                    validateIdentifier(index.originalName(), "原索引名");
                    if (!originalIndexNames.add(index.originalName())) {
                        throw new IllegalArgumentException("同一个原索引不能重复编辑：" + index.originalName());
                    }
                }
                if (index.deleted()) {
                    continue;
                }
                validateIdentifier(index.name(), "索引名");
                if (!indexNames.add(index.name())) {
                    throw new IllegalArgumentException("索引名不能重复：" + index.name());
                }
                if (index.columns() == null || index.columns().isEmpty()) {
                    throw new IllegalArgumentException("索引至少需要一个字段。");
                }
                Set<String> indexColumns = new HashSet<>();
                for (String column : index.columns()) {
                    validateIdentifier(column, "索引字段");
                    if (!columnNames.contains(column)) {
                        throw new IllegalArgumentException("索引字段不存在：" + column);
                    }
                    if (!indexColumns.add(column)) {
                        throw new IllegalArgumentException("索引字段不能重复：" + column);
                    }
                }
            }
        }
    }

    private void validateIdentifier(String value, String label) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(label + "只能包含字母、数字、下划线、$、#，且不能以数字开头：" + value);
        }
    }

    private void validateSqlFragment(String value, String label) {
        if (value.contains(";") || value.contains("--") || value.contains("/*") || value.contains("*/")) {
            throw new IllegalArgumentException(label + "包含不允许的 SQL 控制字符。");
        }
    }

    private String qualifiedName(String schemaName, String tableName) {
        return schemaName == null || schemaName.isBlank() ? tableName : schemaName + "." + tableName;
    }

    private record PrimaryKeyInfo(List<String> columns, String name) {
    }

    private record DdlResult(String sql, String source) {
    }

    private record PageSlice<T>(List<T> items, int page, int pageSize, boolean hasMore) {
    }

    private enum MatchMode {
        EXACT,
        PREFIX,
        CONTAINS
    }

    public record RowIdentity(List<String> columns, String source, boolean stable) {
        public RowIdentity {
            columns = List.copyOf(columns);
        }
    }

}
