package com.example.dbadmin.core;

import org.junit.jupiter.api.Test;
import java.sql.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class DatabaseServerInfoTest {
    private Connection connection() throws Exception {
        var connection = mock(Connection.class);
        var metadata = mock(DatabaseMetaData.class);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseProductName()).thenReturn("OceanBase");
        when(metadata.getDatabaseProductVersion()).thenReturn("4.3.5");
        when(metadata.getDriverName()).thenReturn("OceanBase Connector/J");
        when(metadata.getDriverVersion()).thenReturn("2.4.17");
        return connection;
    }
    @Test void actualModeWinsOverConfiguredMode() throws Exception {
        var connection = connection();
        var statement = mock(Statement.class);
        var result = mock(ResultSet.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(result);
        when(result.next()).thenReturn(true, false);
        when(result.getString(1)).thenReturn("1002");
        when(result.getString(2)).thenReturn("app");
        when(result.getString(3)).thenReturn("MYSQL");
        var info = DatabaseServerInfo.inspect(connection, "oceanbase-oracle");
        assertThat(info.typeMatches()).isFalse();
        assertThat(info.tenantName()).isEqualTo("app");
        assertThat(info.warnings()).anyMatch(w -> w.contains("不一致"));
        verify(statement).setQueryTimeout(5);
    }
    @Test void inaccessibleCatalogIsUnknownRatherThanVerified() throws Exception {
        var connection = connection();
        var statement = mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenThrow(new SQLException("denied"));
        var info = DatabaseServerInfo.inspect(connection, "oceanbase-mysql");
        assertThat(info.typeMatches()).isNull();
        assertThat(info.tenantName()).isNull();
        assertThatThrownBy(() -> DatabaseServerInfo.validateOceanBase(connection, "oceanbase-mysql")).hasMessageContaining("无法验证");
    }
    @Test void otherDatabasesDoNotReadOceanBaseCatalogs() throws Exception {
        var connection = connection();
        DatabaseServerInfo.inspect(connection, "mysql");
        verify(connection, never()).createStatement();
    }
}
