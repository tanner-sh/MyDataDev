package com.example.dbadmin.core;

import com.example.dbadmin.dto.ApiDtos.SqlResult;
import com.example.dbadmin.dto.ApiDtos.DatabaseCapabilities;
import com.example.dbadmin.dto.ApiDtos.ObjectDetail;
import com.example.dbadmin.dto.ApiDtos.TableDesignRequest;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

public interface DatabaseDialect {
    enum NamespaceKind {
        SCHEMA,
        CATALOG
    }

    record MetadataScope(String catalog, String schemaPattern) {
    }

    boolean supports(String dbType, String jdbcUrl);

    default DatabaseCapabilities capabilities() {
        return new DatabaseCapabilities(true, true, false, false, List.of());
    }

    String pageQuery(String baseSql, int limit, int offset);

    SqlResult explain(Connection connection, String sql, int maxRows, int timeoutSeconds) throws Exception;

    default NamespaceKind namespaceKind() {
        return NamespaceKind.SCHEMA;
    }

    default String currentSchema(Connection connection) throws Exception {
        try {
            String schema = connection.getSchema();
            if (schema != null && !schema.isBlank()) {
                return schema;
            }
        } catch (SQLException | AbstractMethodError ignored) {
            // Some JDBC drivers do not implement Connection#getSchema.
        }
        try {
            String catalog = connection.getCatalog();
            return catalog == null || catalog.isBlank() ? null : catalog;
        } catch (SQLException ignored) {
            return null;
        }
    }

    default Optional<String> nativeDdl(Connection connection, String schemaName, String objectName, String objectType) throws Exception {
        return Optional.empty();
    }

    List<String> alterTableSql(String schemaName, String tableName, ObjectDetail original, TableDesignRequest design);

    default String quoteIdentifier(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    default String qualifiedName(String namespace, String objectName) {
        return namespace == null || namespace.isBlank()
                ? quoteIdentifier(objectName)
                : quoteIdentifier(namespace) + "." + quoteIdentifier(objectName);
    }

    default MetadataScope metadataScope(Connection connection, String namespace) throws SQLException {
        if (namespaceKind() == NamespaceKind.CATALOG) {
            String catalog = namespace == null || namespace.isBlank() ? connection.getCatalog() : namespace;
            return new MetadataScope(catalog, null);
        }
        return new MetadataScope(connection.getCatalog(), namespace == null || namespace.isBlank() ? null : namespace);
    }

    default String resultNamespace(ResultSet resultSet) throws SQLException {
        return resultSet.getString(namespaceKind() == NamespaceKind.CATALOG ? "TABLE_CAT" : "TABLE_SCHEM");
    }

    default String paginationHelperColumn() {
        return null;
    }

    default void configureReadStatement(Connection connection, Statement statement, int fetchSize, int timeoutSeconds) throws SQLException {
        if (timeoutSeconds > 0) {
            try {
                statement.setQueryTimeout(timeoutSeconds);
            } catch (SQLFeatureNotSupportedException ignored) {
                // Some otherwise usable JDBC drivers do not implement timeouts.
            }
        }
        if (fetchSize > 0) {
            try {
                statement.setFetchSize(fetchSize);
            } catch (SQLFeatureNotSupportedException ignored) {
                // Fetch size is a hint and may be unsupported by a driver.
            }
        }
    }

    default void configureStreamingStatement(Connection connection, Statement statement, int fetchSize, int timeoutSeconds) throws SQLException {
        configureReadStatement(connection, statement, fetchSize, timeoutSeconds);
    }

    default String literal(Object value) {
        if (value == null) {
            return "NULL";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        return "'" + value.toString().replace("'", "''") + "'";
    }
}
