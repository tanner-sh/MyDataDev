package com.example.dbadmin.service.migration.introspect;

import com.example.dbadmin.core.DatabaseDialect;
import com.example.dbadmin.model.DbConnection;
import com.example.dbadmin.service.migration.model.CanonicalSchema;
import com.example.dbadmin.service.migration.model.CanonicalSchema.CanonicalColumn;
import com.example.dbadmin.service.migration.model.CanonicalSchema.CanonicalProgramObject;
import com.example.dbadmin.service.migration.model.CanonicalSchema.CanonicalTable;
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

/**
 * ClickHouse 深度元数据抽取器（system.tables / system.columns，解包 Nullable 与 LowCardinality）。
 */
public class ClickHouseSchemaIntrospector extends JdbcBaseIntrospector {
    @Override
    public boolean supports(DbTargetDialect dialect) {
        return dialect == DbTargetDialect.CLICKHOUSE;
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
        if (resolvedSchema.isBlank()) resolvedSchema = "default";

        Map<String, TableMeta> tableMetas = loadTables(connection, resolvedSchema);
        List<String> tableNames = tableMetas.isEmpty()
                ? listTablesJdbc(connection, meta, dialect, resolvedSchema)
                : new ArrayList<>(tableMetas.keySet());
        tableNames = filterTables(tableNames, options.selectedTables());

        Map<String, List<CanonicalColumn>> colsByTable = new LinkedHashMap<>();
        Map<String, List<String>> pkByTable = new LinkedHashMap<>();
        loadColumns(connection, resolvedSchema, colsByTable, pkByTable);

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
            List<String> pks = pkByTable.getOrDefault(tableName, List.of());
            tables.add(new CanonicalTable(
                    tableId, tableName, tm == null ? null : tm.comment, List.copyOf(cols),
                    pks.isEmpty() ? null : "PRIMARY", pks,
                    List.of(), List.of(), List.of(),
                    tm == null ? null : tm.partitionKey,
                    tm == null ? null : tm.totalRows,
                    tm == null ? null : tm.totalBytes
            ));
        }

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
                List.of(),
                List.copyOf(programObjects)
        );
    }

    private Map<String, TableMeta> loadTables(Connection connection, String database) {
        Map<String, TableMeta> map = new LinkedHashMap<>();
        String sql = """
                SELECT name, comment, partition_key, total_rows, total_bytes
                FROM system.tables
                WHERE database = ? AND engine NOT LIKE '%View%'
                ORDER BY name
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, database);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    map.put(rs.getString(1), new TableMeta(
                            blankToNull(safeString(rs, "comment")),
                            blankToNull(safeString(rs, "partition_key")),
                            getLongOrNull(rs, "total_rows"),
                            getLongOrNull(rs, "total_bytes")
                    ));
                }
            }
        } catch (Exception ignored) {
        }
        return map;
    }

    private void loadColumns(
            Connection connection,
            String database,
            Map<String, List<CanonicalColumn>> colsByTable,
            Map<String, List<String>> pkByTable
    ) {
        String sql = """
                SELECT table, name, type, default_kind, default_expression, comment, is_in_primary_key
                FROM system.columns
                WHERE database = ?
                ORDER BY table, position
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, database);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String table = rs.getString("table");
                    String colName = rs.getString("name");
                    String rawType = rs.getString("type");
                    String defKind = safeString(rs, "default_kind");
                    String defExpr = blankToNull(safeString(rs, "default_expression"));
                    String comment = blankToNull(safeString(rs, "comment"));
                    boolean inPk = rs.getInt("is_in_primary_key") != 0;

                    boolean nullable = rawType != null && rawType.toLowerCase(Locale.ROOT).contains("nullable(");
                    var norm = CanonicalTypeNormalizer.normalize(
                            DbTargetDialect.CLICKHOUSE, rawType, null, null, LengthUnit.CHAR,
                            null, null, false, false);
                    boolean isDefault = "DEFAULT".equalsIgnoreCase(defKind);
                    var def = isDefault
                            ? CanonicalTypeNormalizer.normalizeDefault(defExpr)
                            : new CanonicalTypeNormalizer.NormalizedDefault(CanonicalSchema.DefaultExprKind.NONE, null);
                    String generated = ("MATERIALIZED".equalsIgnoreCase(defKind) || "ALIAS".equalsIgnoreCase(defKind)) ? defExpr : null;

                    colsByTable.computeIfAbsent(table, k -> new ArrayList<>()).add(new CanonicalColumn(
                            colName, norm.kind(), rawType, norm.length(), norm.lengthUnit(),
                            norm.precision(), norm.scale(), norm.unboundedDecimal(),
                            norm.unsigned(), norm.national(), norm.fractionalSeconds(),
                            nullable, def.kind(), def.raw(), null, generated, comment
                    ));
                    if (inPk) {
                        pkByTable.computeIfAbsent(table, k -> new ArrayList<>()).add(colName);
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    private record TableMeta(String comment, String partitionKey, Long totalRows, Long totalBytes) {
    }
}
