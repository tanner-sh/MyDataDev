package com.example.dbadmin.service;

import com.example.dbadmin.dto.ApiDtos.ColumnInfo;
import com.example.dbadmin.dto.ApiDtos.DbObject;
import com.example.dbadmin.dto.ApiDtos.IndexInfo;
import com.example.dbadmin.dto.ApiDtos.MetadataResponse;
import com.example.dbadmin.dto.ApiDtos.ObjectDetail;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;

@Service
public class MetadataService {
    private final ConnectionService connections;

    public MetadataService(ConnectionService connections) {
        this.connections = connections;
    }

    public MetadataResponse inspect(long connectionId, String schemaFilter) throws Exception {
        try (Connection connection = connections.open(connectionId)) {
            DatabaseMetaData meta = connection.getMetaData();
            List<String> schemas = schemas(meta);
            List<DbObject> objects = new ArrayList<>();
            String schemaPattern = schemaFilter == null || schemaFilter.isBlank() ? null : schemaFilter;
            try (ResultSet rs = meta.getTables(connection.getCatalog(), schemaPattern, "%", new String[]{"TABLE", "VIEW"})) {
                while (rs.next() && objects.size() < 100) {
                    String schema = rs.getString("TABLE_SCHEM");
                    String name = rs.getString("TABLE_NAME");
                    String type = rs.getString("TABLE_TYPE");
                    if (isSystemSchema(schema)) {
                        continue;
                    }
                    objects.add(new DbObject(schema, name, type, columns(meta, connection.getCatalog(), schema, name), indexes(meta, connection.getCatalog(), schema, name)));
                }
            }
            return new MetadataResponse(schemas, objects);
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
        try (Connection connection = connections.open(connectionId)) {
            DatabaseMetaData meta = connection.getMetaData();
            String catalog = connection.getCatalog();
            DbObject object = findObject(meta, catalog, schemaName, objectName);
            List<ColumnInfo> cols = columns(meta, catalog, object.schemaName(), object.name());
            List<IndexInfo> idx = indexes(meta, catalog, object.schemaName(), object.name());
            List<String> pk = primaryKeys(meta, catalog, object.schemaName(), object.name());
            Long rowCount = isView(object.type()) ? null : rowCount(connection, object.schemaName(), object.name());
            String ddl = ddl(object.schemaName(), object.name(), object.type(), cols, pk);
            return new ObjectDetail(object.schemaName(), object.name(), object.type(), cols, idx, pk, rowCount, ddl);
        }
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
        TreeMap<Short, String> ordered = new TreeMap<>();
        try (ResultSet rs = meta.getPrimaryKeys(catalog, schema, table)) {
            while (rs.next()) {
                ordered.put(rs.getShort("KEY_SEQ"), rs.getString("COLUMN_NAME"));
            }
        }
        return new ArrayList<>(ordered.values());
    }

    private Long rowCount(Connection connection, String schema, String table) {
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM " + table(schema, table))) {
            return rs.next() ? rs.getLong(1) : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private String ddl(String schema, String table, String type, List<ColumnInfo> columns, List<String> primaryKeys) {
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
                        rs.getString("REMARKS")
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
}
