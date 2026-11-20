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
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * PostgreSQL 深度元数据抽取器。
 *
 * <p>基于 {@code pg_catalog} 精确区分有精度与无精度 {@code numeric}、{@code timestamptz}、
 * {@code IDENTITY}/{@code SERIAL}、表/列注释、部分/表达式索引、外键、CHECK 约束与序列当前值及归属列。</p>
 */
public class PostgresSchemaIntrospector extends JdbcBaseIntrospector {
    @Override
    public boolean supports(DbTargetDialect dialect) {
        return dialect == DbTargetDialect.POSTGRESQL;
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
        if (resolvedSchema.isBlank()) resolvedSchema = "public";

        Map<String, TableMeta> tableMetas = loadTables(connection, resolvedSchema);
        List<String> tableNames = tableMetas.isEmpty()
                ? listTablesJdbc(connection, meta, dialect, resolvedSchema)
                : new ArrayList<>(tableMetas.keySet());
        tableNames = filterTables(tableNames, options.selectedTables());

        Map<String, List<CanonicalColumn>> colsByTable = loadColumns(connection, resolvedSchema);
        Map<String, PkInfo> pkByTable = loadPrimaryKeys(connection, resolvedSchema);
        Map<String, List<CanonicalIndex>> indexesByTable = loadIndexes(connection, resolvedSchema);
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
            TableMeta tm = tableMetas.get(tableName);
            PkInfo pk = pkByTable.get(tableName);
            tables.add(new CanonicalTable(
                    tableId,
                    tableName,
                    tm == null ? null : tm.comment,
                    List.copyOf(cols),
                    pk == null ? null : pk.name,
                    pk == null ? List.of() : pk.columns,
                    List.copyOf(indexesByTable.getOrDefault(tableName, List.of())),
                    List.copyOf(fkByTable.getOrDefault(tableName, List.of())),
                    List.copyOf(checksByTable.getOrDefault(tableName, List.of())),
                    tm != null && tm.partitioned ? "PARTITIONED" : null,
                    tm == null ? null : tm.estimatedRows,
                    tm == null ? null : tm.estimatedBytes
            ));
        }

        List<CanonicalSequence> sequences = loadSequences(connection, resolvedSchema, warnings);
        List<CanonicalProgramObject> programObjects = readProgramObjects(connection, dialect, resolvedSchema, options, warnings);

