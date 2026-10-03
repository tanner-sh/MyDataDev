package com.example.dbadmin.service;

import com.example.dbadmin.dto.ApiDtos.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.*;

class DatabaseSqlServerCompatibilityTest {
    @Test
    void defaultsCommentsRenamesAndDropsExecuteWithLiveMetadata() throws Exception {
        try (var f = new DatabaseCompatibilityTest.Fixture("sqlserver")) {
            String table = f.table("id INT IDENTITY(1,1) PRIMARY KEY, amount DECIMAL(18,2) DEFAULT 1 NOT NULL, note NVARCHAR(40) NULL, total AS (id * 2)");
            var detail = f.metadata.detail(1, f.schema, table, true);
            assertThat(detail.columns().get(0).identity()).isTrue();
            assertThat(detail.columns().get(1).type()).isEqualTo("decimal(18,2)");
            assertThat(detail.columns().get(3).generated()).isTrue();
            assertThat(f.metadata.previewDesign(1, design(detail, c -> c)).sql()).isEmpty();
            f.metadata.executeDesign(1, design(detail, c -> c.name().equals("amount")
                    ? new ColumnDesign("amount", "DECIMAL(20,3)", null, false, "2.5", "amount", false, "金额说明", false)
                    : c), "ci");
            f.execute("INSERT INTO " + f.q(table) + " (note) VALUES (N'测试')");
            assertThat(f.scalar("SELECT amount FROM " + f.q(table)).toString()).isEqualTo("2.500");
            detail = f.metadata.detail(1, f.schema, table, true);
            assertThat(detail.columns().get(1).remarks()).isEqualTo("金额说明");
            f.metadata.executeDesign(1, design(detail, c -> c.name().equals("amount")
                    ? new ColumnDesign("price", c.type(), c.size(), c.nullable(), "3", c.originalName(), false, "", c.identity()) : c), "ci");
            detail = f.metadata.detail(1, f.schema, table, true);
            assertThat(detail.columns().get(1).name()).isEqualTo("price");
            assertThat(detail.columns().get(1).remarks()).isEmpty();
            // 删除没有依赖的默认值字段时，必须先移除自动命名的默认约束。
            f.execute("ALTER TABLE " + f.q(table) + " ADD disposable INT DEFAULT 7");
            detail = f.metadata.detail(1, f.schema, table, true);
            f.metadata.executeDesign(1, design(detail, c -> c.name().equals("disposable")
                    ? new ColumnDesign(c.name(), c.type(), c.size(), c.nullable(), c.defaultValue(), c.originalName(), true) : c), "ci");
            assertThat(f.metadata.detail(1, f.schema, table, true).columns()).extracting(ColumnInfo::name).doesNotContain("disposable");
        }
    }

    @Test
    void createsIdentityAndCommentsAndPreservesAdvancedIndexes() throws Exception {
        try (var f = new DatabaseCompatibilityTest.Fixture("sqlserver")) {
            String table = f.reserveTable();
            var columns = List.of(new ColumnDesign("id", "INT", null, false, null, null, false, "编号", true),
                    new ColumnDesign("name", "NVARCHAR", 40, true, null, null, false, "名字"));
            f.metadata.executeTableLifecycle(1, new TableLifecycleRequest("CREATE", f.schema, table, null,
                    columns, List.of(), List.of("id"), null, f.schema + "." + table), "ci", null);
            f.execute("INSERT INTO " + f.q(table) + " (name) VALUES (N'名字')");
            assertThat(f.number("SELECT id FROM " + f.q(table))).isEqualTo(1);
            f.execute("CREATE INDEX ix_filtered ON " + f.q(table) + " (name) WHERE name IS NOT NULL");
            var detail = f.metadata.detail(1, f.schema, table, true);
            assertThat(detail.columns().get(0).remarks()).isEqualTo("编号");
            var base = design(detail, c -> c);
            var keep = new TableDesignRequest(f.schema, table, base.columns(),
                    List.of(new IndexDesign("ix_filtered", List.of("name"), false, "ix_filtered", false)),
                    detail.primaryKeys(), detail.structureVersion(), f.schema + "." + table);
            assertThat(f.metadata.previewDesign(1, keep).sql()).isEmpty();
            assertThatThrownBy(() -> f.metadata.previewDesign(1, base)).hasMessageContaining("高级属性");
        }
    }

    @Test
    void estimatedPlansNeverExecuteQueryAndResetSessionAfterFailure() throws Exception {
        try (var f = new DatabaseCompatibilityTest.Fixture("sqlserver")) {
            String table = f.table("id INT PRIMARY KEY, note NVARCHAR(30)");
            f.insert(table, "1, N'测试'");
            var plan = f.dialect.explain(f.jdbc, "SELECT * FROM " + f.q(table) + " WHERE id=1", 100, 10);
            assertThat(plan.columns()).extracting(ResultColumn::label).contains("PhysicalOp", "EstimateRows");
            assertThat(plan.rows()).isNotEmpty();
            assertThat(f.number("SELECT COUNT(*) FROM " + f.q(table))).isEqualTo(1);
            // 1/0 正常执行会报错；估算计划不运行表达式。
            assertThat(f.dialect.explain(f.jdbc, "SELECT 1/0", 100, 10).rows()).isNotEmpty();
            assertThatThrownBy(() -> f.dialect.explain(f.jdbc, "SELECT absent FROM " + f.q(table), 100, 10)).isInstanceOf(java.sql.SQLException.class);
            f.execute("INSERT INTO " + f.q(table) + " VALUES (2, N'恢复')");
            assertThat(f.number("SELECT COUNT(*) FROM " + f.q(table))).isEqualTo(2);
        }
    }

    @Test
    void adjustsPrimaryKeyIndexesAndRemovesDefaultConstraint() throws Exception {
        try (var f = new DatabaseCompatibilityTest.Fixture("sqlserver")) {
            String table = f.table("id INT NOT NULL PRIMARY KEY, code INT NOT NULL, note NVARCHAR(40) DEFAULT N'原值'");
            f.execute("CREATE INDEX ix_note ON " + f.q(table) + " (note)");
            var detail = f.metadata.detail(1, f.schema, table, true);
            var columns = design(detail, c -> c.name().equals("note")
                    ? new ColumnDesign(c.name(), c.type(), c.size(), c.nullable(), null, c.originalName(), false) : c).columns();
            var request = new TableDesignRequest(f.schema, table, columns,
                    List.of(new IndexDesign("ix_code", List.of("code"), true, "ix_note", false)),
                    List.of("code"), detail.structureVersion(), f.schema + "." + table);
            f.metadata.executeDesign(1, request, "ci");
            var changed = f.metadata.detail(1, f.schema, table, true);
            assertThat(changed.primaryKeys()).containsExactly("code");
            assertThat(changed.indexes()).extracting(IndexInfo::name).contains("ix_code").doesNotContain("ix_note");
            f.execute("INSERT INTO " + f.q(table) + " (id, code) VALUES (1, 100)");
            assertThat(f.scalar("SELECT note FROM " + f.q(table))).isNull();
        }
    }

    private TableDesignRequest design(ObjectDetail detail, UnaryOperator<ColumnDesign> edit) {
        var columns = detail.columns().stream().map(c -> edit.apply(new ColumnDesign(c.name(), c.type(), c.size(), c.nullable(),
                c.defaultValue(), c.name(), false, c.remarks(), c.identity()))).toList();
        return new TableDesignRequest(detail.schemaName(), detail.name(), columns, List.of(), detail.primaryKeys(),
                detail.structureVersion(), detail.schemaName() + "." + detail.name());
    }
}
