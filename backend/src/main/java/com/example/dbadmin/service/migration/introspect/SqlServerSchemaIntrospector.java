package com.example.dbadmin.service.migration.introspect;

import com.example.dbadmin.core.DatabaseDialect;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * SQL Server 深度元数据抽取器（sys.tables / sys.columns / sys.identity_columns / sys.extended_properties / sys.sequences）。
 */
public class SqlServerSchemaIntrospector extends JdbcBaseIntrospector {
    @Override
    public boolean supports(DbTargetDialect dialect) {
        return dialect == DbTargetDialect.SQLSERVER;
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
        if (resolvedSchema.isBlank()) resolvedSchema = "dbo";

        Map<String, String> tableComments = loadTableComments(connection, resolvedSchema);
        List<String> tableNames = loadTableNames(connection, resolvedSchema);
        if (tableNames.isEmpty()) tableNames = listTablesJdbc(connection, meta, dialect, resolvedSchema);
        tableNames = filterTables(tableNames, options.selectedTables());

        Map<String, List<CanonicalColumn>> colsByTable = loadColumns(connection, resolvedSchema);
        Map<String, PkInfo> pkByTable = new LinkedHashMap<>();
        Map<String, List<CanonicalIndex>> idxByTable = loadIndexesAndPk(connection, resolvedSchema, pkByTable);
        Map<String, List<CanonicalForeignKey>> fkByTable = loadForeignKeys(connection, resolvedSchema);
        Map<String, List<CanonicalCheckConstraint>> checksByTable = loadChecks(connection, resolvedSchema);

        List<CanonicalTable> tables = new ArrayList<>();
        int seq = 1;
        for (String tableName : tableNames) {
            String tableId = makeTableId(seq++, tableName);
            List<CanonicalColumn> cols = colsByTable.get(tableName);
            if (cols == null || cols.isEmpty()) {
                tables.add(readTableJdbc(connection, meta, dialect, sourceDialect, resolvedSchema, tableName, tableId));
                continue;
            }
            PkInfo pk = pkByTable.get(tableName);
            tables.add(new CanonicalTable(
                    tableId, tableName, tableComments.get(tableName), List.copyOf(cols),
                    pk == null ? null : pk.name, pk == null ? List.of() : pk.columns,
                    List.copyOf(idxByTable.getOrDefault(tableName, List.of())),
                    List.copyOf(fkByTable.getOrDefault(tableName, List.of())),
                    List.copyOf(checksByTable.getOrDefault(tableName, List.of())),
                    null, null, null
            ));
        }

        List<CanonicalSequence> sequences = loadSequences(connection, resolvedSchema, warnings);
        List<CanonicalProgramObject> programObjects = readProgramObjects(connection, dialect, resolvedSchema, options, warnings);

        return new CanonicalSchema(
                CanonicalSchema.CURRENT_FORMAT_VERSION,
                sourceDialect.code(),
                sourceDialect.family().name(),
                version,
                "UTF-8",
                resolvedSchema,
                null,
                List.copyOf(tables),
                List.copyOf(sequences),
                List.copyOf(programObjects)
        );
    }

