package com.example.dbadmin.service;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;

class ReadOnlyQueryScopeTest {
    @Test
    void doesNotInvokeUnsupportedTransactionsOnClickHouseLikeDrivers() throws Exception {
        Connection connection = mock(Connection.class);
        var metadata = mock(java.sql.DatabaseMetaData.class);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.supportsTransactions()).thenReturn(false);
        try (var scope = ReadOnlyQueryScope.begin(connection, true)) { }
        org.mockito.Mockito.verify(connection, org.mockito.Mockito.never()).setAutoCommit(false);
        org.mockito.Mockito.verify(connection, org.mockito.Mockito.never()).rollback();
        org.mockito.Mockito.verify(connection, org.mockito.Mockito.never()).commit();
    }

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

    @Test
    void failedRollbackDiscardsTheConnectionWithoutReenablingAutoCommit() throws Exception {
        Connection connection = mock(Connection.class);
        when(connection.getAutoCommit()).thenReturn(true);
        var lease = mock(BorrowedConnection.Lease.class);
        when(connection.unwrap(BorrowedConnection.Lease.class)).thenReturn(lease);
        var failure = new SQLException("rollback disconnected", "08006");
        doThrow(failure).when(connection).rollback();

        assertThatThrownBy(() -> ReadOnlyQueryScope.begin(connection, true).close()).isSameAs(failure);

        verify(lease).discardOnClose();
        // 回滚失败后 setAutoCommit(true) 可能提交尚未回滚的事务。
        verify(connection, never()).setAutoCommit(true);
        verify(connection, never()).setReadOnly(false);
    }

    @Test
    void failedStateRestorationDiscardsTheConnectionAndPreservesTheDriverError() throws Exception {
        Connection connection = mock(Connection.class);
        when(connection.getAutoCommit()).thenReturn(true);
        var lease = mock(BorrowedConnection.Lease.class);
        when(connection.unwrap(BorrowedConnection.Lease.class)).thenReturn(lease);
        var failure = new java.sql.SQLNonTransientConnectionException("Connection is closed", "08000", 1220);
        doThrow(failure).when(connection).setAutoCommit(true);

        assertThatThrownBy(() -> ReadOnlyQueryScope.begin(connection, true).close()).isSameAs(failure);

        verify(connection).rollback();
        verify(lease).discardOnClose();
        verify(connection, never()).setReadOnly(false);
    }

    @Test
    void failedReadOnlyRestorationAlsoDiscardsTheConnection() throws Exception {
        Connection connection = mock(Connection.class);
        when(connection.getAutoCommit()).thenReturn(true);
        var lease = mock(BorrowedConnection.Lease.class);
        when(connection.unwrap(BorrowedConnection.Lease.class)).thenReturn(lease);
        var failure = new SQLException("cannot restore read-only hint");
        doThrow(failure).when(connection).setReadOnly(false);

        assertThatThrownBy(() -> ReadOnlyQueryScope.begin(connection, true).close()).isSameAs(failure);

        verify(connection).rollback();
        verify(connection).setAutoCommit(true);
        verify(lease).discardOnClose();
    }

    @Test
    void keepsTheQueryErrorPrimaryAndAttachesCleanupAndDiscardFailures() throws Exception {
        Connection connection = mock(Connection.class);
        when(connection.getAutoCommit()).thenReturn(true);
        var readFailure = new SQLException("result read failed");
        var rollbackFailure = new SQLException("rollback failed");
        var discardFailure = new SQLException("cannot locate pool lease");
        doThrow(rollbackFailure).when(connection).rollback();
        when(connection.unwrap(BorrowedConnection.Lease.class)).thenThrow(discardFailure);

        assertThatThrownBy(() -> {
            try (var scope = ReadOnlyQueryScope.begin(connection, true)) { throw readFailure; }
        }).isSameAs(readFailure);
        assertThat(readFailure.getSuppressed()).containsExactly(rollbackFailure);
        assertThat(rollbackFailure.getSuppressed()).containsExactly(discardFailure);
        verify(connection, never()).setAutoCommit(true);
    }

    @Test
    void failedCleanupEvictsThePhysicalConnectionAndReleasesASingleSlotPool() throws Exception {
        var config = new com.zaxxer.hikari.HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:readonly-cleanup-" + java.util.UUID.randomUUID());
        config.setUsername("sa");
        config.setMinimumIdle(0);
        config.setMaximumPoolSize(1);
        config.setConnectionTimeout(1000);
        try (var pool = new com.zaxxer.hikari.HikariDataSource(config)) {
            org.h2.jdbc.JdbcConnection broken;
            try (Connection borrowed = BorrowedConnection.wrap(pool.getConnection(), pool)) {
                var scope = ReadOnlyQueryScope.begin(borrowed, true);
                broken = borrowed.unwrap(org.h2.jdbc.JdbcConnection.class);
                broken.close();
                assertThatThrownBy(scope::close).isInstanceOf(SQLException.class);
            }
            assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero();
            try (Connection next = pool.getConnection()) {
                assertThat(next.unwrap(org.h2.jdbc.JdbcConnection.class)).isNotSameAs(broken);
                assertThat(next.isValid(1)).isTrue();
            }
        }
    }
}
