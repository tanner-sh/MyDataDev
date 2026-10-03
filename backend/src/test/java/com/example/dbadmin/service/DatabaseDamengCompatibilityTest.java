package com.example.dbadmin.service;

import com.example.dbadmin.dto.ApiDtos.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

@Timeout(value = 2, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class DatabaseDamengCompatibilityTest {
    @Test
    void explainReturnsStructuredEstimatedPlanAndLeavesConnectionUsable() throws Exception {
        try (var f = new DatabaseCompatibilityTest.Fixture("dm")) {
            String table = f.table("ID INT PRIMARY KEY, NOTE VARCHAR(80)");
            f.insert(table, "1, '中文'");
            var history = org.mockito.Mockito.mock(com.example.dbadmin.repo.SqlHistoryRepository.class);
            var service = new SqlService(f.connections, f.properties, f.audit, f.dialects, history,
                    f.metadata, new SqlScriptSplitter(), new SqlStatementClassifier(),
                    f.guard, new SqlExecutionRegistry(), f.edits, new SqlExecutionMetrics());
            var plan = service.explain(1, "SELECT * FROM " + f.q(table) + " WHERE ID=1", "ci");
            assertThatThrownBy(() -> service.explain(1, "DELETE FROM " + f.q(table), "ci"))
                    .hasMessageContaining("只支持查询");
            assertThat(plan.columns()).extracting(ResultColumn::label).contains("OPERATION", "LEVEL_ID", "ROW_NUMS", "COST");
            assertThat(plan.rows()).isNotEmpty();
            assertThat(plan.truncated()).isFalse();
            assertThat(service.explainReadOnly(1, "SELECT * FROM " + f.q(table), f.schema, "ci",
                    new SqlQueryLimits(100, 100, 5000, 100000, 10000, 10)).rows()).isNotEmpty();
            var limited = f.dialect.explain(f.jdbc, "SELECT * FROM " + f.q(table), 1, 10);
            assertThat(limited.rows()).hasSize(1);
            assertThat(limited.truncated()).isTrue();
            assertThatThrownBy(() -> f.dialect.explain(f.jdbc, "SELECT MISSING FROM " + f.q(table), 100, 10))
                    .isInstanceOf(java.sql.SQLException.class);
            f.execute("INSERT INTO " + f.q(table) + " VALUES (2, '恢复')");
            assertThat(f.number("SELECT COUNT(*) FROM " + f.q(table))).isEqualTo(2);
            // 序列取值有副作用；估算计划不应消费 NEXTVAL。
            String sequence = "SEQ_" + java.util.UUID.randomUUID().toString().replace("-", "");
            try {
                f.execute("CREATE SEQUENCE " + sequence + " START WITH 100");
                f.dialect.explain(f.jdbc, "SELECT " + sequence + ".NEXTVAL FROM DUAL", 100, 10);
                f.jdbc.setReadOnly(true);
                try {
                    var readOnlyPlan = f.dialect.explain(f.jdbc, "SELECT " + sequence + ".NEXTVAL FROM DUAL", 100, 10);
                    assertThat(readOnlyPlan.columns()).extracting(ResultColumn::label).containsExactly("DM_PLAN");
                    assertThat(f.jdbc.isReadOnly()).isTrue();
                    assertThatThrownBy(() -> f.execute("INSERT INTO " + f.q(table) + " VALUES (3, '禁止写入')"))
                            .isInstanceOf(java.sql.SQLException.class);
                } finally { f.jdbc.setReadOnly(false); }
                assertThat(f.number("SELECT " + sequence + ".NEXTVAL FROM DUAL")).isEqualTo(100);
            } finally { f.execute("DROP SEQUENCE " + sequence); }
        }
    }

    @Test
    void commentsDefaultRemovalAndNullabilityRoundTrip() throws Exception {
        try (var f = new DatabaseCompatibilityTest.Fixture("dm")) {
            String table = f.table("ID INT PRIMARY KEY, NOTE VARCHAR(80) DEFAULT '原值' NOT NULL");
            var detail = f.metadata.detail(1, f.schema, table, true);
            var request = new TableDesignRequest(f.schema, table, List.of(
                    new ColumnDesign("ID", "INT", null, false, null, "ID", false),
                    new ColumnDesign("NOTE", "VARCHAR", 80, true, null, "NOTE", false, "中文说明")),
                    List.of(), List.of("ID"), detail.structureVersion(), f.schema + "." + table);
            f.metadata.executeDesign(1, request, "ci");
            f.execute("INSERT INTO " + f.q(table) + " (ID) VALUES (1)");
            assertThat(f.scalar("SELECT NOTE FROM " + f.q(table))).isNull();
            var changed = f.metadata.detail(1, f.schema, table, true);
            assertThat(changed.columns().get(1).nullable()).isTrue();
            assertThat(changed.columns().get(1).remarks()).isEqualTo("中文说明");
            var commentOnly = new TableDesignRequest(f.schema, table, changed.columns().stream()
                    .map(c -> new ColumnDesign(c.name(), c.type(), c.size(), c.nullable(), c.defaultValue(), c.name(), false,
                            c.name().equals("NOTE") ? "新说明" : null)).toList(), List.of(), changed.primaryKeys(),
                    changed.structureVersion(), f.schema + "." + table);
            assertThat(f.metadata.previewDesign(1, commentOnly).sql()).hasSize(1).allMatch(sql -> sql.startsWith("COMMENT ON"));
            f.metadata.executeDesign(1, commentOnly, "ci");
            assertThat(f.metadata.detail(1, f.schema, table, true).columns().get(1).remarks()).isEqualTo("新说明");
        }
    }
}
