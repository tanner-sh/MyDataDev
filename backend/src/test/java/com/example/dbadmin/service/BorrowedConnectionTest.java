package com.example.dbadmin.service;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class BorrowedConnectionTest {
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void discardsDirtyConnectionWithoutResettingAnEvictedPoolEntry(boolean commit) throws Exception {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:discard-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        config.setUsername("sa");
        config.setMinimumIdle(0);
        config.setMaximumPoolSize(1);
        config.setConnectionTimeout(1000);
        try (HikariDataSource pool = new HikariDataSource(config)) {
            Connection borrowed = BorrowedConnection.wrap(pool.getConnection(), pool);
            long originalSession = sessionId(borrowed);
            try (var statement = borrowed.createStatement()) {
                statement.execute("CREATE TABLE t(id INT)");
                borrowed.setAutoCommit(false);
                statement.execute("INSERT INTO t VALUES (1)");
            }
            if (commit) borrowed.commit(); else borrowed.rollback();
            BorrowedConnection.discardOnClose(borrowed);

            assertThatCode(borrowed::close).doesNotThrowAnyException();
            assertThat(borrowed.isClosed()).isTrue();
            assertThatCode(borrowed::close).doesNotThrowAnyException();
            assertThatThrownBy(borrowed::createStatement).isInstanceOf(SQLException.class);
            assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero();

            // A one-slot pool must recover with a new physical session, preserving the confirmed outcome.
            try (Connection next = BorrowedConnection.wrap(pool.getConnection(), pool);
                 var statement = next.createStatement();
                 var rows = statement.executeQuery("SELECT COUNT(*) FROM t")) {
                assertThat(sessionId(next)).isNotEqualTo(originalSession);
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isEqualTo(commit ? 1 : 0);
            }
        }
    }

    private long sessionId(Connection connection) throws Exception {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT SESSION_ID()")) {
            rows.next();
            return rows.getLong(1);
        }
    }
}
