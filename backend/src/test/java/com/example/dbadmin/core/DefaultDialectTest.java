package com.example.dbadmin.core;

import org.junit.jupiter.api.Test;

import java.sql.Connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DefaultDialectTest {
    private final DefaultDialect dialect = new DefaultDialect();

    @Test
    void resolvesJdbcSchemaBeforeCatalog() throws Exception {
        Connection connection = mock(Connection.class);
        when(connection.getSchema()).thenReturn("PUBLIC");
        when(connection.getCatalog()).thenReturn("demo");

        assertThat(dialect.currentSchema(connection)).isEqualTo("PUBLIC");
    }

    @Test
    void fallsBackToCurrentCatalogWhenSchemaIsUnavailable() throws Exception {
        Connection connection = mock(Connection.class);
        when(connection.getSchema()).thenReturn(null);
        when(connection.getCatalog()).thenReturn("demo");

        assertThat(dialect.currentSchema(connection)).isEqualTo("demo");
    }
}
