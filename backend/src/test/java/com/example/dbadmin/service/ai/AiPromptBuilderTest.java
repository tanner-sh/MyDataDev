package com.example.dbadmin.service.ai;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AiPromptBuilderTest {
    private static final SchemaContext CONTEXT = new SchemaContext("PostgreSQL", "public", List.of(
            new SchemaContext.Table("public", "orders",
                    List.of(new SchemaContext.Column("id", "BIGINT", false, null)),
                    List.of("id"), List.of(), List.of())
    ), false);

    /**
     * 系统提示是缓存前缀：它只能由结构和方言决定。混进本次 SQL 或时间戳，同一条连接的
     * 连续提问就再也命中不了缓存。
     */
    @Test
    void systemPromptDependsOnlyOnSchemaAndDialect() {
        String first = AiPromptBuilder.system(CONTEXT, "PostgreSQL");
        String second = AiPromptBuilder.system(CONTEXT, "PostgreSQL");

        assertThat(first).isEqualTo(second);
        assertThat(first).contains("表 public.orders").contains("目标数据库：PostgreSQL");
    }

    @Test
    void systemPromptSaysWhenThereIsNoSchemaAtAll() {
        assertThat(AiPromptBuilder.system(SchemaContext.empty("MySQL", null), "MySQL"))
                .contains("这次没有可用的表结构");
    }

    @Test
    void systemPromptForbidsInventingTablesAndClaimingExecution() {
        String prompt = AiPromptBuilder.system(CONTEXT, "PostgreSQL");

        assertThat(prompt).contains("绝不臆造");
        assertThat(prompt).contains("你没有执行权限");
    }

    @Test
    void diagnosePromptCarriesTheStatementAndTheDriverError() {
        String prompt = AiPromptBuilder.diagnose("SELECT * FROM orders", "ERROR: relation \"order\" does not exist");

        assertThat(prompt).contains("SELECT * FROM orders");
        assertThat(prompt).contains("relation \"order\" does not exist");
    }

    @Test
    void diagnosePromptClampsAnOversizedStatement() {
        String prompt = AiPromptBuilder.diagnose("x".repeat(AiPromptBuilder.MAX_SQL_CHARS + 500), "boom");

        assertThat(prompt).contains("（已截断）");
        assertThat(prompt.length()).isLessThan(AiPromptBuilder.MAX_SQL_CHARS + 1_000);
    }

    @Test
    void generatePromptPinsReadonlyConnectionsToSelect() {
        assertThat(AiPromptBuilder.generate("统计上周订单数", true)).contains("只能给 SELECT 语句");
        assertThat(AiPromptBuilder.generate("把过期订单标记为关闭", false)).contains("会修改数据");
    }

    /** 确定性规则已经得出的结论要一并发过去，模型只在其上解释，而不是重新判断一遍。 */
    @Test
    void explainPromptForwardsTheDeterministicFindings() {
        String prompt = AiPromptBuilder.explain("SELECT 1", "Seq Scan on orders", "orders 全表扫描，预估 120 万行");

        assertThat(prompt).contains("Seq Scan on orders");
        assertThat(prompt).contains("不必重复判断");
        assertThat(prompt).contains("预估 120 万行");
    }

    @Test
    void explainPromptOmitsTheFindingsSectionWhenThereAreNone() {
        assertThat(AiPromptBuilder.explain("SELECT 1", "Seq Scan", "  ")).doesNotContain("不必重复判断");
    }
}
