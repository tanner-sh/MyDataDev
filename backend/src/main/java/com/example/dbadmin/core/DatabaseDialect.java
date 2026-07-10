package com.example.dbadmin.core;

import com.example.dbadmin.dto.ApiDtos.SqlResult;
import com.example.dbadmin.dto.ApiDtos.ObjectDetail;
import com.example.dbadmin.dto.ApiDtos.TableDesignRequest;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

public interface DatabaseDialect {
    boolean supports(String dbType, String jdbcUrl);

    String pageQuery(String baseSql, int limit, int offset);

    SqlResult explain(Connection connection, String sql, int maxRows, int timeoutSeconds) throws Exception;

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
}
