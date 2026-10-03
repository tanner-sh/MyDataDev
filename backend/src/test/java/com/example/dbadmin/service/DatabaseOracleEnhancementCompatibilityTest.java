package com.example.dbadmin.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

@Timeout(value=2, unit=TimeUnit.MINUTES, threadMode=Timeout.ThreadMode.SEPARATE_THREAD)
class DatabaseOracleEnhancementCompatibilityTest {
    @Test void ordinaryAccountReadsPartitionsAndAllocatedStorage() throws Exception {
        try (var f = new DatabaseCompatibilityTest.Fixture("oracle")) {
            var table = f.reserveTable();
            f.execute("CREATE TABLE " + f.q(table) + " (id NUMBER, note CLOB) PARTITION BY RANGE (id) (PARTITION p1 VALUES LESS THAN (10), PARTITION p2 VALUES LESS THAN (MAXVALUE))");
            f.execute("INSERT INTO " + f.q(table) + " VALUES (1, '中文')");
            var info = new OracleTableStorageService(f.connections, f.metadata).inspect(1, f.schema, table);
            assertThat(info.partitioned()).isTrue();
            assertThat(info.partitions()).extracting(OracleTableStorageService.Partition::name).containsExactly("P1", "P2");
            assertThat(Long.parseLong(info.tableBytes())).isPositive();
            assertThat(info.warnings()).isEmpty();
        }
    }
    @Test void explainIsolatesItsRowsAndDoesNotCommitCallerTransaction() throws Exception {
        try (var f = new DatabaseCompatibilityTest.Fixture("oracle")) {
            var table = f.table("id NUMBER");
            f.jdbc.setAutoCommit(false);
            f.execute("INSERT INTO " + f.q(table) + " VALUES (1)");
            f.execute("EXPLAIN PLAN SET STATEMENT_ID='KEEP_OTHER_PLAN' FOR SELECT * FROM DUAL");
            var result = f.dialect.explain(f.jdbc, "SELECT * FROM " + f.q(table), 100, 10);
            assertThat(result.rows()).isNotEmpty();
            assertThat(f.number("SELECT COUNT(*) FROM PLAN_TABLE WHERE STATEMENT_ID='KEEP_OTHER_PLAN'")).isPositive();
            assertThat(f.number("SELECT COUNT(*) FROM PLAN_TABLE WHERE STATEMENT_ID LIKE 'MDD_%'")).isZero();
            assertThatThrownBy(() -> f.dialect.explain(f.jdbc, "SELECT missing_column FROM DUAL", 100, 10)).hasMessageContaining("ORA-00904");
            f.jdbc.rollback();
            assertThat(f.number("SELECT COUNT(*) FROM " + f.q(table))).isZero();
            f.jdbc.setAutoCommit(true);
            try (var ignored = ReadOnlyQueryScope.begin(f.jdbc, true)) {
                assertThat(f.dialect.explain(f.jdbc, "SELECT * FROM DUAL", 100, 10).rows()).isNotEmpty();
            }
            assertThat(f.jdbc.getAutoCommit()).isTrue();
        }
    }
}
