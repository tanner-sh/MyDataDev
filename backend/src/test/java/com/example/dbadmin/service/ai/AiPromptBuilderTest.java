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

    @Test
    void interpretPromptWarnsThatOnlyAPreviewWasSeen() {
        String prompt = AiPromptBuilder.interpret("SELECT * FROM orders", "id\tstatus\n1\tPAID", null);

        assertThat(prompt).contains("只是前几行");
        assertThat(prompt).contains("1\tPAID");
    }

    /** 图表候选由前端算出来，模型只能在真实候选里挑，不能推荐一个画不出来的图。 */
    @Test
    void interpretPromptPinsChartAdviceToTheRealCandidates() {
        String prompt = AiPromptBuilder.interpret("SELECT 1", "preview", "柱状图：按 status 分组统计 amount");

        assertThat(prompt).contains("不要推荐候选之外的图表类型");
        assertThat(prompt).contains("按 status 分组统计 amount");
    }

    @Test
    void interpretPromptOmitsTheChartSectionWhenNothingIsChartable() {
        assertThat(AiPromptBuilder.interpret("SELECT 1", "preview", "  ")).doesNotContain("图表候选");
    }

    @Test
    void documentPromptForbidsInventingBusinessMeaning() {
        String prompt = AiPromptBuilder.document("shop", "orders、order_item");

        assertThat(prompt).contains("用途不明");
        assertThat(prompt).contains("不要编造业务含义");
        assertThat(prompt).contains("orders、order_item");
    }

    @Test
    void documentPromptFallsBackToADefaultNamespaceLabel() {
        assertThat(AiPromptBuilder.document(null, "orders")).contains("（默认）");
    }

    /** 脚本由方言生成，模型改写它没有意义 —— 用户也不该信一个改过的迁移脚本。 */
    @Test
    void reviewScriptPromptTellsTheModelNotToRewriteTheScript() {
        String prompt = AiPromptBuilder.reviewScript("ALTER TABLE orders DROP COLUMN note;");

        assertThat(prompt).contains("不要改写脚本");
        assertThat(prompt).contains("ALTER TABLE orders DROP COLUMN note;");
    }
}
