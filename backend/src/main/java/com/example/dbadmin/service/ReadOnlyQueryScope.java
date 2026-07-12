package com.example.dbadmin.service;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Adds a rollback-only JDBC transaction around queries executed through a
 * connection configured as read-only. This supplements (rather than replaces)
 * database-side read-only credentials.
 */
final class ReadOnlyQueryScope implements AutoCloseable {
    private final Connection connection;
    private final boolean active;
    private final boolean previousAutoCommit;
    private final boolean previousReadOnly;
    private final boolean restoreReadOnly;

    private ReadOnlyQueryScope(Connection connection, boolean active, boolean previousAutoCommit, boolean previousReadOnly, boolean restoreReadOnly) {
        this.connection = connection;
        this.active = active;
        this.previousAutoCommit = previousAutoCommit;
        this.previousReadOnly = previousReadOnly;
        this.restoreReadOnly = restoreReadOnly;
    }

    static ReadOnlyQueryScope begin(Connection connection, boolean enforce) throws Exception {
        if (!enforce) return new ReadOnlyQueryScope(connection, false, true, false, false);
        boolean autoCommit = connection.getAutoCommit();
        boolean readOnly = false;
        boolean readOnlyKnown = true;
        try {
            readOnly = connection.isReadOnly();
        } catch (SQLException ignored) {
            readOnlyKnown = false;
        }
        boolean changedReadOnly = false;
        try {
            if (readOnlyKnown && !readOnly) {
                try {
                    connection.setReadOnly(true);
                    changedReadOnly = true;
                } catch (SQLException ignored) {
                    // The rollback-only transaction still provides a second
                    // safety layer on drivers without the JDBC hint.
                }
            }
            if (autoCommit) connection.setAutoCommit(false);
            return new ReadOnlyQueryScope(connection, true, autoCommit, readOnly, changedReadOnly);
        } catch (Exception setupFailure) {
            try {
                if (connection.getAutoCommit() != autoCommit) {
                    connection.rollback();
                    connection.setAutoCommit(autoCommit);
                }
            } catch (Exception restoreFailure) {
                setupFailure.addSuppressed(restoreFailure);
            }
            if (changedReadOnly) {
                try {
                    connection.setReadOnly(readOnly);
                } catch (Exception restoreFailure) {
                    setupFailure.addSuppressed(restoreFailure);
                }
            }
            throw setupFailure;
        }
    }

    @Override
    public void close() throws Exception {
        if (!active) return;
        Exception failure = null;
        try {
            connection.rollback();
        } catch (Exception error) {
            failure = error;
        }
        try {
            connection.setAutoCommit(previousAutoCommit);
        } catch (Exception error) {
            if (failure == null) failure = error;
            else failure.addSuppressed(error);
        }
        if (restoreReadOnly) {
            try {
                connection.setReadOnly(previousReadOnly);
            } catch (Exception error) {
                if (failure == null) failure = error;
                else failure.addSuppressed(error);
            }
        }
        if (failure != null) throw failure;
    }
}
