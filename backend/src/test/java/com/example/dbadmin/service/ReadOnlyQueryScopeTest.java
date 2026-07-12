package com.example.dbadmin.service;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ReadOnlyQueryScopeTest {
    @Test
    void doesNotProbeWritableConnections() throws Exception {
        Connection connection = mock(Connection.class);

        ReadOnlyQueryScope.begin(connection, false).close();

        verifyNoInteractions(connection);
    }

    @Test
    void restoresReadOnlyHintWhenTransactionSetupFails() throws Exception {
        Connection connection = mock(Connection.class);
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.isReadOnly()).thenReturn(false);
        doThrow(new SQLException("cannot disable auto commit")).when(connection).setAutoCommit(false);

        assertThatThrownBy(() -> ReadOnlyQueryScope.begin(connection, true))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("cannot disable auto commit");

        var ordered = inOrder(connection);
        ordered.verify(connection).setReadOnly(true);
        ordered.verify(connection).setAutoCommit(false);
        ordered.verify(connection).setReadOnly(false);
    }

    @Test
    void stillUsesRollbackWhenReadOnlyHintIsUnsupported() throws Exception {
        Connection connection = mock(Connection.class);
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.isReadOnly()).thenReturn(false);
        doThrow(new SQLFeatureNotSupportedException()).when(connection).setReadOnly(true);

        try (ReadOnlyQueryScope ignored = ReadOnlyQueryScope.begin(connection, true)) {
            // Query executes inside the scope.
        }

        var ordered = inOrder(connection);
        ordered.verify(connection).setAutoCommit(false);
        ordered.verify(connection).rollback();
        ordered.verify(connection).setAutoCommit(true);
    }
}
