package com.example.dbadmin.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SqlCommandOutputTest {
    @Test
    void summarisesAStatementByItsFirstRealLines() {
        assertThat(SqlCommandOutput.summary("-- 第 2 条\nINSERT INTO t\n  VALUES (1)")).isEqualTo("INSERT INTO t VALUES (1)");
        assertThat(SqlCommandOutput.summary("SELECT '" + "x".repeat(300) + "'")).hasSize(200).endsWith("…");
    }

    @Test
    void describesOutcomesTheWaySqlPlusDoes() {
        assertThat(SqlCommandOutput.updateText("insert into t values (1)", 1)).isEqualTo("已插入 1 行。");
        assertThat(SqlCommandOutput.updateText("UPDATE t SET a = 1", 3)).isEqualTo("已更新 3 行。");
        assertThat(SqlCommandOutput.updateText("-- c\nDELETE FROM t", 0)).isEqualTo("已删除 0 行。");
        assertThat(SqlCommandOutput.updateText("CREATE TABLE x (a INT)", 0)).isEqualTo("已执行（CREATE）。");
        assertThat(SqlCommandOutput.updateText("BEGIN NULL; END;", 0)).isEqualTo("PL/SQL 过程已成功完成。");
        assertThat(SqlCommandOutput.updateText("COMMIT", 0)).isEqualTo("已提交。");
        assertThat(SqlCommandOutput.updateText("SET ROLE ALL", 0)).isEqualTo("执行成功。");
    }

    @Test
    void alignsColumnsCountingWideCharactersTwice() {
        String table = SqlCommandOutput.table(List.of("ID", "NAME"), List.of(List.of("1", "张三"), List.of("22", "Bob")));
        assertThat(table).isEqualTo("ID NAME\n-- ----\n1  张三\n22 Bob");
        assertThat(SqlCommandOutput.table(List.of("V"), List.of(List.of("x".repeat(60))))).contains("x".repeat(39) + "…");
    }

    @Test
    void saysHowManyRowsThereWereAndHowManyAreShown() {
        assertThat(SqlCommandOutput.rowCountText(0, 0, false)).isEqualTo("未选定行。");
        assertThat(SqlCommandOutput.rowCountText(3, 3, false)).isEqualTo("已选择 共 3 行。");
        assertThat(SqlCommandOutput.rowCountText(50, 20, false)).isEqualTo("已选择 共 50 行，仅显示前 20 行。");
        assertThat(SqlCommandOutput.rowCountText(100000, 20, true)).isEqualTo("已选择 至少 100000 行，仅显示前 20 行。");
    }
}
