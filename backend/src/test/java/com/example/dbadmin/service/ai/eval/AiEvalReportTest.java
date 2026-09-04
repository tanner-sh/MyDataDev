package com.example.dbadmin.service.ai.eval;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.example.dbadmin.service.ai.eval.ScriptedLlmClient.answer;
import static com.example.dbadmin.service.ai.eval.ScriptedLlmClient.toolCall;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 用剧本化的模型把评测流程整个走一遍：装配 → 提问 → 打分 → 出报告。
 *
 * <p>存在的理由很实际：一次真评测要花掉几十次模型调用，跑到最后一步才发现报告渲染抛异常，
 * 那几十次就白花了。这里也顺带保证 {@link AiSqlAgentEvalTest} 用到的那条链路一直是通的。</p>
 */
class AiEvalReportTest {
    @Test
    void runsTheWholeEvalPipelineAndRendersBothOutcomes() throws Exception {
        AiEvalCase passing = find("crm-lookup");
        AiEvalCase failing = find("category-count");

        ScriptedLlmClient model = new ScriptedLlmClient(List.of(
                toolCall("t1", "search_schema", "{\"query\":\"客户\",\"limit\":10}"),
                toolCall("t2", "describe_objects", "{\"names\":[\"T_CRM_0021\"]}"),
                answer("```sql\nSELECT CUST_NM, MOBILE FROM T_CRM_0021 WHERE ENABLED = TRUE\n```"),
                toolCall("t3", "search_schema", "{\"query\":\"商品\",\"limit\":10}"),
                toolCall("t4", "describe_objects", "{\"names\":[\"PRODUCT\"]}"),
                // 只查了商品表，漏掉类目表 —— 编译能过，但答案是错的，评测必须判它未通过。
                answer("```sql\nSELECT CATEGORY_ID, COUNT(*) FROM PRODUCT GROUP BY CATEGORY_ID\n```")));

        List<AiEvalReport.Row> rows = new ArrayList<>();
        try (AiAgentHarness harness = new AiAgentHarness(
                model, AiEvalCases.glossary(AiAgentHarness.CONNECTION_ID), "scripted")) {
            for (AiEvalCase evalCase : List.of(passing, failing)) {
                AiAgentHarness.Run run = harness.ask(evalCase.question());
                rows.add(new AiEvalReport.Row(evalCase, run,
                        AiEvalScoring.score(evalCase, run.answer(), run.validated())));
            }
        }

        assertThat(rows.get(0).score().passed()).isTrue();
        assertThat(rows.get(1).score().passed()).isFalse();
        assertThat(rows.get(1).score().missingTables()).containsExactly("PRODUCT_CATEGORY");

        String report = AiEvalReport.render("scripted", rows);

        assertThat(report)
                .contains("模型：`scripted`")
                .contains("用例：2 条，通过 1（50%）")
                .contains("| crm-lookup | 通过 |")
                .contains("| category-count | **未通过** | 漏掉 PRODUCT_CATEGORY")
                .contains("### category-count — 漏掉 PRODUCT_CATEGORY")
                .contains("SELECT CATEGORY_ID, COUNT(*) FROM PRODUCT");
    }

    @Test
    void saysSoPlainlyWhenEverythingPasses() {
        assertThat(AiEvalReport.render("scripted", List.of())).contains("全部通过。");
    }

    private static AiEvalCase find(String id) {
        return AiEvalCases.all().stream()
                .filter(item -> item.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("用例集里没有 " + id));
    }
}