        return new CanonicalSchema(
                CanonicalSchema.CURRENT_FORMAT_VERSION,
                sourceDialect.code(),
                sourceDialect.family().name(),
                version,
                detectCharset(connection, sourceDialect),
                resolvedSchema,
                null,
                List.copyOf(tables),
                List.copyOf(sequences),
                List.copyOf(programObjects)
        );
    }

    @Override
    protected String detectCharset(Connection connection, DbTargetDialect sourceDialect) {
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SHOW server_encoding")) {
            if (rs.next()) {
                String cs = blankToNull(rs.getString(1));
                if (cs != null) return cs;
            }
        } catch (Exception ignored) {
        }
        return "UTF8";
    }

    private Map<String, TableMeta> loadTables(Connection connection, String schema) {
        Map<String, TableMeta> map = new LinkedHashMap<>();
        String sql = """
                SELECT c.relname,
                       obj_description(c.oid, 'pg_class') AS tbl_comment,
                       GREATEST(c.reltuples::bigint, 0) AS est_rows,
                       pg_total_relation_size(c.oid) AS est_bytes,
                       c.relkind = 'p' AS is_part
                FROM pg_catalog.pg_class c
                JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = ? AND c.relkind IN ('r', 'p')
                ORDER BY c.relname
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    map.put(rs.getString(1), new TableMeta(
                            blankToNull(rs.getString(2)),
                            getLongOrNull(rs, "est_rows"),
                            getLongOrNull(rs, "est_bytes"),
                            rs.getBoolean(5)
                    ));
                }
            }
        } catch (Exception ignored) {
        }
        return map;
    }

    private Map<String, List<CanonicalColumn>> loadColumns(Connection connection, String schema) {
        Map<String, List<CanonicalColumn>> result = new LinkedHashMap<>();
        String sql = """
                SELECT c.relname AS table_name,
                       a.attname AS column_name,
                       pg_catalog.format_type(a.atttypid, a.atttypmod) AS formatted_type,
                       t.typname AS udt_name,
                       NOT a.attnotnull AS is_nullable,
                       pg_catalog.pg_get_expr(d.adbin, d.adrelid) AS column_default,
                       a.attidentity AS identity_kind,
                       a.attgenerated AS generated_kind,
                       col_description(c.oid, a.attnum) AS col_comment
                FROM pg_catalog.pg_class c
                JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                JOIN pg_catalog.pg_attribute a ON a.attrelid = c.oid
                JOIN pg_catalog.pg_type t ON t.oid = a.atttypid
                LEFT JOIN pg_catalog.pg_attrdef d ON d.adrelid = c.oid AND d.adnum = a.attnum
                WHERE n.nspname = ? AND c.relkind IN ('r', 'p')
                  AND a.attnum > 0 AND NOT a.attisdropped
                ORDER BY c.relname, a.attnum
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String table = rs.getString("table_name");
                    String colName = rs.getString("column_name");
                    String formattedType = rs.getString("formatted_type");
                    String udtName = rs.getString("udt_name");
                    boolean nullable = rs.getBoolean("is_nullable");
                    String colDefault = blankToNull(rs.getString("column_default"));
                    String identityKind = blankToNull(safeString(rs, "identity_kind"));
                    String generatedKind = blankToNull(safeString(rs, "generated_kind"));
                    String comment = blankToNull(rs.getString("col_comment"));

                    boolean isGenerated = "s".equalsIgnoreCase(generatedKind);
                    boolean isIdentity = "a".equalsIgnoreCase(identityKind) || "d".equalsIgnoreCase(identityKind);
                    boolean isSerial = !isIdentity && colDefault != null && colDefault.toLowerCase(Locale.ROOT).startsWith("nextval(");

                    // 区分 PG 裸 numeric（format_type == "numeric"）与 numeric(p,s)
                    boolean unboundedNumeric = "numeric".equalsIgnoreCase(formattedType == null ? "" : formattedType.trim());
                    String typeForNorm = "int8".equalsIgnoreCase(udtName) ? "BIGINT" : formattedType;
                    var norm = CanonicalTypeNormalizer.normalize(
                            DbTargetDialect.POSTGRESQL, typeForNorm, null, null, LengthUnit.CHAR,
                            null, null, unboundedNumeric, false);

                    IdentitySpec identity = (isIdentity || isSerial)
                            ? new IdentitySpec("a".equalsIgnoreCase(identityKind), 1, 1) : null;
                    var def = (isIdentity || isGenerated || isSerial)
                            ? new CanonicalTypeNormalizer.NormalizedDefault(CanonicalSchema.DefaultExprKind.NONE, null)
                            : CanonicalTypeNormalizer.normalizeDefault(colDefault);
                    String genExpr = isGenerated ? colDefault : null;

                    result.computeIfAbsent(table, k -> new ArrayList<>()).add(new CanonicalColumn(
                            colName, norm.kind(), formattedType, norm.length(), norm.lengthUnit(),
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

    private Map<String, PkInfo> loadPrimaryKeys(Connection connection, String schema) {
        Map<String, PkInfo> result = new LinkedHashMap<>();
        String sql = """
                SELECT c.relname AS table_name, con.conname AS pk_name, a.attname AS col_name, u.ord
                FROM pg_catalog.pg_constraint con
                JOIN pg_catalog.pg_class c ON c.oid = con.conrelid
                JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                CROSS JOIN LATERAL unnest(con.conkey) WITH ORDINALITY AS u(attnum, ord)
                JOIN pg_catalog.pg_attribute a ON a.attrelid = c.oid AND a.attnum = u.attnum
                WHERE n.nspname = ? AND con.contype = 'p'
                ORDER BY c.relname, u.ord
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String table = rs.getString(1);
                    String pkName = rs.getString(2);
                    PkInfo pk = result.computeIfAbsent(table, k -> new PkInfo(pkName, new ArrayList<>()));
                    pk.columns.add(rs.getString(3));
                }
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    private Map<String, List<CanonicalIndex>> loadIndexes(Connection connection, String schema) {
        Map<String, List<CanonicalIndex>> result = new LinkedHashMap<>();
        String sql = """
                SELECT t.relname AS table_name,
                       i.relname AS index_name,
                       ix.indisunique AS is_unique,
                       am.amname AS access_method,
                       pg_catalog.pg_get_expr(ix.indpred, ix.indrelid) AS filter_expr,
                       array_agg(
                           COALESCE(a.attname, pg_catalog.pg_get_indexdef(ix.indexrelid, k.ord::int, true))
                           ORDER BY k.ord
                       ) AS col_items,
                       array_agg(a.attname IS NULL ORDER BY k.ord) AS is_expr_flags
                FROM pg_catalog.pg_index ix
                JOIN pg_catalog.pg_class t ON t.oid = ix.indrelid
                JOIN pg_catalog.pg_namespace n ON n.oid = t.relnamespace
                JOIN pg_catalog.pg_class i ON i.oid = ix.indexrelid
                JOIN pg_catalog.pg_am am ON am.oid = i.relam
                CROSS JOIN LATERAL unnest(ix.indkey) WITH ORDINALITY AS k(attnum, ord)
                LEFT JOIN pg_catalog.pg_attribute a ON a.attrelid = t.oid AND a.attnum = k.attnum AND k.attnum > 0
                WHERE n.nspname = ? AND NOT ix.indisprimary AND k.ord <= ix.indnkeyatts
                GROUP BY t.relname, i.relname, ix.indisunique, am.amname, ix.indpred, ix.indrelid
                ORDER BY t.relname, i.relname
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String table = rs.getString("table_name");
                    String idxName = rs.getString("index_name");
                    boolean unique = rs.getBoolean("is_unique");
                    String am = rs.getString("access_method");
                    String filter = blankToNull(rs.getString("filter_expr"));
                    Object[] colItems = (Object[]) rs.getArray("col_items").getArray();
                    Object[] exprFlags = (Object[]) rs.getArray("is_expr_flags").getArray();
                    List<IndexColumn> cols = new ArrayList<>();
                    boolean hasExpr = false;
                    for (int idx = 0; idx < colItems.length; idx++) {
                        boolean isExpr = Boolean.TRUE.equals(exprFlags[idx]);
                        String text = colItems[idx] == null ? "" : colItems[idx].toString();
                        if (isExpr) {
                            hasExpr = true;
                            cols.add(new IndexColumn(null, false, null, text));
                        } else {
                            cols.add(new IndexColumn(text, false, null, null));
                        }
                    }
                    IndexKind kind = hasExpr ? IndexKind.EXPRESSION
                            : ("hash".equalsIgnoreCase(am) ? IndexKind.HASH
                            : ("btree".equalsIgnoreCase(am) ? IndexKind.BTREE : IndexKind.OTHER));
                    result.computeIfAbsent(table, k -> new ArrayList<>())
                            .add(new CanonicalIndex(idxName, List.copyOf(cols), unique, kind, filter));
                }
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    private Map<String, List<CanonicalForeignKey>> loadForeignKeys(Connection connection, String schema) {
        Map<String, List<CanonicalForeignKey>> result = new LinkedHashMap<>();
        String sql = """
                SELECT c.relname AS table_name,
                       con.conname AS fk_name,
                       rn.nspname AS ref_schema,
                       rc.relname AS ref_table,
                       con.confupdtype,
                       con.confdeltype,
                       array_agg(la.attname ORDER BY u.ord) AS local_cols,
                       array_agg(ra.attname ORDER BY u.ord) AS ref_cols
                FROM pg_catalog.pg_constraint con
                JOIN pg_catalog.pg_class c ON c.oid = con.conrelid
                JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                JOIN pg_catalog.pg_class rc ON rc.oid = con.confrelid
                JOIN pg_catalog.pg_namespace rn ON rn.oid = rc.relnamespace
                CROSS JOIN LATERAL unnest(con.conkey, con.confkey) WITH ORDINALITY AS u(lnum, rnum, ord)
                JOIN pg_catalog.pg_attribute la ON la.attrelid = c.oid AND la.attnum = u.lnum
                JOIN pg_catalog.pg_attribute ra ON ra.attrelid = rc.oid AND ra.attnum = u.rnum
                WHERE n.nspname = ? AND con.contype = 'f'
                GROUP BY c.relname, con.conname, rn.nspname, rc.relname, con.confupdtype, con.confdeltype
                ORDER BY c.relname, con.conname
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String table = rs.getString("table_name");
                    Object[] localArr = (Object[]) rs.getArray("local_cols").getArray();
                    Object[] refArr = (Object[]) rs.getArray("ref_cols").getArray();
                    List<String> localCols = new ArrayList<>();
                    List<String> refCols = new ArrayList<>();
                    for (Object o : localArr) localCols.add(o.toString());
                    for (Object o : refArr) refCols.add(o.toString());
                    result.computeIfAbsent(table, k -> new ArrayList<>()).add(new CanonicalForeignKey(
                            rs.getString("fk_name"), List.copyOf(localCols),
                            rs.getString("ref_schema"), rs.getString("ref_table"), List.copyOf(refCols),
                            pgFkAction(rs.getString("confupdtype")), pgFkAction(rs.getString("confdeltype"))
                    ));
                }
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    private Map<String, List<CanonicalCheckConstraint>> loadChecks(Connection connection, String schema) {
        Map<String, List<CanonicalCheckConstraint>> result = new LinkedHashMap<>();
        String sql = """
                SELECT c.relname AS table_name, con.conname, pg_catalog.pg_get_constraintdef(con.oid) AS def
                FROM pg_catalog.pg_constraint con
                JOIN pg_catalog.pg_class c ON c.oid = con.conrelid
                JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = ? AND con.contype = 'c'
                ORDER BY c.relname, con.conname
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String def = blankToNull(rs.getString(3));
                    if (def != null) {
                        String expr = def.replaceFirst("(?i)^CHECK\\s*", "").trim();
                        result.computeIfAbsent(rs.getString(1), k -> new ArrayList<>())
                                .add(new CanonicalCheckConstraint(rs.getString(2), expr));
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
                SELECT s.sequencename,
                       COALESCE(s.last_value, s.start_value, 1) AS current_val,
                       s.increment_by,
                       s.min_value,
                       s.max_value,
                       s.cycle,
                       s.cache_size,
                       tc.relname AS owned_table,
                       a.attname AS owned_col
                FROM pg_catalog.pg_sequences s
                JOIN pg_catalog.pg_namespace n ON n.nspname = s.schemaname
                JOIN pg_catalog.pg_class sc ON sc.relnamespace = n.oid AND sc.relname = s.sequencename
                LEFT JOIN pg_catalog.pg_depend d ON d.objid = sc.oid AND d.deptype IN ('a', 'i') AND d.refobjsubid > 0
                LEFT JOIN pg_catalog.pg_class tc ON tc.oid = d.refobjid
                LEFT JOIN pg_catalog.pg_attribute a ON a.attrelid = tc.oid AND a.attnum = d.refobjsubid
                WHERE s.schemaname = ?
                ORDER BY s.sequencename
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new CanonicalSequence(
                            rs.getString("sequencename"),
                            rs.getLong("current_val"),
                            rs.getLong("increment_by"),
                            getLongOrNull(rs, "min_value"),
                            getLongOrNull(rs, "max_value"),
                            rs.getBoolean("cycle"),
                            getLongOrNull(rs, "cache_size"),
                            blankToNull(safeString(rs, "owned_table")),
                            blankToNull(safeString(rs, "owned_col"))
                    ));
                }
            }
        } catch (Exception error) {
            warnings.add("读取 PostgreSQL 序列失败（已跳过）：" + shortMessage(error));
        }
        return list;
    }

    private static String pgFkAction(String code) {
        if (code == null) return "NO ACTION";
        return switch (code.toLowerCase(Locale.ROOT)) {
            case "c" -> "CASCADE";
            case "n" -> "SET NULL";
            case "d" -> "SET DEFAULT";
            case "r" -> "RESTRICT";
            default -> "NO ACTION";
        };
    }

    private record TableMeta(String comment, Long estimatedRows, Long estimatedBytes, boolean partitioned) {
    }

    private record PkInfo(String name, List<String> columns) {
    }
}
