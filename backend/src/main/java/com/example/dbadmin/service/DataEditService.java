package com.example.dbadmin.service;

import com.example.dbadmin.core.DatabaseDialect;
import com.example.dbadmin.core.DialectRegistry;
import com.example.dbadmin.dto.ApiDtos.DataPreviewRequest;
import com.example.dbadmin.dto.ApiDtos.DataPreviewResponse;
import com.example.dbadmin.dto.ApiDtos.DataCommitResponse;
import com.example.dbadmin.dto.ApiDtos.RowChange;
import com.example.dbadmin.dto.ApiDtos.TableDataResponse;
import com.example.dbadmin.model.DbConnection;
import com.example.dbadmin.repo.AuditRepository;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.sql.Clob;
import java.sql.Blob;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class DataEditService {
    private final MetadataService metadata;
    private final ConnectionService connections;
    private final AuditRepository audit;
    private final DialectRegistry dialectRegistry;

    public DataEditService(MetadataService metadata, ConnectionService connections, AuditRepository audit, DialectRegistry dialectRegistry) {
        this.metadata = metadata;
        this.connections = connections;
        this.audit = audit;
        this.dialectRegistry = dialectRegistry;
    }

    public TableDataResponse table(long connectionId, String schemaName, String tableName, int page, int pageSize) throws Exception {
        int safePage = Math.max(page, 0);
        int safePageSize = Math.min(Math.max(pageSize, 1), 500);
        List<String> keyColumns = metadata.primaryOrUniqueColumns(connectionId, schemaName, tableName);
        DbConnection dbConnection = connections.require(connectionId);
        DatabaseDialect dialect = dialectRegistry.dialectFor(dbConnection);
        String baseSql = "SELECT * FROM " + table(schemaName, tableName);
        String sql = dialect.pageQuery(baseSql, safePageSize, safePage * safePageSize);
        try (Connection connection = connections.open(connectionId); Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
            ResultSetMetaData md = rs.getMetaData();
            List<String> columns = new ArrayList<>();
            for (int i = 1; i <= md.getColumnCount(); i++) {
                String column = md.getColumnLabel(i);
                if (!"DBADMIN_RN".equalsIgnoreCase(column)) {
                    columns.add(column);
                }
            }
            List<Map<String, Object>> rows = new ArrayList<>();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (String column : columns) {
                    row.put(column, serializableValue(rs.getObject(column)));
                }
                rows.add(row);
            }
            return new TableDataResponse(columns, rows, keyColumns, !keyColumns.isEmpty());
        }
    }

    public DataPreviewResponse preview(DataPreviewRequest request) throws Exception {
        List<String> sql = buildSql(request);
        return new DataPreviewResponse(sql);
    }

    public DataCommitResponse commit(DataPreviewRequest request, String actor) throws Exception {
        List<String> sql = buildSql(request);
        int affected = 0;
        try (Connection connection = connections.open(request.connectionId())) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                for (String line : sql) {
                    affected += statement.executeUpdate(executableSql(line));
                }
                connection.commit();
            } catch (Exception e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        }
        audit.log(actor, "DATA_COMMIT", "connection:" + request.connectionId() + " table:" + request.tableName(), String.join("\n", sql));
        return new DataCommitResponse(sql, affected);
    }

    private List<String> buildSql(DataPreviewRequest request) throws Exception {
        if (request.changes() == null || request.changes().isEmpty()) {
            return List.of();
        }
        List<String> guard = metadata.primaryOrUniqueColumns(request.connectionId(), request.schemaName(), request.tableName());
        List<String> sql = new ArrayList<>();
        for (RowChange change : request.changes()) {
            String type = change.type().toUpperCase();
            if (("UPDATE".equals(type) || "DELETE".equals(type)) && guard.isEmpty()) {
                throw new IllegalArgumentException("Updates and deletes require a primary key or unique index");
            }
            sql.add(switch (type) {
                case "INSERT" -> insertSql(request, change.values());
                case "UPDATE" -> updateSql(request, change.values(), change.key());
                case "DELETE" -> deleteSql(request, change.key());
                default -> throw new IllegalArgumentException("Unsupported change type: " + change.type());
            });
        }
        return sql;
    }

    private String insertSql(DataPreviewRequest request, Map<String, Object> values) {
        String cols = values.keySet().stream().map(this::quote).collect(Collectors.joining(", "));
        String vals = values.values().stream().map(this::literal).collect(Collectors.joining(", "));
        return "INSERT INTO " + table(request) + " (" + cols + ") VALUES (" + vals + ");";
    }

    private String updateSql(DataPreviewRequest request, Map<String, Object> values, Map<String, Object> key) {
        String set = values.entrySet().stream().map(e -> quote(e.getKey()) + " = " + literal(e.getValue())).collect(Collectors.joining(", "));
        return "UPDATE " + table(request) + " SET " + set + " WHERE " + where(key) + ";";
    }

    private String deleteSql(DataPreviewRequest request, Map<String, Object> key) {
        return "DELETE FROM " + table(request) + " WHERE " + where(key) + ";";
    }

    private String where(Map<String, Object> key) {
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException("Row key is required");
        }
        return key.entrySet().stream().map(e -> quote(e.getKey()) + " = " + literal(e.getValue())).collect(Collectors.joining(" AND "));
    }

    private String table(DataPreviewRequest request) {
        return request.schemaName() == null || request.schemaName().isBlank()
                ? quote(request.tableName())
                : quote(request.schemaName()) + "." + quote(request.tableName());
    }

    private String table(String schemaName, String tableName) {
        return schemaName == null || schemaName.isBlank()
                ? quote(tableName)
                : quote(schemaName) + "." + quote(tableName);
    }

    private String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private String literal(Object value) {
        if (value == null) {
            return "NULL";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        return "'" + value.toString().replace("'", "''") + "'";
    }

    private String executableSql(String sql) {
        String trimmed = sql.trim();
        return trimmed.endsWith(";") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    private Object serializableValue(Object value) throws Exception {
        if (value instanceof Clob clob) {
            long length = clob.length();
            return clob.getSubString(1, (int) Math.min(length, 10_000));
        }
        if (value instanceof Blob blob) {
            return "<BLOB " + blob.length() + " bytes>";
        }
        return value;
    }
}
