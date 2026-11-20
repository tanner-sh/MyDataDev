package com.example.dbadmin.service.migration.introspect;

import com.example.dbadmin.core.DatabaseDialect;
import com.example.dbadmin.core.SchemaObjectKind;
import com.example.dbadmin.core.SchemaObjectSql;
import com.example.dbadmin.dto.ApiDtos.SchemaObjectCapability;
import com.example.dbadmin.model.DbConnection;
import com.example.dbadmin.service.migration.model.CanonicalSchema;
import com.example.dbadmin.service.migration.model.CanonicalSchema.CanonicalCheckConstraint;
import com.example.dbadmin.service.migration.model.CanonicalSchema.CanonicalColumn;
import com.example.dbadmin.service.migration.model.CanonicalSchema.CanonicalForeignKey;
import com.example.dbadmin.service.migration.model.CanonicalSchema.CanonicalIndex;
import com.example.dbadmin.service.migration.model.CanonicalSchema.CanonicalProgramObject;
import com.example.dbadmin.service.migration.model.CanonicalSchema.CanonicalSequence;
import com.example.dbadmin.service.migration.model.CanonicalSchema.CanonicalTable;
import com.example.dbadmin.service.migration.model.CanonicalSchema.IdentitySpec;
import com.example.dbadmin.service.migration.model.CanonicalSchema.IndexColumn;
import com.example.dbadmin.service.migration.model.CanonicalSchema.IndexKind;
import com.example.dbadmin.service.migration.model.CanonicalSchema.LengthUnit;
import com.example.dbadmin.service.migration.model.DbTargetDialect;
import com.example.dbadmin.service.migration.model.MigrationOptions;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 通用 JDBC 元数据抽取基类与 H2/兜底实现。
 *
 * <p>同时封装：表筛选、稳定 {@code tableId} 生成、基于方言 {@link SchemaObjectSql} 的可编程对象抽取、
 * 外键动作解码等各家族复用的底层工具。</p>
 */
public class JdbcBaseIntrospector implements SchemaIntrospector {
    private static final int MAX_PROGRAM_OBJECTS_PER_KIND = 500;

    @Override
    public boolean supports(DbTargetDialect dialect) {
        return true;
    }

    @Override
    public CanonicalSchema introspect(
            Connection connection,
            DbConnection dbConnection,
            DatabaseDialect dialect,
            DbTargetDialect sourceDialect,
            String schemaName,
            MigrationOptions options,
            List<String> warnings
    ) throws Exception {
        DatabaseMetaData meta = connection.getMetaData();
        String version = safeVersion(meta);
        String resolvedSchema = resolveSchema(connection, dialect, sourceDialect, schemaName);
        List<String> tableNames = filterTables(listTablesJdbc(connection, meta, dialect, resolvedSchema), options.selectedTables());
        List<CanonicalTable> tables = new ArrayList<>();
        int seq = 1;
        for (String tableName : tableNames) {
            tables.add(readTableJdbc(connection, meta, dialect, sourceDialect, resolvedSchema, tableName, makeTableId(seq++, tableName)));
        }
        List<CanonicalSequence> sequences = readSequences(connection, sourceDialect, resolvedSchema, warnings);
        List<CanonicalProgramObject> programObjects = readProgramObjects(connection, dialect, resolvedSchema, options, warnings);
        return new CanonicalSchema(
                CanonicalSchema.CURRENT_FORMAT_VERSION,
                sourceDialect.code(),
                sourceDialect.family().name(),
                version,
                detectCharset(connection, sourceDialect),
                resolvedSchema,
                captureSnapshotToken(connection, sourceDialect, options, warnings),
                List.copyOf(tables),
                List.copyOf(sequences),
                List.copyOf(programObjects)
        );
    }

