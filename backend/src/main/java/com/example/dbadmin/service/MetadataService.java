package com.example.dbadmin.service;

import com.example.dbadmin.dto.ApiDtos.ColumnInfo;
import com.example.dbadmin.dto.ApiDtos.DbObject;
import com.example.dbadmin.dto.ApiDtos.IndexInfo;
import com.example.dbadmin.dto.ApiDtos.MetadataResponse;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

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
            List<String> cols = new ArrayList<>();
            try (ResultSet rs = meta.getPrimaryKeys(connection.getCatalog(), schema, table)) {
                while (rs.next()) {
                    cols.add(rs.getString("COLUMN_NAME"));
                }
            }
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
