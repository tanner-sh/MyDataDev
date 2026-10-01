package com.example.dbadmin.service;

import org.junit.jupiter.api.Test;
import java.sql.*;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SqlScriptTransactionTest {
    @Test void preservesEarlierCommitButRollsBackFailureTail() throws Exception {
        try (Connection c = database(); Statement s = c.createStatement(); SqlScriptTransaction tx = new SqlScriptTransaction(c, () -> {})) {
            tx.execute(s, "INSERT INTO t VALUES (1)", 1);
            tx.execute(s, "COMMIT", 2);
            tx.execute(s, "INSERT INTO t VALUES (2)", 3);
            Exception failure = catchException(() -> tx.execute(s, "INSERT INTO missing VALUES (3)", 4));
            tx.fail(failure);
            assertThat(rows(c)).isEqualTo(1);
            assertThat(tx.lastCommit).isEqualTo(2);
            assertThat(tx.outcome).isEqualTo("ROLLED_BACK");
        }
    }
    @Test void honorsSavepointAndRollbackThenCommit() throws Exception {
        try (Connection c = database(); Statement s = c.createStatement(); SqlScriptTransaction tx = new SqlScriptTransaction(c, () -> {})) {
            tx.execute(s, "INSERT INTO t VALUES (1)", 1);
            tx.execute(s, "ROLLBACK", 2);
            tx.execute(s, "INSERT INTO t VALUES (2)", 3);
            tx.execute(s, "SAVEPOINT keep", 4);
            tx.execute(s, "INSERT INTO t VALUES (3)", 5);
            tx.execute(s, "ROLLBACK TO SAVEPOINT keep", 6);
            tx.execute(s, "COMMIT", 7);
            tx.finish(false, 7);
            assertThat(rows(c)).isEqualTo(1);
            assertThat(tx.commits).isEqualTo(1);
            assertThat(tx.rollbacks).isEqualTo(2);
        }
    }
    @Test void endPolicyIsExplicitAndCloseNeverCommits() throws Exception {
        for (boolean commit : new boolean[]{false, true}) {
            try (Connection c = database(); Statement s = c.createStatement()) {
                try (SqlScriptTransaction tx = new SqlScriptTransaction(c, () -> {})) {
                    tx.execute(s, "INSERT INTO t VALUES (1)", 1);
                    tx.finish(commit, 1);
                }
                assertThat(rows(c)).isEqualTo(commit ? 1 : 0);
                try (SqlScriptTransaction tx = new SqlScriptTransaction(c, () -> {})) {
                    tx.execute(s, "INSERT INTO t VALUES (2)", 1);
                }
                assertThat(rows(c)).isEqualTo(commit ? 1 : 0);
            }
        }
    }
    @Test void failedCommitRemainsUnknownEvenIfRollbackSucceeds() throws Exception {
        Connection c = mock(Connection.class);
        Statement s = mock(Statement.class);
        when(s.execute("COMMIT")).thenThrow(new SQLException("lost reply"));
        try (SqlScriptTransaction tx = new SqlScriptTransaction(c, () -> {})) {
            tx.fail(catchException(() -> tx.execute(s, "COMMIT", 2)));
            assertThat(tx.outcome).isEqualTo("UNKNOWN");
            assertThat(tx.lastCommit).isNull();
            verify(c).rollback();
        }
    }
    @Test void failedRollbackAbortsConnectionAndPreservesOriginalError() throws Exception {
        Connection c = mock(Connection.class);
        doThrow(new SQLException("rollback lost")).when(c).rollback();
        try (SqlScriptTransaction tx = new SqlScriptTransaction(c, () -> {})) {
            Exception original = new SQLException("statement failed");
            tx.fail(original);
            assertThat(tx.outcome).isEqualTo("UNKNOWN");
            assertThat(original.getSuppressed()).hasSize(1);
            verify(c).abort(any());
            verify(c, never()).commit();
        }
    }
    private Connection database() throws Exception {
        Connection c = DriverManager.getConnection("jdbc:h2:mem:script-" + UUID.randomUUID(), "sa", "");
        try (Statement s = c.createStatement()) { s.execute("CREATE TABLE t(id INT PRIMARY KEY)"); }
        return c;
    }
    private int rows(Connection c) throws Exception {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM t")) { rs.next(); return rs.getInt(1); }
    }
}
