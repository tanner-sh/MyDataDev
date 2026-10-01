package com.example.dbadmin.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/** Uses the opt-in TEST_ORACLE_* fixture; no customer ETL objects or credentials. */
@Timeout(120)
class DatabaseScriptTransactionCompatibilityTest {
    @Test void oracleKeepsExplicitCommitAndRollsBackFailedTail() throws Exception {
        try (var f = new DatabaseCompatibilityTest.Fixture("oracle")) {
            String table = f.table(f.pk("id"));
            try (var c = f.connections.open(1L); var tx = new SqlScriptTransaction(c, () -> {}); var s = c.createStatement()) {
                tx.execute(s, "INSERT INTO " + f.q(table) + " VALUES (1)", 1);
                tx.execute(s, "COMMIT", 2);
                tx.execute(s, "INSERT INTO " + f.q(table) + " VALUES (2)", 3);
                tx.fail(catchException(() -> tx.execute(s, "INSERT INTO " + f.q(table) + " VALUES (2)", 4)));
            }
            assertThat(f.number("SELECT COUNT(*) FROM " + f.q(table))).isEqualTo(1);
        }
    }
    @Test void oracleProcedureBodyIsInstalledWholeAndInternalCommitPersists() throws Exception {
        try (var f = new DatabaseCompatibilityTest.Fixture("oracle")) {
            String table = f.table(f.pk("id"));
            String procedure = "p_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
            String script = "-- installation\nCREATE OR REPLACE PROCEDURE " + procedure
                    + " AS\nBEGIN\n INSERT INTO " + f.q(table) + " VALUES (1);\n COMMIT;\nEND;\n/\n"
                    + "BEGIN " + procedure + "; END;\n/\n";
            try {
                try (var c = f.connections.open(1L); var tx = new SqlScriptTransaction(c, () -> {}); var s = c.createStatement()) {
                    int index = 0;
                    for (var unit : new SqlScriptSplitter().split(script, "oracle")) tx.execute(s, unit.sql(), ++index);
                    tx.finish(false, index);
                }
                assertThat(f.number("SELECT COUNT(*) FROM " + f.q(table))).isEqualTo(1);
                assertThat(f.number("SELECT COUNT(*) FROM USER_ERRORS WHERE NAME=UPPER('" + procedure + "')")).isZero();
            } finally { f.execute("DROP PROCEDURE " + procedure); }
        }
    }
}
