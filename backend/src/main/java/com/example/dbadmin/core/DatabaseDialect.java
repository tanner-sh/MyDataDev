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
import java.util.HexFormat;
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

    List<String> createTableSql(String schemaName, String tableName, TableDesignRequest design);

    String renameTableSql(String schemaName, String tableName, String newTableName);

    String dropTableSql(String schemaName, String tableName);

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

    default void activateNamespace(Connection connection, String namespace) throws SQLException {
        if (namespace == null || namespace.isBlank()) return;
        if (namespaceKind() == NamespaceKind.CATALOG) {
            connection.setCatalog(namespace);
        } else {
            String current = null;
            try {
                current = currentSchema(connection);
            } catch (Exception ignored) {
                // Let setSchema provide the authoritative support/error result.
            }
            if (namespace.equals(current)) return;
            connection.setSchema(namespace);
        }
    }

    default String resultNamespace(ResultSet resultSet) throws SQLException {
        return resultSet.getString(namespaceKind() == NamespaceKind.CATALOG ? "TABLE_CAT" : "TABLE_SCHEM");
    }

    default String paginationHelperColumn() {
        return null;
    }

    /**
     * 目标库上的活动会话。
     *
     * <p>返回 {@code null} 表示该方言不支持 —— 界面据此隐藏会话面板，而不是报一个看不懂的
     * SQL 错误。语句必须返回 sessionId / user / host / database / state / command /
     * durationSeconds / sql 这几列（缺的用 NULL 占位），由 SessionService 统一读取。</p>
     */
    default String activeSessionsSql() {
        return null;
    }

    /**
     * 终止一个会话的语句。会话 id 由调用方校验成纯数字或原样透传，实现负责拼装。
     *
     * <p>返回 {@code null} 表示该方言不支持终止。</p>
     */
    /**
     * 是否支持终止会话。
     *
     * <p>单独给一个能力位，而不是拿一个假的会话号去调 {@link #killSessionSql} 试探 —— Oracle
     * 的会话标识是 {@code SID,SERIAL#} 形式，任何不合法的输入都会抛异常，试探会把整个活动
     * 会话列表带崩。</p>
     */
    default boolean supportsKillSession() {
        return false;
    }

    default String killSessionSql(String sessionId) {
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

    /**
     * 写进「将来会被真正执行的脚本」里的字面量。
     *
     * <p>与 {@link #literal} 的区别只在于取舍：{@code literal} 服务于预览，可读性优先；这个
     * 方法服务于生成的导入脚本，正确性优先 —— 脚本可能在另一台机器、另一个会话设置下执行，
     * 所以它必须选一种不依赖会话状态的写法，哪怕不好看。默认两者相同，只有转义规则会随会话
     * 变化的方言（MySQL 系）才需要区分。</p>
     */
    default String scriptLiteral(Object value) {
        if (value instanceof byte[] bytes) return scriptBinaryLiteral(bytes);
        return literal(value);
    }

    /** Binary literal used in generated scripts. The default is the SQL-standard form. */
    default String scriptBinaryLiteral(byte[] value) {
        return "X'" + HexFormat.of().formatHex(value) + "'";
    }
}
