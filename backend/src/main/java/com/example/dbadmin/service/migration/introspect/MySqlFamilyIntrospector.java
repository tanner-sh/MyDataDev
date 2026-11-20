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
 * MySQL 家族（MySQL / OceanBase MySQL 模式 / MariaDB）深度元数据抽取器。
 *
 * <p>完整采集 {@code UNSIGNED}、{@code AUTO_INCREMENT} 当前值、前缀索引 {@code SUB_PART}、
 * 表/列注释、外键更新/删除规则、CHECK 约束、生成列以及 MariaDB 原生 {@code SEQUENCE}。</p>
 */
public class MySqlFamilyIntrospector extends JdbcBaseIntrospector {
    @Override
    public boolean supports(DbTargetDialect dialect) {
        return dialect != null && dialect.isMySqlFamily();
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

        Map<String, TableMeta> tableMetas = loadTableMetas(connection, resolvedSchema);
        List<String> tableNames = tableMetas.isEmpty()
                ? listTablesJdbc(connection, meta, dialect, resolvedSchema)
                : new ArrayList<>(tableMetas.keySet());
        tableNames = filterTables(tableNames, options.selectedTables());

        Map<String, List<CanonicalColumn>> columnsByTable = loadColumns(connection, resolvedSchema, sourceDialect, tableMetas);
        Map<String, List<String>> pkByTable = new LinkedHashMap<>();
        Map<String, List<CanonicalIndex>> indexesByTable = loadIndexesAndPk(connection, resolvedSchema, pkByTable);
        Map<String, List<CanonicalForeignKey>> fkByTable = loadForeignKeys(connection, resolvedSchema);
        Map<String, List<CanonicalCheckConstraint>> checksByTable = loadChecks(connection, resolvedSchema);

        List<CanonicalTable> tables = new ArrayList<>();
        int seq = 1;
        for (String tableName : tableNames) {
            String tableId = makeTableId(seq++, tableName);
            List<CanonicalColumn> cols = columnsByTable.get(tableName);
            if (cols == null || cols.isEmpty()) {
                tables.add(readTableJdbc(connection, meta, dialect, sourceDialect, resolvedSchema, tableName, tableId));
                continue;
            }
            TableMeta tm = tableMetas.get(tableName);
            List<String> pks = pkByTable.getOrDefault(tableName, List.of());
            tables.add(new CanonicalTable(
                    tableId,
                    tableName,
                    tm == null ? null : tm.comment,
                    List.copyOf(cols),
                    pks.isEmpty() ? null : "PRIMARY",
                    pks,
                    List.copyOf(indexesByTable.getOrDefault(tableName, List.of())),
                    List.copyOf(fkByTable.getOrDefault(tableName, List.of())),
                    List.copyOf(checksByTable.getOrDefault(tableName, List.of())),
                    null,
                    tm == null ? null : tm.estimatedRows,
                    tm == null ? null : tm.estimatedBytes
            ));
        }

        List<CanonicalSequence> sequences = sourceDialect == DbTargetDialect.MARIADB
                ? loadMariaDbSequences(connection, resolvedSchema, warnings)
                : List.of();
        List<CanonicalProgramObject> programObjects = readProgramObjects(connection, dialect, resolvedSchema, options, warnings);

        return new CanonicalSchema(
                CanonicalSchema.CURRENT_FORMAT_VERSION,
                sourceDialect.code(),
                sourceDialect.family().name(),
                version,
                detectCharset(connection, sourceDialect, resolvedSchema),
                resolvedSchema,
                null,
                List.copyOf(tables),
                List.copyOf(sequences),
                List.copyOf(programObjects)
        );
    }

