package com.example.dbadmin.service;

import com.example.dbadmin.core.DatabaseDialect;
import com.example.dbadmin.core.DialectRegistry;
import com.example.dbadmin.dto.ApiDtos.ColumnInfo;
import com.example.dbadmin.dto.ApiDtos.DbObject;
import com.example.dbadmin.dto.ApiDtos.IndexInfo;
import com.example.dbadmin.dto.ApiDtos.MetadataResponse;
import com.example.dbadmin.dto.ApiDtos.ObjectDetail;
import com.example.dbadmin.dto.ApiDtos.ObjectRelation;
import com.example.dbadmin.dto.ApiDtos.ObjectRelations;
import com.example.dbadmin.dto.ApiDtos.ObjectStructure;
import com.example.dbadmin.dto.ApiDtos.TableDesignRequest;
import com.example.dbadmin.dto.ApiDtos.TableDesignResponse;
import com.example.dbadmin.model.DbConnection;
import com.example.dbadmin.repo.AuditRepository;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class MetadataService {
    private final ConnectionService connections;
    private final DialectRegistry dialectRegistry;
    private final AuditRepository audit;
    private final MetadataCacheService cache;
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_$#]*");

    public MetadataService(ConnectionService connections, DialectRegistry dialectRegistry, AuditRepository audit, MetadataCacheService cache) {
        this.connections = connections;
        this.dialectRegistry = dialectRegistry;
        this.audit = audit;
        this.cache = cache;
    }

    public MetadataResponse inspect(long connectionId, String schemaFilter, String keyword, Integer page, Integer pageSize, boolean refresh) throws Exception {
        if (refresh) {
            cache.evictConnection(connectionId);
        }
        var cached = cache.metadata(connectionId);
        boolean cacheHit = cached.isPresent();
        MetadataCacheService.MetadataSnapshot snapshot = cached.orElse(null);
        if (snapshot == null) {
            snapshot = loadMetadataSnapshot(connectionId);
        }
        List<DbObject> filtered = filterObjects(snapshot.objects(), schemaFilter, keyword);
        int normalizedPage = Math.max(page == null ? 0 : page, 0);
        int normalizedPageSize = Math.min(Math.max(pageSize == null ? 200 : pageSize, 1), 500);
        int offset = normalizedPage * normalizedPageSize;
        List<DbObject> objects = filtered.stream().skip(offset).limit(normalizedPageSize).toList();
        boolean hasMore = filtered.size() > offset + objects.size();
        return new MetadataResponse(snapshot.schemas(), objects, normalizedPage, normalizedPageSize, hasMore, snapshot.cachedAt().toString(), cacheHit);
    }

    private MetadataCacheService.MetadataSnapshot loadMetadataSnapshot(long connectionId) throws Exception {
        try (Connection connection = connections.open(connectionId)) {
            DatabaseMetaData meta = connection.getMetaData();
            List<String> schemas = schemas(meta);
            List<DbObject> objects = new ArrayList<>();
            try (ResultSet rs = meta.getTables(connection.getCatalog(), null, "%", new String[]{"TABLE", "VIEW"})) {
                while (rs.next()) {
                    String schema = rs.getString("TABLE_SCHEM");
                    String name = rs.getString("TABLE_NAME");
                    String type = rs.getString("TABLE_TYPE");
                    if (isSystemSchema(schema)) {
                        continue;
                    }
                    objects.add(new DbObject(schema, name, type, List.of(), List.of()));
                }
            }
            return cache.putMetadata(connectionId, schemas, objects);
        }
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
            String catalog = connection.getCatalog();
            DbObject object = findObject(meta, catalog, schemaName, objectName);
            ObjectStructure structure = new ObjectStructure(
                    object.schemaName(),
                    object.name(),
                    object.type(),
                    columns(meta, catalog, object.schemaName(), object.name()),
                    indexes(meta, catalog, object.schemaName(), object.name())
            );
            cache.putStructure(connectionId, object.schemaName(), object.name(), structure);
            return structure;
        }
    }

    public List<String> primaryOrUniqueColumns(long connectionId, String schema, String table) throws Exception {
        try (Connection connection = connections.open(connectionId)) {
            DatabaseMetaData meta = connection.getMetaData();
            List<String> cols = primaryKeys(meta, connection.getCatalog(), schema, table);
            if (!cols.isEmpty()) {
                return cols;
            }
            try (ResultSet rs = meta.getIndexInfo(connection.getCatalog(), schema, table, true, false)) {
                while (rs.next()) {
                    String col = rs.getString("COLUMN_NAME");
                    if (col != null && !cols.contains(col)) {
                        cols.add(col);
                    }
                }
            }
            return cols;
        }
    }

    public ObjectDetail detail(long connectionId, String schemaName, String objectName) throws Exception {
        return detail(connectionId, schemaName, objectName, false);
    }

    public ObjectDetail detail(long connectionId, String schemaName, String objectName, boolean refresh) throws Exception {
        if (!refresh) {
            var cached = cache.detail(connectionId, schemaName, objectName);
            if (cached.isPresent()) {
                return cached.get();
            }
        }
        try (Connection connection = connections.open(connectionId)) {
            DatabaseMetaData meta = connection.getMetaData();
            String catalog = connection.getCatalog();
            DbObject object = findObject(meta, catalog, schemaName, objectName);
            List<ColumnInfo> cols = columns(meta, catalog, object.schemaName(), object.name());
            List<IndexInfo> idx = indexes(meta, catalog, object.schemaName(), object.name());
            PrimaryKeyInfo pk = primaryKeyInfo(meta, catalog, object.schemaName(), object.name());
            Long rowCount = isView(object.type()) ? null : rowCount(connection, object.schemaName(), object.name());
            DbConnection dbConnection = connections.require(connectionId);
            DatabaseDialect dialect = dialectRegistry.dialectFor(dbConnection);
            DdlResult ddl = ddl(connection, dialect, object.schemaName(), object.name(), object.type(), cols, pk.columns());
            ObjectDetail detail = new ObjectDetail(object.schemaName(), object.name(), object.type(), cols, idx, pk.columns(), pk.name(), rowCount, ddl.sql(), ddl.source());
            cache.putDetail(connectionId, object.schemaName(), object.name(), detail);
            return detail;
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
            String catalog = connection.getCatalog();
            DbObject object = findObject(meta, catalog, schemaName, objectName);
            ObjectRelations relations = new ObjectRelations(
                    relations(meta.getImportedKeys(catalog, object.schemaName(), object.name())),
                    relations(meta.getExportedKeys(catalog, object.schemaName(), object.name()))
            );
            cache.putRelations(connectionId, object.schemaName(), object.name(), relations);
            return relations;
        }
    }

    public TableDesignResponse previewDesign(long connectionId, TableDesignRequest request) throws Exception {
        List<String> sql = designSql(connectionId, request);
        return new TableDesignResponse(sql, sql.isEmpty() ? "没有检测到结构变更。" : "已生成 " + sql.size() + " 条 DDL。");
    }

    public TableDesignResponse executeDesign(long connectionId, TableDesignRequest request, String actor) throws Exception {
        DbConnection dbConnection = connections.require(connectionId);
        if (dbConnection.readonly()) {
            throw new IllegalStateException("只读连接不允许执行表结构变更。");
        }
        String expected = qualifiedName(request.schemaName(), request.tableName());
        if (!expected.equals(request.confirmation())) {
            throw new IllegalArgumentException("确认文本不匹配，请输入完整表名：" + expected);
        }
        List<String> sql = designSql(connectionId, request);
        if (sql.isEmpty()) {
            return new TableDesignResponse(List.of(), "没有检测到结构变更。");
        }
        try (Connection connection = connections.open(connectionId); Statement statement = connection.createStatement()) {
            for (String line : sql) {
                statement.execute(line);
            }
        }
        cache.evictObject(connectionId, request.schemaName(), request.tableName());
        audit.log(actor, "TABLE_DESIGN_EXECUTE", "connection:" + connectionId + " table:" + expected, String.join("\n", sql));
        return new TableDesignResponse(sql, "已执行 " + sql.size() + " 条 DDL。");
    }

    private List<String> designSql(long connectionId, TableDesignRequest request) throws Exception {
        validateDesign(request);
        ObjectDetail original = detail(connectionId, request.schemaName(), request.tableName());
        DbConnection dbConnection = connections.require(connectionId);
        DatabaseDialect dialect = dialectRegistry.dialectFor(dbConnection);
        return dialect.alterTableSql(original.schemaName(), original.name(), original, request);
    }

    private List<String> schemas(DatabaseMetaData meta) throws Exception {
        List<String> schemas = new ArrayList<>();
        try (ResultSet rs = meta.getSchemas()) {
            while (rs.next() && schemas.size() < 500) {
                String schema = rs.getString("TABLE_SCHEM");
                if (!isSystemSchema(schema)) {
                    schemas.add(schema);
                }
            }
        }
        return schemas;
    }

    private boolean isSystemSchema(String schema) {
        if (schema == null) {
            return false;
        }
        String s = schema.toUpperCase();
        return s.equals("INFORMATION_SCHEMA")
                || s.equals("SYS")
                || s.equals("SYSTEM")
                || s.equals("PG_CATALOG")
                || s.equals("SQLJ")
                || s.startsWith("SYS_")
                || s.startsWith("MYSQL");
    }

    private boolean matchesKeyword(String schema, String name, String keyword) {
        if (keyword == null) {
            return true;
        }
        String objectName = name == null ? "" : name.toLowerCase(Locale.ROOT);
        String qualifiedName = schema == null || schema.isBlank()
                ? objectName
                : (schema + "." + name).toLowerCase(Locale.ROOT);
        return objectName.contains(keyword) || qualifiedName.contains(keyword);
    }

    private List<DbObject> filterObjects(List<DbObject> objects, String schemaFilter, String keyword) {
        String normalizedSchema = schemaFilter == null || schemaFilter.isBlank() ? null : schemaFilter.toLowerCase(Locale.ROOT);
        String normalizedKeyword = keyword == null || keyword.isBlank() ? null : keyword.toLowerCase(Locale.ROOT);
        return objects.stream()
                .filter(object -> normalizedSchema == null || normalizedSchema.equals((object.schemaName() == null ? "" : object.schemaName()).toLowerCase(Locale.ROOT)))
                .filter(object -> matchesKeyword(object.schemaName(), object.name(), normalizedKeyword))
                .toList();
    }

    private DbObject findObject(DatabaseMetaData meta, String catalog, String schema, String objectName) throws Exception {
        String schemaPattern = schema == null || schema.isBlank() ? null : schema;
        try (ResultSet rs = meta.getTables(catalog, schemaPattern, objectName, new String[]{"TABLE", "VIEW"})) {
            while (rs.next()) {
                String foundSchema = rs.getString("TABLE_SCHEM");
                String foundName = rs.getString("TABLE_NAME");
                String type = rs.getString("TABLE_TYPE");
                if (!isSystemSchema(foundSchema)) {
                    return new DbObject(foundSchema, foundName, type, List.of(), List.of());
                }
            }
        }
        try (ResultSet rs = meta.getTables(catalog, schemaPattern, "%", new String[]{"TABLE", "VIEW"})) {
            while (rs.next()) {
                String foundSchema = rs.getString("TABLE_SCHEM");
                String foundName = rs.getString("TABLE_NAME");
                String type = rs.getString("TABLE_TYPE");
                if (!isSystemSchema(foundSchema) && foundName != null && foundName.equalsIgnoreCase(objectName)) {
                    return new DbObject(foundSchema, foundName, type, List.of(), List.of());
                }
            }
        }
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

    private Long rowCount(Connection connection, String schema, String table) {
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM " + table(schema, table))) {
            return rs.next() ? rs.getLong(1) : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private DdlResult ddl(Connection connection, DatabaseDialect dialect, String schema, String table, String type, List<ColumnInfo> columns, List<String> primaryKeys) throws Exception {
        var nativeDdl = dialect.nativeDdl(connection, schema, table, type);
        if (nativeDdl.isPresent() && !nativeDdl.get().isBlank()) {
            return new DdlResult(nativeDdl.get(), "NATIVE");
        }
        return new DdlResult(generatedDdl(schema, table, type, columns, primaryKeys), "GENERATED");
    }

    private String generatedDdl(String schema, String table, String type, List<ColumnInfo> columns, List<String> primaryKeys) {
        if (isView(type)) {
            return "-- 视图定义反查暂未实现。\nCREATE VIEW " + table(schema, table) + " AS\n-- 请使用数据库原生工具查看完整视图定义。";
        }
        List<String> lines = new ArrayList<>();
        for (ColumnInfo column : columns) {
            lines.add("  " + quote(column.name()) + " " + columnType(column) + (column.nullable() ? "" : " NOT NULL"));
        }
        if (!primaryKeys.isEmpty()) {
            lines.add("  PRIMARY KEY (" + String.join(", ", primaryKeys.stream().map(this::quote).toList()) + ")");
        }
        return "CREATE TABLE " + table(schema, table) + " (\n" + String.join(",\n", lines) + "\n);";
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

    private String table(String schema, String table) {
        return schema == null || schema.isBlank()
                ? quote(table)
                : quote(schema) + "." + quote(table);
    }

    private String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private List<ColumnInfo> columns(DatabaseMetaData meta, String catalog, String schema, String table) throws Exception {
        List<ColumnInfo> cols = new ArrayList<>();
        try (ResultSet rs = meta.getColumns(catalog, schema, table, "%")) {
            while (rs.next()) {
                cols.add(new ColumnInfo(
                        rs.getString("COLUMN_NAME"),
                        rs.getString("TYPE_NAME"),
                        rs.getInt("COLUMN_SIZE"),
                        rs.getInt("NULLABLE") == DatabaseMetaData.columnNullable,
                        rs.getString("REMARKS"),
                        rs.getInt("ORDINAL_POSITION"),
                        rs.getString("COLUMN_DEF")
                ));
            }
        }
        return cols;
    }

    private List<IndexInfo> indexes(DatabaseMetaData meta, String catalog, String schema, String table) throws Exception {
        List<IndexInfo> indexes = new ArrayList<>();
        try (ResultSet rs = meta.getIndexInfo(catalog, schema, table, false, false)) {
            while (rs.next()) {
                String name = rs.getString("INDEX_NAME");
                String col = rs.getString("COLUMN_NAME");
                if (name != null && col != null) {
                    indexes.add(new IndexInfo(name, col, !rs.getBoolean("NON_UNIQUE")));
                }
            }
        }
        return indexes;
    }

    private List<ObjectRelation> relations(ResultSet rs) throws Exception {
        try (rs) {
            List<ObjectRelation> relations = new ArrayList<>();
            while (rs.next()) {
                relations.add(new ObjectRelation(
                        rs.getString("FK_NAME"),
                        rs.getString("PKTABLE_SCHEM"),
                        rs.getString("PKTABLE_NAME"),
                        rs.getString("PKCOLUMN_NAME"),
                        rs.getString("FKTABLE_SCHEM"),
                        rs.getString("FKTABLE_NAME"),
                        rs.getString("FKCOLUMN_NAME")
                ));
            }
            return relations;
        }
    }

    private void validateDesign(TableDesignRequest request) {
        validateIdentifier(request.tableName(), "表名");
        if (request.schemaName() != null && !request.schemaName().isBlank()) {
            validateIdentifier(request.schemaName(), "Schema");
        }
        if (request.columns() == null || request.columns().stream().noneMatch(column -> !column.deleted())) {
            throw new IllegalArgumentException("表设计至少需要保留一个字段。");
        }
        Set<String> columnNames = request.columns().stream()
                .filter(column -> !column.deleted())
                .peek(column -> {
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
                })
                .map(column -> column.name().toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        if (columnNames.size() != request.columns().stream().filter(column -> !column.deleted()).count()) {
            throw new IllegalArgumentException("字段名不能重复。");
        }
        if (request.primaryKeys() != null) {
            for (String primaryKey : request.primaryKeys()) {
                validateIdentifier(primaryKey, "主键字段");
                if (!columnNames.contains(primaryKey.toLowerCase(Locale.ROOT))) {
                    throw new IllegalArgumentException("主键字段不存在：" + primaryKey);
                }
            }
        }
        if (request.indexes() != null) {
            for (var index : request.indexes()) {
                if (index.deleted()) {
                    if (index.originalName() != null && !index.originalName().isBlank()) {
                        validateIdentifier(index.originalName(), "原索引名");
                    }
                    continue;
                }
                validateIdentifier(index.name(), "索引名");
                if (index.originalName() != null && !index.originalName().isBlank()) {
                    validateIdentifier(index.originalName(), "原索引名");
                }
                if (index.columns() == null || index.columns().isEmpty()) {
                    throw new IllegalArgumentException("索引至少需要一个字段。");
                }
                for (String column : index.columns()) {
                    validateIdentifier(column, "索引字段");
                    if (!columnNames.contains(column.toLowerCase(Locale.ROOT))) {
                        throw new IllegalArgumentException("索引字段不存在：" + column);
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

}
