package com.example.dbadmin.core;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OceanBase Connector/J 在 Oracle 模式的默认配置（compatibleOjdbcVersion=6）下，getSchema 与
 * setSchema 都直接抛 AbstractMethodError。读 schema、切 schema 都必须完全绕开这两个方法。
 */
class OceanBaseOracleDialectTest {
    private static final String CURRENT_SCHEMA_SQL = "SELECT SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA') FROM DUAL";

    private final OceanBaseOracleDialect dialect = new OceanBaseOracleDialect();

    @Test
    void explainsDirectlyWithoutOraclePlanTableSyntax() throws Exception {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet rows = mock(ResultSet.class);
        ResultSetMetaData metadata = mock(ResultSetMetaData.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery("EXPLAIN SELECT 1 AS PROBE FROM DUAL")).thenReturn(rows);
        when(rows.getMetaData()).thenReturn(metadata);
        when(metadata.getColumnCount()).thenReturn(1);
        when(metadata.getColumnLabel(1)).thenReturn("Query Plan");
        when(metadata.getColumnTypeName(1)).thenReturn("VARCHAR");
        when(rows.next()).thenReturn(true, true, false);
        when(rows.getObject(1)).thenReturn("EXPRESSION");

        var plan = dialect.explain(connection, "SELECT 1 AS PROBE FROM DUAL", 1, 7);

        assertThat(plan.rows()).containsExactly(java.util.List.of("EXPRESSION"));
        assertThat(plan.truncated()).isTrue();
        assertThat(dialect.capabilities().explain()).isTrue();
        verify(statement).setQueryTimeout(7);
        verify(statement).setMaxRows(2);
        verify(statement, never()).execute(anyString());
        verify(connection, never()).prepareStatement(anyString());
        verify(rows).close();
        verify(statement).close();
    }

    @Test
    void preservesExplainErrorWithoutRetryingOrRunningTheQuery() throws Exception {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);
        SQLException error = new SQLException("unknown column", "42000", 904);
        when(statement.executeQuery("EXPLAIN SELECT MISSING FROM DUAL")).thenThrow(error);

        assertThatThrownBy(() -> dialect.explain(connection, "SELECT MISSING FROM DUAL", 10, 7)).isSameAs(error);
        verify(statement).executeQuery("EXPLAIN SELECT MISSING FROM DUAL");
        verify(statement, never()).execute(anyString());
        verify(connection, never()).prepareStatement(anyString());
        verify(statement).close();
    }

    @Test
    void switchesTheSchemaWithAlterSessionInsteadOfTheUnimplementedJdbcMethod() throws Exception {
        Statement statement = mock(Statement.class);
        Connection connection = oracleModeDriver(statement, "SWHY_USER");

        dialect.activateNamespace(connection, "SWHY_FBIP");

        verify(statement).execute("ALTER SESSION SET CURRENT_SCHEMA = \"SWHY_FBIP\"");
        verify(connection, never()).setSchema(anyString());
    }

    @Test
    void skipsTheSwitchWhenTheSessionIsAlreadyInThatSchema() throws Exception {
        Statement statement = mock(Statement.class);
        Connection connection = oracleModeDriver(statement, "SWHY_FBIP");

        dialect.activateNamespace(connection, "SWHY_FBIP");

        verify(statement, never()).execute(anyString());
    }

    @Test
    void readsTheNamespaceFromTheSessionContext() throws Exception {
        Connection connection = oracleModeDriver(mock(Statement.class), "SWHY_FBIP");

        assertThat(dialect.currentNamespace(connection)).isEqualTo("SWHY_FBIP");
        verify(connection, never()).getSchema();
    }

    /** 与驱动的真实行为一致：两个 JDBC 方法抛 AbstractMethodError，会话上下文查询正常。 */
    private static Connection oracleModeDriver(Statement statement, String sessionSchema) throws Exception {
        Connection connection = mock(Connection.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(connection.getSchema()).thenThrow(new AbstractMethodError("Unimplemented method: getSchema()"));
        doThrow(new AbstractMethodError("Unimplemented method: getSchema()")).when(connection).setSchema(anyString());
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(CURRENT_SCHEMA_SQL)).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getString(1)).thenReturn(sessionSchema);
        return connection;
    }
}
