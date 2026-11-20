package com.example.dbadmin.service.migration.introspect;

import com.example.dbadmin.core.DatabaseDialect;
import com.example.dbadmin.model.DbConnection;
import com.example.dbadmin.service.migration.model.CanonicalSchema;
import com.example.dbadmin.service.migration.model.CanonicalSchema.CanonicalColumn;
import com.example.dbadmin.service.migration.model.CanonicalSchema.CanonicalForeignKey;
import com.example.dbadmin.service.migration.model.CanonicalSchema.CanonicalIndex;
import com.example.dbadmin.service.migration.model.CanonicalSchema.CanonicalProgramObject;
import com.example.dbadmin.service.migration.model.CanonicalSchema.CanonicalTable;
import com.example.dbadmin.service.migration.model.CanonicalSchema.IdentitySpec;
import com.example.dbadmin.service.migration.model.CanonicalSchema.IndexColumn;
import com.example.dbadmin.service.migration.model.CanonicalSchema.IndexKind;
import com.example.dbadmin.service.migration.model.CanonicalSchema.LengthUnit;
import com.example.dbadmin.service.migration.model.DbTargetDialect;
import com.example.dbadmin.service.migration.model.MigrationOptions;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * SQLite 深度元数据抽取器（sqlite_master + PRAGMA table_info / index_list / index_info / foreign_key_list）。
 */
public class SqliteSchemaIntrospector extends JdbcBaseIntrospector {
    @Override
    public boolean supports(DbTargetDialect dialect) {
        return dialect == DbTargetDialect.SQLITE;
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

        Map<String, String> tableSql = new LinkedHashMap<>();
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT name, sql FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name")) {
            while (rs.next()) {
                tableSql.put(rs.getString(1), rs.getString(2));
            }
        }