    private String detectCharset(Connection connection, DbTargetDialect sourceDialect, String schema) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT DEFAULT_CHARACTER_SET_NAME FROM INFORMATION_SCHEMA.SCHEMATA WHERE SCHEMA_NAME = ?")) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String cs = blankToNull(rs.getString(1));
                    if (cs != null) return cs;
                }
            }
        } catch (Exception ignored) {
        }
        return "utf8mb4";
    }

    private Map<String, TableMeta> loadTableMetas(Connection connection, String schema) {
        Map<String, TableMeta> map = new LinkedHashMap<>();
        String sql = """
                SELECT TABLE_NAME, TABLE_COMMENT, TABLE_ROWS, DATA_LENGTH, AUTO_INCREMENT
                FROM INFORMATION_SCHEMA.TABLES
                WHERE TABLE_SCHEMA = ? AND TABLE_TYPE = 'BASE TABLE'
                ORDER BY TABLE_NAME
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    map.put(rs.getString(1), new TableMeta(
                            blankToNull(rs.getString(2)),
                            getLongOrNull(rs, "TABLE_ROWS"),
                            getLongOrNull(rs, "DATA_LENGTH"),
                            getLongOrNull(rs, "AUTO_INCREMENT")
                    ));
                }
            }
        } catch (Exception ignored) {
        }
        return map;
    }

    private Map<String, List<CanonicalColumn>> loadColumns(
            Connection connection,
            String schema,
            DbTargetDialect sourceDialect,
            Map<String, TableMeta> tableMetas
    ) {
        Map<String, List<CanonicalColumn>> result = new LinkedHashMap<>();
        String sql = """
                SELECT TABLE_NAME, COLUMN_NAME, COLUMN_TYPE, DATA_TYPE, CHARACTER_MAXIMUM_LENGTH,
                       NUMERIC_PRECISION, NUMERIC_SCALE, DATETIME_PRECISION, IS_NULLABLE,
                       COLUMN_DEFAULT, EXTRA, GENERATION_EXPRESSION, COLUMN_COMMENT
                FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_SCHEMA = ?
                ORDER BY TABLE_NAME, ORDINAL_POSITION
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String table = rs.getString("TABLE_NAME");
                    String colName = rs.getString("COLUMN_NAME");
                    String colType = rs.getString("COLUMN_TYPE");
                    Long charMaxLen = getLongOrNull(rs, "CHARACTER_MAXIMUM_LENGTH");
                    Integer numPrec = getIntOrNull(rs, "NUMERIC_PRECISION");
                    Integer numScale = getIntOrNull(rs, "NUMERIC_SCALE");
                    Integer dtPrec = getIntOrNull(rs, "DATETIME_PRECISION");
                    boolean nullable = !"NO".equalsIgnoreCase(rs.getString("IS_NULLABLE"));
                    String colDef = blankToNull(safeString(rs, "COLUMN_DEFAULT"));
                    String extra = safeString(rs, "EXTRA");
                    String genExpr = blankToNull(safeString(rs, "GENERATION_EXPRESSION"));
                    String comment = blankToNull(safeString(rs, "COLUMN_COMMENT"));

                    boolean autoInc = extra != null && extra.toLowerCase(Locale.ROOT).contains("auto_increment");
                    boolean unsigned = colType != null && colType.toLowerCase(Locale.ROOT).contains("unsigned");
                    Integer boundedLen = charMaxLen != null && charMaxLen > 0 && charMaxLen <= Integer.MAX_VALUE
                            ? charMaxLen.intValue() : null;
                    Integer effectiveScale = dtPrec != null ? dtPrec : numScale;

                    var norm = CanonicalTypeNormalizer.normalize(
                            sourceDialect, colType, null, boundedLen, LengthUnit.CHAR,
                            numPrec, effectiveScale, false, unsigned);
                    var def = (autoInc || genExpr != null)
                            ? new CanonicalTypeNormalizer.NormalizedDefault(CanonicalSchema.DefaultExprKind.NONE, null)
                            : CanonicalTypeNormalizer.normalizeDefault(colDef);
                    TableMeta tm = tableMetas.get(table);
                    long startWith = tm != null && tm.autoIncrement != null && tm.autoIncrement > 0 ? tm.autoIncrement : 1L;
                    IdentitySpec identity = autoInc ? new IdentitySpec(false, startWith, 1) : null;

                    result.computeIfAbsent(table, k -> new ArrayList<>()).add(new CanonicalColumn(
                            colName, norm.kind(), colType, norm.length(), norm.lengthUnit(),
                            norm.precision(), norm.scale(), norm.unboundedDecimal(),
                            norm.unsigned(), norm.national(), norm.fractionalSeconds(),
                            nullable, def.kind(), def.raw(), identity, genExpr, comment
                    ));
                }
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    private Map<String, List<CanonicalIndex>> loadIndexesAndPk(
            Connection connection,
            String schema,
            Map<String, List<String>> pkByTable
    ) {
        Map<String, TreeMap<Integer, String>> pkOrder = new LinkedHashMap<>();
        Map<String, RawIndex> rawIndexes = new LinkedHashMap<>();
        String sql = """
                SELECT TABLE_NAME, INDEX_NAME, NON_UNIQUE, SEQ_IN_INDEX, COLUMN_NAME, COLLATION, SUB_PART, INDEX_TYPE
                FROM INFORMATION_SCHEMA.STATISTICS
                WHERE TABLE_SCHEMA = ?
                ORDER BY TABLE_NAME, INDEX_NAME, SEQ_IN_INDEX
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String table = rs.getString(1);
                    String idxName = rs.getString(2);
                    boolean unique = rs.getInt(3) == 0;
                    int seq = rs.getInt(4);
                    String colName = rs.getString(5);
                    boolean desc = "D".equalsIgnoreCase(safeString(rs, "COLLATION"));
                    Integer subPart = getIntOrNull(rs, "SUB_PART");
                    String idxType = safeString(rs, "INDEX_TYPE");
                    if (colName == null) continue;
                    if ("PRIMARY".equalsIgnoreCase(idxName)) {
                        pkOrder.computeIfAbsent(table, k -> new TreeMap<>()).put(seq, colName);
                        continue;
                    }
                    RawIndex raw = rawIndexes.computeIfAbsent(table + "\u0000" + idxName,
                            k -> new RawIndex(table, idxName, unique, classifyMySqlIndexKind(idxType)));
                    raw.columns.put(seq, new IndexColumn(colName, desc, subPart, null));
                }
            }
        } catch (Exception ignored) {
        }
        for (var e : pkOrder.entrySet()) {
            pkByTable.put(e.getKey(), List.copyOf(e.getValue().values()));
        }
        Map<String, List<CanonicalIndex>> result = new LinkedHashMap<>();
        for (RawIndex raw : rawIndexes.values()) {
            result.computeIfAbsent(raw.table, k -> new ArrayList<>())
                    .add(new CanonicalIndex(raw.name, List.copyOf(raw.columns.values()), raw.unique, raw.kind, null));
        }
        return result;
    }

    private Map<String, List<CanonicalForeignKey>> loadForeignKeys(Connection connection, String schema) {
        Map<String, RawFk> rawFks = new LinkedHashMap<>();
        String sql = """
                SELECT k.TABLE_NAME, k.CONSTRAINT_NAME, k.ORDINAL_POSITION, k.COLUMN_NAME,
                       k.REFERENCED_TABLE_SCHEMA, k.REFERENCED_TABLE_NAME, k.REFERENCED_COLUMN_NAME,
                       r.UPDATE_RULE, r.DELETE_RULE
                FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE k
                JOIN INFORMATION_SCHEMA.REFERENTIAL_CONSTRAINTS r
                  ON r.CONSTRAINT_SCHEMA = k.CONSTRAINT_SCHEMA AND r.CONSTRAINT_NAME = k.CONSTRAINT_NAME
                WHERE k.TABLE_SCHEMA = ? AND k.REFERENCED_TABLE_NAME IS NOT NULL
                ORDER BY k.TABLE_NAME, k.CONSTRAINT_NAME, k.ORDINAL_POSITION
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String table = rs.getString(1);
                    String name = rs.getString(2);
                    int pos = rs.getInt(3);
                    RawFk fk = rawFks.computeIfAbsent(table + "\u0000" + name, k -> new RawFk(
                            table, name, safeString(rs, "REFERENCED_TABLE_SCHEMA"),
                            safeString(rs, "REFERENCED_TABLE_NAME"),
                            safeString(rs, "UPDATE_RULE"),
                            safeString(rs, "DELETE_RULE")
                    ));
                    fk.local.put(pos, rs.getString(4));
                    fk.ref.put(pos, rs.getString(7));
                }
            }
        } catch (Exception ignored) {
        }
        Map<String, List<CanonicalForeignKey>> result = new LinkedHashMap<>();
        for (RawFk fk : rawFks.values()) {
            result.computeIfAbsent(fk.table, k -> new ArrayList<>()).add(new CanonicalForeignKey(
                    fk.name, List.copyOf(fk.local.values()), fk.refSchema, fk.refTable,
                    List.copyOf(fk.ref.values()),
                    fk.onUpdate == null ? "NO ACTION" : fk.onUpdate,
                    fk.onDelete == null ? "NO ACTION" : fk.onDelete
            ));
        }
        return result;
    }

    private Map<String, List<CanonicalCheckConstraint>> loadChecks(Connection connection, String schema) {
        Map<String, List<CanonicalCheckConstraint>> result = new LinkedHashMap<>();
        String sql = """
                SELECT tc.TABLE_NAME, tc.CONSTRAINT_NAME, cc.CHECK_CLAUSE
                FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS tc
                JOIN INFORMATION_SCHEMA.CHECK_CONSTRAINTS cc
                  ON cc.CONSTRAINT_SCHEMA = tc.CONSTRAINT_SCHEMA AND cc.CONSTRAINT_NAME = tc.CONSTRAINT_NAME
                WHERE tc.TABLE_SCHEMA = ? AND tc.CONSTRAINT_TYPE = 'CHECK'
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String clause = blankToNull(rs.getString(3));
                    if (clause != null) {
                        result.computeIfAbsent(rs.getString(1), k -> new ArrayList<>())
                                .add(new CanonicalCheckConstraint(rs.getString(2), clause));
                    }
                }
            }
        } catch (Exception ignored) {
            // MySQL 5.7 无 CHECK_CONSTRAINTS 视图，忽略
        }
        return result;
    }

    private List<CanonicalSequence> loadMariaDbSequences(Connection connection, String schema, List<String> warnings) {
        List<CanonicalSequence> sequences = new ArrayList<>();
        String listSql = "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = ? AND TABLE_TYPE = 'SEQUENCE' ORDER BY TABLE_NAME";
        try (PreparedStatement ps = connection.prepareStatement(listSql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String seqName = rs.getString(1);
                    String detailSql = "SELECT next_not_cached_value, increment, minimum_value, maximum_value, cycle_option, cache_size FROM "
                            + DbTargetDialect.MARIADB.qualifiedName(schema, seqName);
                    try (var st = connection.createStatement(); ResultSet dr = st.executeQuery(detailSql)) {
                        if (dr.next()) {
                            sequences.add(new CanonicalSequence(
                                    seqName, dr.getLong(1), dr.getLong(2),
                                    getLongOrNull(dr, "minimum_value"), getLongOrNull(dr, "maximum_value"),
                                    dr.getInt(5) != 0, getLongOrNull(dr, "cache_size"), null, null
                            ));
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (Exception error) {
            warnings.add("读取 MariaDB 序列失败（已跳过）：" + shortMessage(error));
        }
        return sequences;
    }

    private static IndexKind classifyMySqlIndexKind(String indexType) {
        if (indexType == null) return IndexKind.BTREE;
        return switch (indexType.toUpperCase(Locale.ROOT)) {
            case "HASH" -> IndexKind.HASH;
            case "FULLTEXT" -> IndexKind.FULLTEXT;
            case "BTREE" -> IndexKind.BTREE;
            default -> IndexKind.OTHER;
        };
    }

    private record TableMeta(String comment, Long estimatedRows, Long estimatedBytes, Long autoIncrement) {
    }

    private static final class RawIndex {
        final String table;
        final String name;
        final boolean unique;
        final IndexKind kind;
        final TreeMap<Integer, IndexColumn> columns = new TreeMap<>();
        RawIndex(String table, String name, boolean unique, IndexKind kind) {
            this.table = table;
            this.name = name;
            this.unique = unique;
            this.kind = kind;
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