    protected List<CanonicalSequence> readSequences(Connection connection, DbTargetDialect sourceDialect, String schema, List<String> warnings) {
        if (sourceDialect != DbTargetDialect.H2) return List.of();
        List<CanonicalSequence> result = new ArrayList<>();
        String sql = "SELECT SEQUENCE_NAME, BASE_VALUE, INCREMENT, MIN_VALUE, MAX_VALUE, CYCLE_OPTION, CACHE "
                + "FROM INFORMATION_SCHEMA.SEQUENCES WHERE SEQUENCE_SCHEMA = ? ORDER BY SEQUENCE_NAME";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, schema == null ? "PUBLIC" : schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new CanonicalSequence(
                            rs.getString("SEQUENCE_NAME"),
                            rs.getLong("BASE_VALUE"),
                            rs.getLong("INCREMENT"),
                            getLongOrNull(rs, "MIN_VALUE"),
                            getLongOrNull(rs, "MAX_VALUE"),
                            "YES".equalsIgnoreCase(rs.getString("CYCLE_OPTION")),
                            getLongOrNull(rs, "CACHE"),
                            null,
                            null
                    ));
                }
            }
        } catch (Exception ignored) {
            // H2 兼容版本差异，查不到时忽略
        }
        return result;
    }

    protected String detectCharset(Connection connection, DbTargetDialect sourceDialect) {
        return "UTF-8";
    }

    protected String captureSnapshotToken(Connection connection, DbTargetDialect sourceDialect, MigrationOptions options, List<String> warnings) {
        return null;
    }

    protected CanonicalTable readTableJdbc(
            Connection connection,
            DatabaseMetaData meta,
            DatabaseDialect dialect,
            DbTargetDialect sourceDialect,
            String schema,
            String tableName,
            String tableId
    ) throws Exception {
        DatabaseDialect.MetadataScope scope = dialect.metadataScope(connection, schema);
        String exactSchema = escapePattern(meta, scope.schemaPattern());
        String exactTable = escapePattern(meta, tableName);

        String tableComment = null;
        try (ResultSet rs = meta.getTables(scope.catalog(), exactSchema, exactTable, new String[]{"TABLE"})) {
            if (rs.next()) {
                tableComment = blankToNull(rs.getString("REMARKS"));
            }
        } catch (SQLException ignored) {
        }

        List<CanonicalColumn> columns = new ArrayList<>();
        try (ResultSet rs = meta.getColumns(scope.catalog(), exactSchema, exactTable, "%")) {
            while (rs.next()) {
                String colName = rs.getString("COLUMN_NAME");
                String typeName = rs.getString("TYPE_NAME");
                int jdbcType = rs.getInt("DATA_TYPE");
                int colSize = rs.getInt("COLUMN_SIZE");
                Integer decimalDigits = getIntOrNull(rs, "DECIMAL_DIGITS");
                boolean nullable = rs.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls;
                String defaultVal = rs.getString("COLUMN_DEF");
                String remarks = blankToNull(rs.getString("REMARKS"));
                boolean autoInc = "YES".equalsIgnoreCase(safeString(rs, "IS_AUTOINCREMENT"));

                var normalized = CanonicalTypeNormalizer.normalize(
                        sourceDialect, typeName, jdbcType, colSize > 0 ? colSize : null,
                        LengthUnit.NONE, colSize > 0 ? colSize : null, decimalDigits, false, false);
                var def = CanonicalTypeNormalizer.normalizeDefault(autoInc ? null : defaultVal);
                IdentitySpec identity = autoInc ? new IdentitySpec(false, 1, 1) : null;
                columns.add(new CanonicalColumn(
                        colName, normalized.kind(), typeName, normalized.length(), normalized.lengthUnit(),
                        normalized.precision(), normalized.scale(), normalized.unboundedDecimal(),
                        normalized.unsigned(), normalized.national(), normalized.fractionalSeconds(),
                        nullable, def.kind(), def.raw(), identity, null, remarks
                ));
            }
        }

        TreeMap<Short, String> pkMap = new TreeMap<>();
        String pkName = null;
        Set<String> pkNames = new LinkedHashSet<>();
        try (ResultSet rs = meta.getPrimaryKeys(scope.catalog(), scope.schemaPattern(), tableName)) {
            while (rs.next()) {
                pkMap.put(rs.getShort("KEY_SEQ"), rs.getString("COLUMN_NAME"));
                String candidatePk = rs.getString("PK_NAME");
                if (candidatePk != null && !candidatePk.isBlank()) {
                    pkName = candidatePk;
                    pkNames.add(candidatePk.toUpperCase(Locale.ROOT));
                }
            }
        } catch (SQLException ignored) {
        }
        List<String> pkColumns = List.copyOf(pkMap.values());

        Map<String, IndexAcc> idxMap = new LinkedHashMap<>();
        try (ResultSet rs = meta.getIndexInfo(scope.catalog(), scope.schemaPattern(), tableName, false, true)) {
            while (rs.next()) {
                String idxName = rs.getString("INDEX_NAME");
                String colName = rs.getString("COLUMN_NAME");
                if (idxName == null || colName == null) continue;
                if (pkNames.contains(idxName.toUpperCase(Locale.ROOT))) continue;
                IndexAcc acc = idxMap.computeIfAbsent(idxName, k -> new IndexAcc(!safeBoolean(rs, "NON_UNIQUE")));
                boolean desc = "D".equalsIgnoreCase(safeString(rs, "ASC_OR_DESC"));
                acc.columns.put(rs.getShort("ORDINAL_POSITION"), new IndexColumn(colName, desc, null, null));
            }
        } catch (SQLException ignored) {
        }
        List<CanonicalIndex> indexes = new ArrayList<>();
        for (var entry : idxMap.entrySet()) {
            List<IndexColumn> idxCols = List.copyOf(entry.getValue().columns.values());
            if (isPrimaryBackingIndex(pkColumns, entry.getValue().unique, idxCols)) continue;
            indexes.add(new CanonicalIndex(entry.getKey(), idxCols, entry.getValue().unique, IndexKind.BTREE, null));
        }

        Map<String, FkAcc> fkMap = new LinkedHashMap<>();
        try (ResultSet rs = meta.getImportedKeys(scope.catalog(), scope.schemaPattern(), tableName)) {
            while (rs.next()) {
                String fkName = rs.getString("FK_NAME");
                String key = fkName == null || fkName.isBlank() ? "fk_" + tableName + "_" + fkMap.size() : fkName;
                String refSchema = sourceDialect.catalogNamespace() ? rs.getString("PKTABLE_CAT") : rs.getString("PKTABLE_SCHEM");
                String refTable = rs.getString("PKTABLE_NAME");
                FkAcc acc = fkMap.computeIfAbsent(key, k -> new FkAcc(
                        refSchema, refTable,
                        decodeFkRule(safeShort(rs, "UPDATE_RULE")),
                        decodeFkRule(safeShort(rs, "DELETE_RULE"))
                ));
                short seqNo = rs.getShort("KEY_SEQ");
                acc.local.put(seqNo, rs.getString("FKCOLUMN_NAME"));
                acc.ref.put(seqNo, rs.getString("PKCOLUMN_NAME"));
            }
        } catch (SQLException ignored) {
        }
        List<CanonicalForeignKey> foreignKeys = new ArrayList<>();
        for (var entry : fkMap.entrySet()) {
            FkAcc acc = entry.getValue();
            foreignKeys.add(new CanonicalForeignKey(
                    entry.getKey(), List.copyOf(acc.local.values()), acc.refSchema, acc.refTable,
                    List.copyOf(acc.ref.values()), acc.onUpdate, acc.onDelete
            ));
        }

        return new CanonicalTable(
                tableId, tableName, tableComment, List.copyOf(columns), pkName, pkColumns,
                List.copyOf(indexes), List.copyOf(foreignKeys), List.of(), null, null, null
        );
    }

    protected List<String> listTablesJdbc(Connection connection, DatabaseMetaData meta, DatabaseDialect dialect, String schema) throws SQLException {
        DatabaseDialect.MetadataScope scope = dialect.metadataScope(connection, schema);
        List<String> names = new ArrayList<>();
        try (ResultSet rs = meta.getTables(scope.catalog(), escapePattern(meta, scope.schemaPattern()), "%", new String[]{"TABLE"})) {
            while (rs.next()) {
                String name = rs.getString("TABLE_NAME");
                if (name != null && !name.isBlank() && !name.startsWith("BIN$")) {
                    names.add(name);
                }
            }
        }
        names.sort(String::compareToIgnoreCase);
        return names;
    }

    /** 复用各方言挂在 {@link DatabaseDialect#schemaObjects()} 上的对象目录查询提取可编程对象源码与依赖。 */
    protected List<CanonicalProgramObject> readProgramObjects(
            Connection connection,
            DatabaseDialect dialect,
            String schema,
            MigrationOptions options,
            List<String> warnings
    ) {
        // 未勾选迁移视图/过程/触发器时跳过 N+1 对象源码抽取，避免大型 ERP 库评估阶段多出上千次往返
        if (options != null && !options.programObjectsEnabled()) {
            return List.of();
        }
        SchemaObjectSql objectSql = dialect.schemaObjects();
        if (objectSql == null || objectSql == SchemaObjectSql.NONE) {
            return List.of();
        }
        List<CanonicalProgramObject> objects = new ArrayList<>();
        Set<String> supportedKinds = new LinkedHashSet<>();
        for (SchemaObjectCapability cap : dialect.capabilities().schemaObjects()) {
            if (cap.operations().contains("LIST") && cap.operations().contains("DETAIL")) {
                supportedKinds.add(cap.kind());
            }
        }
        for (String kindName : supportedKinds) {
            SchemaObjectKind kind;
            try {
                kind = SchemaObjectKind.parse(kindName);
            } catch (IllegalArgumentException ignored) {
                continue;
            }
            if (kind == SchemaObjectKind.SEQUENCE) continue; // 序列单独在 sequences 中结构化管理
            SchemaObjectSql.Query listQuery = objectSql.listQuery(schema, kind);
            if (listQuery == null) continue;
            try (PreparedStatement ps = connection.prepareStatement(listQuery.sql())) {
                bindParams(ps, listQuery.parameters());
                ps.setMaxRows(MAX_PROGRAM_OBJECTS_PER_KIND);
                List<SchemaObjectSql.ObjectRef> refs = new ArrayList<>();
                Map<String, String> statusByName = new LinkedHashMap<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String objSchema = safeString(rs, "schema_name");
                        String objName = safeString(rs, "object_name");
                        String specificName = safeString(rs, "specific_name");
                        String subtype = safeString(rs, "subtype");
                        String status = safeString(rs, "object_status");
                        if (objName == null || objName.isBlank()) continue;
                        refs.add(new SchemaObjectSql.ObjectRef(objSchema == null ? schema : objSchema, objName, specificName, subtype));
                        statusByName.put(objName, status == null || status.isBlank() ? "VALID" : status);
                    }
                }
                for (SchemaObjectSql.ObjectRef ref : refs) {
                    String source = fetchObjectSource(connection, dialect, objectSql, kind, ref);
                    if (source == null || source.isBlank()) continue;
                    List<String> deps = fetchObjectDependencies(connection, objectSql, kind, ref, schema);
                    objects.add(new CanonicalProgramObject(
                            kind.name(), ref.name(), source.trim(), deps, statusByName.getOrDefault(ref.name(), "VALID")
                    ));
                }
            } catch (Exception error) {
                warnings.add("读取 " + kind.label() + " 清单失败（已跳过）：" + shortMessage(error));
            }
        }
        return objects;
    }

    private String fetchObjectSource(Connection connection, DatabaseDialect dialect, SchemaObjectSql objectSql,
                                     SchemaObjectKind kind, SchemaObjectSql.ObjectRef ref) {
        SchemaObjectSql.Source src = objectSql.source(dialect, kind, ref);
        if (src == null) return null;
        try (PreparedStatement ps = connection.prepareStatement(src.sql())) {
            bindParams(ps, src.parameters());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                String body = null;
                if (src.showCreate()) {
                    int cols = rs.getMetaData().getColumnCount();
                    for (int i = 1; i <= cols; i++) {
                        String val = rs.getString(i);
                        if (val != null && val.stripLeading().toUpperCase(Locale.ROOT).startsWith("CREATE")) {
                            body = val;
                            break;
                        }
                    }
                } else {
                    body = rs.getString(1);
                }
                if (body == null || body.isBlank()) return null;
                return src.prefix() + body + src.suffix();
            }
        } catch (Exception ignored) {
            return null;
        }
    }

    private List<String> fetchObjectDependencies(Connection connection, SchemaObjectSql objectSql,
                                                 SchemaObjectKind kind, SchemaObjectSql.ObjectRef ref, String schema) {
        SchemaObjectSql.Query depQuery = objectSql.dependenciesQuery(kind, ref);
        if (depQuery == null) return List.of();
        Set<String> deps = new LinkedHashSet<>();
        try (PreparedStatement ps = connection.prepareStatement(depQuery.sql())) {
            bindParams(ps, depQuery.parameters());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String depSchema = rs.getString(1);
                    String depName = rs.getString(2);
                    if (depName == null || depName.isBlank()) continue;
                    if (depSchema == null || schema == null || depSchema.equalsIgnoreCase(schema)) {
                        deps.add(depName);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return List.copyOf(deps);
    }

    protected static String resolveSchema(Connection connection, DatabaseDialect dialect, DbTargetDialect sourceDialect, String requested) throws Exception {
        if (sourceDialect == DbTargetDialect.SQLITE) return "";
        if (requested != null && !requested.isBlank()) return requested.trim();
        String current = dialect.currentSchema(connection);
        return current == null ? "" : current.trim();
    }

    protected static List<String> filterTables(List<String> allTables, List<String> selectedTables) {
        if (selectedTables == null || selectedTables.isEmpty()) return allTables;
        Set<String> wanted = new LinkedHashSet<>();
        for (String name : selectedTables) {
            if (name != null && !name.isBlank()) wanted.add(name.trim().toLowerCase(Locale.ROOT));
        }
        if (wanted.isEmpty()) return allTables;
        return allTables.stream().filter(t -> wanted.contains(t.toLowerCase(Locale.ROOT))).toList();
    }

    protected static String makeTableId(int index, String tableName) {
        String safe = tableName == null ? "tbl" : tableName.replaceAll("[^a-zA-Z0-9_]", "_");
        if (safe.length() > 36) safe = safe.substring(0, 36);
        if (safe.isBlank()) safe = "tbl";
        return String.format(Locale.ROOT, "t%04d_%s", index, safe.toLowerCase(Locale.ROOT));
    }

    protected static boolean isPrimaryBackingIndex(List<String> pkColumns, boolean unique, List<IndexColumn> idxColumns) {
        if (!unique || pkColumns == null || pkColumns.isEmpty() || idxColumns.size() != pkColumns.size()) return false;
        for (int i = 0; i < pkColumns.size(); i++) {
            String colName = idxColumns.get(i).name();
            if (colName == null || !colName.equalsIgnoreCase(pkColumns.get(i))) return false;
        }
        return true;
    }

    protected static String decodeFkRule(short rule) {
        return switch (rule) {
            case DatabaseMetaData.importedKeyCascade -> "CASCADE";
            case DatabaseMetaData.importedKeySetNull -> "SET NULL";
            case DatabaseMetaData.importedKeySetDefault -> "SET DEFAULT";
            case DatabaseMetaData.importedKeyRestrict -> "RESTRICT";
            default -> "NO ACTION";
        };
    }

    protected static String escapePattern(DatabaseMetaData meta, String value) throws SQLException {
        if (value == null) return null;
        String escape = meta.getSearchStringEscape();
        if (escape == null || escape.isEmpty()) return value;
        return value.replace(escape, escape + escape).replace("_", escape + "_").replace("%", escape + "%");
    }

    protected static String safeVersion(DatabaseMetaData meta) {
        try {
            String product = meta.getDatabaseProductName();
            String ver = meta.getDatabaseProductVersion();
            return (product == null ? "" : product.trim()) + (ver == null ? "" : " " + ver.trim());
        } catch (Exception ignored) {
            return "";
        }
    }

    protected static PreparedStatement prepareMetaQuery(Connection connection, String sql) throws SQLException {
        PreparedStatement ps = connection.prepareStatement(sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
        try {
            ps.setFetchSize(2000);
        } catch (SQLException ignored) {
            // 部分轻量驱动（如 SQLite）不支持大 fetchSize，静默忽略
        }
        return ps;
    }

    protected static void bindParams(PreparedStatement ps, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            ps.setObject(i + 1, params.get(i));
        }
    }

    protected static String safeString(ResultSet rs, String col) {
        try { return rs.getString(col); } catch (Exception ignored) { return null; }
    }

    protected static boolean safeBoolean(ResultSet rs, String col) {
        try { return rs.getBoolean(col); } catch (Exception ignored) { return false; }
    }

    protected static short safeShort(ResultSet rs, String col) {
        try { return rs.getShort(col); } catch (Exception ignored) { return (short) DatabaseMetaData.importedKeyNoAction; }
    }

    protected static Integer getIntOrNull(ResultSet rs, String col) {
        try {
            Object val = rs.getObject(col);
            return val instanceof Number n ? n.intValue() : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    protected static Long getLongOrNull(ResultSet rs, String col) {
        try {
            Object val = rs.getObject(col);
            return val instanceof Number n ? n.longValue() : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    protected static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    protected static String shortMessage(Throwable error) {
        String msg = error.getMessage();
        if (msg == null || msg.isBlank()) return error.getClass().getSimpleName();
        String oneLine = msg.replaceAll("\\R", " ").trim();
        return oneLine.length() > 300 ? oneLine.substring(0, 300) + "…" : oneLine;
    }

    private static final class IndexAcc {
        final boolean unique;
        final TreeMap<Short, IndexColumn> columns = new TreeMap<>();
        IndexAcc(boolean unique) { this.unique = unique; }
    }

    private static final class FkAcc {
        final String refSchema;
        final String refTable;
        final String onUpdate;
        final String onDelete;
        final TreeMap<Short, String> local = new TreeMap<>();
        final TreeMap<Short, String> ref = new TreeMap<>();
        FkAcc(String refSchema, String refTable, String onUpdate, String onDelete) {
            this.refSchema = refSchema;
            this.refTable = refTable;
            this.onUpdate = onUpdate;
            this.onDelete = onDelete;
        }
    }
}