        List<String> tableNames = filterTables(new ArrayList<>(tableSql.keySet()), options.selectedTables());
        List<CanonicalTable> tables = new ArrayList<>();
        int seq = 1;
        for (String tableName : tableNames) {
            String quoted = DbTargetDialect.SQLITE.quoteIdentifier(tableName);
            String ddl = tableSql.getOrDefault(tableName, "");
            boolean hasAutoIncKeyword = ddl != null && ddl.toUpperCase(Locale.ROOT).contains("AUTOINCREMENT");

            List<CanonicalColumn> columns = new ArrayList<>();
            TreeMap<Integer, String> pkMap = new TreeMap<>();
            try (Statement st = connection.createStatement();
                 ResultSet rs = st.executeQuery("PRAGMA table_info(" + quoted + ")")) {
                while (rs.next()) {
                    String colName = rs.getString("name");
                    String typeName = rs.getString("type");
                    boolean notNull = rs.getInt("notnull") != 0;
                    String defaultVal = blankToNull(rs.getString("dflt_value"));
                    int pkPos = rs.getInt("pk");
                    if (pkPos > 0) pkMap.put(pkPos, colName);

                    var norm = CanonicalTypeNormalizer.normalize(
                            DbTargetDialect.SQLITE, typeName == null || typeName.isBlank() ? "TEXT" : typeName,
                            null, null, LengthUnit.CHAR, null, null, false, false);
                    boolean isAutoInc = pkPos == 1 && hasAutoIncKeyword
                            && typeName != null && typeName.trim().equalsIgnoreCase("INTEGER");
                    var def = isAutoInc
                            ? new CanonicalTypeNormalizer.NormalizedDefault(CanonicalSchema.DefaultExprKind.NONE, null)
                            : CanonicalTypeNormalizer.normalizeDefault(defaultVal);
                    IdentitySpec identity = isAutoInc ? new IdentitySpec(false, 1, 1) : null;
                    columns.add(new CanonicalColumn(
                            colName, norm.kind(), typeName == null || typeName.isBlank() ? "TEXT" : typeName,
                            norm.length(), norm.lengthUnit(), norm.precision(), norm.scale(), norm.unboundedDecimal(),
                            norm.unsigned(), norm.national(), norm.fractionalSeconds(),
                            !notNull && pkPos == 0, def.kind(), def.raw(), identity, null, null
                    ));
                }
            }
            List<String> pkCols = List.copyOf(pkMap.values());

            List<CanonicalIndex> indexes = new ArrayList<>();
            List<RawIdx> idxHeaders = new ArrayList<>();
            try (Statement st = connection.createStatement();
                 ResultSet rs = st.executeQuery("PRAGMA index_list(" + quoted + ")")) {
                while (rs.next()) {
                    String origin = safeString(rs, "origin");
                    if ("pk".equalsIgnoreCase(origin)) continue;
                    idxHeaders.add(new RawIdx(rs.getString("name"), rs.getInt("unique") != 0));
                }
            }
            for (RawIdx header : idxHeaders) {
                TreeMap<Integer, IndexColumn> cols = new TreeMap<>();
                try (Statement st = connection.createStatement();
                     ResultSet rs = st.executeQuery("PRAGMA index_info(" + DbTargetDialect.SQLITE.quoteIdentifier(header.name) + ")")) {
                    while (rs.next()) {
                        String col = rs.getString("name");
                        if (col != null) cols.put(rs.getInt("seqno"), new IndexColumn(col, false, null, null));
                    }
                }
                List<IndexColumn> idxCols = List.copyOf(cols.values());
                if (!idxCols.isEmpty() && !isPrimaryBackingIndex(pkCols, header.unique, idxCols)) {
                    indexes.add(new CanonicalIndex(header.name, idxCols, header.unique, IndexKind.BTREE, null));
                }
            }

            Map<Integer, RawFk> fkMap = new LinkedHashMap<>();
            try (Statement st = connection.createStatement();
                 ResultSet rs = st.executeQuery("PRAGMA foreign_key_list(" + quoted + ")")) {
                while (rs.next()) {
                    int id = rs.getInt("id");
                    int fkSeq = rs.getInt("seq");
                    RawFk fk = fkMap.computeIfAbsent(id, k -> new RawFk(
                            "fk_" + tableName + "_" + id,
                            safeString(rs, "table"),
                            safeString(rs, "on_update"),
                            safeString(rs, "on_delete")
                    ));
                    fk.local.put(fkSeq, safeString(rs, "from"));
                    fk.ref.put(fkSeq, safeString(rs, "to"));
                }
            }
            List<CanonicalForeignKey> foreignKeys = new ArrayList<>();
            for (RawFk fk : fkMap.values()) {
                foreignKeys.add(new CanonicalForeignKey(
                        fk.name, List.copyOf(fk.local.values()), "", fk.refTable,
                        List.copyOf(fk.ref.values()),
                        fk.onUpdate == null ? "NO ACTION" : fk.onUpdate,
                        fk.onDelete == null ? "NO ACTION" : fk.onDelete
                ));
            }

            tables.add(new CanonicalTable(
                    makeTableId(seq++, tableName), tableName, null, List.copyOf(columns),
                    pkCols.isEmpty() ? null : "PRIMARY", pkCols,
                    List.copyOf(indexes), List.copyOf(foreignKeys), List.of(), null, null, null
            ));
        }

        List<CanonicalProgramObject> programObjects = readProgramObjects(connection, dialect, "", options, warnings);
        return new CanonicalSchema(
                CanonicalSchema.CURRENT_FORMAT_VERSION,
                sourceDialect.code(),
                sourceDialect.family().name(),
                version,
                "UTF-8",
                "",
                null,
                List.copyOf(tables),
                List.of(),
                List.copyOf(programObjects)
        );
    }

    private record RawIdx(String name, boolean unique) {
    }

    private static final class RawFk {
        final String name;
        final String refTable;
        final String onUpdate;
        final String onDelete;
        final TreeMap<Integer, String> local = new TreeMap<>();
        final TreeMap<Integer, String> ref = new TreeMap<>();
        RawFk(String name, String refTable, String onUpdate, String onDelete) {
            this.name = name;
            this.refTable = refTable;
            this.onUpdate = onUpdate;
            this.onDelete = onDelete;
        }
    }
}