    private List<String> loadTableNames(Connection connection, String schema) {
        List<String> names = new ArrayList<>();
        String sql = """
                SELECT t.name FROM sys.tables t
                JOIN sys.schemas s ON s.schema_id = t.schema_id
                WHERE s.name = ? AND t.is_ms_shipped = 0
                ORDER BY t.name
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) names.add(rs.getString(1));
            }
        } catch (Exception ignored) {
        }
        return names;
    }

    private Map<String, String> loadTableComments(Connection connection, String schema) {
        Map<String, String> map = new LinkedHashMap<>();
        String sql = """
                SELECT t.name, CAST(ep.value AS NVARCHAR(4000))
                FROM sys.tables t
                JOIN sys.schemas s ON s.schema_id = t.schema_id
                JOIN sys.extended_properties ep ON ep.major_id = t.object_id AND ep.minor_id = 0 AND ep.name = 'MS_Description'
                WHERE s.name = ?
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String c = blankToNull(rs.getString(2));
                    if (c != null) map.put(rs.getString(1), c);
                }
            }
        } catch (Exception ignored) {
        }
        return map;
    }

    private Map<String, List<CanonicalColumn>> loadColumns(Connection connection, String schema) {
        Map<String, List<CanonicalColumn>> result = new LinkedHashMap<>();
        String sql = """
                SELECT t.name AS table_name,
                       c.name AS column_name,
                       tp.name AS type_name,
                       c.max_length,
                       c.precision,
                       c.scale,
                       c.is_nullable,
                       c.is_identity,
                       CAST(ic.seed_value AS BIGINT) AS seed_val,
                       CAST(ic.increment_value AS BIGINT) AS inc_val,
                       dc.definition AS default_def,
                       cc.definition AS computed_def,
                       CAST(ep.value AS NVARCHAR(4000)) AS col_comment
                FROM sys.tables t
                JOIN sys.schemas s ON s.schema_id = t.schema_id
                JOIN sys.columns c ON c.object_id = t.object_id
                JOIN sys.types tp ON tp.user_type_id = c.user_type_id
                LEFT JOIN sys.identity_columns ic ON ic.object_id = c.object_id AND ic.column_id = c.column_id
                LEFT JOIN sys.default_constraints dc ON dc.parent_object_id = c.object_id AND dc.parent_column_id = c.column_id
                LEFT JOIN sys.computed_columns cc ON cc.object_id = c.object_id AND cc.column_id = c.column_id
                LEFT JOIN sys.extended_properties ep ON ep.major_id = t.object_id AND ep.minor_id = c.column_id AND ep.name = 'MS_Description'
                WHERE s.name = ? AND t.is_ms_shipped = 0
                ORDER BY t.name, c.column_id
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String table = rs.getString("table_name");
                    String colName = rs.getString("column_name");
                    String typeName = rs.getString("type_name");
                    int maxLen = rs.getInt("max_length");
                    int prec = rs.getInt("precision");
                    int scale = rs.getInt("scale");
                    boolean nullable = rs.getBoolean("is_nullable");
                    boolean isIdentity = rs.getBoolean("is_identity");
                    Long seed = getLongOrNull(rs, "seed_val");
                    Long inc = getLongOrNull(rs, "inc_val");
                    String defaultDef = blankToNull(safeString(rs, "default_def"));
                    String computedDef = blankToNull(safeString(rs, "computed_def"));
                    String comment = blankToNull(safeString(rs, "col_comment"));

                    String lowerType = typeName == null ? "" : typeName.toLowerCase(Locale.ROOT);
                    // NVARCHAR / NCHAR 在 sys.columns 中 max_length 按字节记，需除以 2 得到字符数；-1 表示 MAX
                    Integer charLen = maxLen < 0 ? -1 : ((lowerType.equals("nvarchar") || lowerType.equals("nchar")) ? maxLen / 2 : maxLen);
                    String nativeDisplay = formatSqlServerNative(typeName, charLen, prec, scale);
                    var norm = CanonicalTypeNormalizer.normalize(
                            DbTargetDialect.SQLSERVER, nativeDisplay, null, charLen > 0 ? charLen : null,
                            LengthUnit.CHAR, prec > 0 ? prec : null, scale, false, false);
                    IdentitySpec identity = isIdentity
                            ? new IdentitySpec(false, seed == null ? 1 : seed, inc == null ? 1 : inc) : null;
                    var def = (isIdentity || computedDef != null)
                            ? new CanonicalTypeNormalizer.NormalizedDefault(CanonicalSchema.DefaultExprKind.NONE, null)
                            : CanonicalTypeNormalizer.normalizeDefault(defaultDef);

                    result.computeIfAbsent(table, k -> new ArrayList<>()).add(new CanonicalColumn(
                            colName, norm.kind(), nativeDisplay, norm.length(), norm.lengthUnit(),
                            norm.precision(), norm.scale(), norm.unboundedDecimal(),
                            norm.unsigned(), norm.national(), norm.fractionalSeconds(),
                            nullable, def.kind(), def.raw(), identity, computedDef, comment
                    ));
                }
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    private static String formatSqlServerNative(String typeName, int len, int prec, int scale) {
        if (typeName == null) return "nvarchar(255)";
        String lower = typeName.toLowerCase(Locale.ROOT);
        if (lower.equals("nvarchar") || lower.equals("varchar") || lower.equals("varbinary")) {
            return typeName + "(" + (len < 0 ? "MAX" : len) + ")";
        }
        if (lower.equals("char") || lower.equals("nchar") || lower.equals("binary")) {
            return typeName + "(" + Math.max(len, 1) + ")";
        }
        if (lower.equals("decimal") || lower.equals("numeric")) {
            return typeName + "(" + prec + "," + scale + ")";
        }
        if (lower.equals("datetime2") || lower.equals("datetimeoffset") || lower.equals("time")) {
            return typeName + "(" + scale + ")";
        }
        return typeName;
    }

    private Map<String, List<CanonicalIndex>> loadIndexesAndPk(
            Connection connection,
            String schema,
            Map<String, PkInfo> pkByTable
    ) {
        Map<String, TreeMap<Integer, String>> pkCols = new LinkedHashMap<>();
        Map<String, String> pkNames = new LinkedHashMap<>();
        Map<String, RawIndex> rawIdx = new LinkedHashMap<>();
        String sql = """
                SELECT t.name AS table_name,
                       i.name AS index_name,
                       i.is_primary_key,
                       i.is_unique,
                       i.filter_definition,
                       ic.key_ordinal,
                       c.name AS column_name,
                       ic.is_descending_key
                FROM sys.tables t
                JOIN sys.schemas s ON s.schema_id = t.schema_id
                JOIN sys.indexes i ON i.object_id = t.object_id
                JOIN sys.index_columns ic ON ic.object_id = i.object_id AND ic.index_id = i.index_id
                JOIN sys.columns c ON c.object_id = ic.object_id AND c.column_id = ic.column_id
                WHERE s.name = ? AND i.name IS NOT NULL AND ic.is_included_column = 0 AND ic.key_ordinal > 0
                ORDER BY t.name, i.name, ic.key_ordinal
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String table = rs.getString("table_name");
                    String idxName = rs.getString("index_name");
                    boolean isPk = rs.getBoolean("is_primary_key");
                    boolean isUnique = rs.getBoolean("is_unique");
                    String filter = blankToNull(safeString(rs, "filter_definition"));
                    int ord = rs.getInt("key_ordinal");
                    String col = rs.getString("column_name");
                    boolean desc = rs.getBoolean("is_descending_key");
                    if (isPk) {
                        pkNames.put(table, idxName);
                        pkCols.computeIfAbsent(table, k -> new TreeMap<>()).put(ord, col);
                    } else {
                        RawIndex raw = rawIdx.computeIfAbsent(table + "\u0000" + idxName,
                                k -> new RawIndex(table, idxName, isUnique, filter));
                        raw.columns.put(ord, new IndexColumn(col, desc, null, null));
                    }
                }
            }
        } catch (Exception ignored) {
        }
        for (var e : pkCols.entrySet()) {
            pkByTable.put(e.getKey(), new PkInfo(pkNames.get(e.getKey()), List.copyOf(e.getValue().values())));
        }
        Map<String, List<CanonicalIndex>> result = new LinkedHashMap<>();
        for (RawIndex raw : rawIdx.values()) {
            result.computeIfAbsent(raw.table, k -> new ArrayList<>())
                    .add(new CanonicalIndex(raw.name, List.copyOf(raw.columns.values()), raw.unique, IndexKind.BTREE, raw.filter));
        }
        return result;
    }

    private Map<String, List<CanonicalForeignKey>> loadForeignKeys(Connection connection, String schema) {
        Map<String, RawFk> rawFks = new LinkedHashMap<>();
        String sql = """
                SELECT pt.name AS table_name,
                       fk.name AS fk_name,
                       rs.name AS ref_schema,
                       rt.name AS ref_table,
                       fk.update_referential_action_desc,
                       fk.delete_referential_action_desc,
                       fkc.constraint_column_id,
                       pc.name AS local_col,
                       rc.name AS ref_col
                FROM sys.foreign_keys fk
                JOIN sys.tables pt ON pt.object_id = fk.parent_object_id
                JOIN sys.schemas ps ON ps.schema_id = pt.schema_id
                JOIN sys.tables rt ON rt.object_id = fk.referenced_object_id
                JOIN sys.schemas rs ON rs.schema_id = rt.schema_id
                JOIN sys.foreign_key_columns fkc ON fkc.constraint_object_id = fk.object_id
                JOIN sys.columns pc ON pc.object_id = fkc.parent_object_id AND pc.column_id = fkc.parent_column_id
                JOIN sys.columns rc ON rc.object_id = fkc.referenced_object_id AND rc.column_id = fkc.referenced_column_id
                WHERE ps.name = ?
                ORDER BY pt.name, fk.name, fkc.constraint_column_id
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String table = rs.getString("table_name");
                    String name = rs.getString("fk_name");
                    int pos = rs.getInt("constraint_column_id");
                    RawFk fk = rawFks.computeIfAbsent(table + "\u0000" + name, k -> new RawFk(
                            table, name, safeString(rs, "ref_schema"), safeString(rs, "ref_table"),
                            normalizeRule(safeString(rs, "update_referential_action_desc")),
                            normalizeRule(safeString(rs, "delete_referential_action_desc"))
                    ));
                    fk.local.put(pos, rs.getString("local_col"));
                    fk.ref.put(pos, rs.getString("ref_col"));
                }
            }
        } catch (Exception ignored) {
        }
        Map<String, List<CanonicalForeignKey>> result = new LinkedHashMap<>();
        for (RawFk fk : rawFks.values()) {
            result.computeIfAbsent(fk.table, k -> new ArrayList<>()).add(new CanonicalForeignKey(
                    fk.name, List.copyOf(fk.local.values()), fk.refSchema, fk.refTable,
                    List.copyOf(fk.ref.values()), fk.onUpdate, fk.onDelete
            ));
        }
        return result;
    }

    private Map<String, List<CanonicalCheckConstraint>> loadChecks(Connection connection, String schema) {
        Map<String, List<CanonicalCheckConstraint>> result = new LinkedHashMap<>();
        String sql = """
                SELECT t.name, cc.name, cc.definition
                FROM sys.check_constraints cc
                JOIN sys.tables t ON t.object_id = cc.parent_object_id
                JOIN sys.schemas s ON s.schema_id = t.schema_id
                WHERE s.name = ?
                ORDER BY t.name, cc.name
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String def = blankToNull(rs.getString(3));
                    if (def != null) {
                        result.computeIfAbsent(rs.getString(1), k -> new ArrayList<>())
                                .add(new CanonicalCheckConstraint(rs.getString(2), def));
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    private List<CanonicalSequence> loadSequences(Connection connection, String schema, List<String> warnings) {
        List<CanonicalSequence> list = new ArrayList<>();
        String sql = """
                SELECT seq.name,
                       CAST(seq.current_value AS BIGINT) AS current_val,
                       CAST(seq.increment AS BIGINT) AS inc_val,
                       CAST(seq.minimum_value AS BIGINT) AS min_val,
                       CAST(seq.maximum_value AS BIGINT) AS max_val,
                       seq.is_cycling,
                       CAST(seq.cache_size AS BIGINT) AS cache_val
                FROM sys.sequences seq
                JOIN sys.schemas s ON s.schema_id = seq.schema_id
                WHERE s.name = ?
                ORDER BY seq.name
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new CanonicalSequence(
                            rs.getString(1),
                            rs.getLong(2),
                            rs.getLong(3),
                            getLongOrNull(rs, "min_val"),
                            getLongOrNull(rs, "max_val"),
                            rs.getBoolean(6),
                            getLongOrNull(rs, "cache_val"),
                            null, null
                    ));
                }
            }
        } catch (Exception error) {
            warnings.add("读取 SQL Server 序列失败（已跳过）：" + shortMessage(error));
        }
        return list;
    }

    private static String normalizeRule(String desc) {
        if (desc == null) return "NO ACTION";
        return desc.replace('_', ' ').toUpperCase(Locale.ROOT);
    }

    private record PkInfo(String name, List<String> columns) {
    }

    private static final class RawIndex {
        final String table;
        final String name;
        final boolean unique;
        final String filter;
        final TreeMap<Integer, IndexColumn> columns = new TreeMap<>();
        RawIndex(String table, String name, boolean unique, String filter) {
            this.table = table;
            this.name = name;
            this.unique = unique;
            this.filter = filter;
        }
    }

    private static final class RawFk {
        final String table;
        final String name;
        final String refSchema;
        final String refTable;
        final String onUpdate;
        final String onDelete;
        final TreeMap<Integer, String> local = new TreeMap<>();
        final TreeMap<Integer, String> ref = new TreeMap<>();
        RawFk(String table, String name, String refSchema, String refTable, String onUpdate, String onDelete) {
            this.table = table;
            this.name = name;
            this.refSchema = refSchema;
            this.refTable = refTable;
            this.onUpdate = onUpdate;
            this.onDelete = onDelete;
        }
    }
}
