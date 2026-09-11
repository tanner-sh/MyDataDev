package com.example.dbadmin.core;

import com.example.dbadmin.dto.ApiDtos.DatabaseCapabilities;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

public class OceanBaseOracleDialect extends OracleDialect {
    @Override
    public DatabaseCapabilities capabilities() {
        return new DatabaseCapabilities(true, true, true, true, List.of(), List.of(), SchemaObjectCapabilities.oracleFamily());
    }

    @Override
    public boolean supports(String dbType, String jdbcUrl) {
        return "oceanbase-oracle".equalsIgnoreCase(dbType);
    }

    /**
     * 读当前 schema 走会话上下文，不走 {@link Connection#getSchema()}。
     *
     * <p>OceanBase Connector/J 在 Oracle 模式下只有 {@code compatibleOjdbcVersion=8} 时才实现
     * {@code getSchema} / {@code setSchema}，默认值 6 下两者都直接抛 {@link AbstractMethodError}。
     * 那是 {@code Error} 而不是 {@code SQLException}，会越过所有按 {@code Exception} 写的收尾 ——
     * 此前在 SQL 工作台选一个 Schema 执行查询，每次都报「服务器内部错误」并漏掉一条池化连接，
     * 漏到池的上限，这条连接上的一切请求都只剩借连接超时。那个参数是驱动级的兼容开关，
     * 不该为了切 schema 让用户去改连接串，所以这里自己发语句。</p>
     */
    @Override
    public String currentNamespace(Connection connection) throws SQLException {
        return sessionCurrentSchema(connection);
    }

    /** 与 {@link #currentNamespace} 同一套机制：ojdbc 的 {@code setSchema} 发的也是这条语句。 */
    @Override
    public void activateNamespace(Connection connection, String namespace) throws SQLException {
        if (namespace == null || namespace.isBlank()) return;
        String current = null;
        try {
            current = sessionCurrentSchema(connection);
        } catch (SQLException ignored) {
            // 读不到就照切一次，由 ALTER SESSION 给出权威结果。
        }
        if (namespace.equals(current)) return;
        try (Statement statement = connection.createStatement()) {
            // 命名空间来自元数据的规范名或上一次读到的会话值，加引号才不会被再折一次大小写。
            statement.execute("ALTER SESSION SET CURRENT_SCHEMA = " + quoteIdentifier(namespace));
        }
    }
}
