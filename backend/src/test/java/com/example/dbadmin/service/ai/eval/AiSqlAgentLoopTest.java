package com.example.dbadmin.service.ai.eval;

import com.example.dbadmin.service.ai.llm.LlmAgentMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.example.dbadmin.service.ai.eval.ScriptedLlmClient.answer;
import static com.example.dbadmin.service.ai.eval.ScriptedLlmClient.toolCall;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Agent 编排的端到端回归，用剧本化的模型跑真实的工具、校验、会话和审计。
 *
 * <p>不需要 API Key，所以它是 CI 里唯一能守住这条循环的东西：`AiSchemaToolsTest` 只覆盖单个
 * 工具，而这里覆盖的是「工具结果回传 → 校验失败 → 自动修正 → 结构依据」这条完整链路。</p>
 */
class AiSqlAgentLoopTest {
    @Test
    void searchesThenDescribesThenFixesTheSqlThatFailedCompilation() throws Exception {
        ScriptedLlmClient model = new ScriptedLlmClient(List.of(
                toolCall("t1", "search_schema", "{\"query\":\"客户 手机号\",\"limit\":10}"),
                toolCall("t2", "describe_objects", "{\"names\":[\"T_CRM_0021\"]}"),
                // 第一版用了不存在的列，必须被目标库的编译校验挡住。
                answer("```sql\nSELECT CUST_NM, PHONE_NO FROM T_CRM_0021 WHERE ENABLED = TRUE\n```\n客户联系方式。"),
                answer("```sql\nSELECT CUST_NM, MOBILE FROM T_CRM_0021 WHERE ENABLED = TRUE\n```\n启用客户的名称与手机号。")));

        try (AiAgentHarness harness = new AiAgentHarness(model, AiEvalCases.glossary(AiAgentHarness.CONNECTION_ID))) {
            AiAgentHarness.Run run = harness.ask("查询所有启用状态的客户名称和手机号");

            assertThat(run.outcome()).isEqualTo("success");
            assertThat(run.answer()).contains("MOBILE").doesNotContain("PHONE_NO");
            assertThat(run.validated()).isTrue();
            assertThat(run.number("rounds")).isEqualTo(4);
            assertThat(run.number("tools")).isEqualTo(2);
            assertThat(run.number("objects")).isGreaterThan(0);

            // 校验失败那一轮的错误原文必须作为用户消息回到模型，否则它没有修正的依据。
            List<LlmAgentMessage> fourthRound = model.seen().get(3).messages();
            LlmAgentMessage retry = fourthRound.get(fourthRound.size() - 1);
            assertThat(retry.role()).isEqualTo(LlmAgentMessage.Role.USER);
            assertThat(retry.text()).contains("编译失败").contains("PHONE_NO");

            // 工具结果只在服务端流转：第二轮的历史里必须已经带上第一轮的搜索结果。
            assertThat(model.seen().get(1).messages())
                    .anyMatch(message -> message.role() == LlmAgentMessage.Role.TOOL_RESULTS);
        }
    }

    @Test
    void refusesToAnswerFromMemoryBeforeAnyStructureHasBeenRead() throws Exception {
        ScriptedLlmClient model = new ScriptedLlmClient(List.of(
                // 一上来就凭印象写表名，没查过任何结构。
                answer("```sql\nSELECT name, phone FROM customer\n```"),
                toolCall("t1", "search_schema", "{\"query\":\"客户\",\"limit\":10}"),
                toolCall("t2", "describe_objects", "{\"names\":[\"T_CRM_0021\"]}"),
                answer("```sql\nSELECT CUST_NM, MOBILE FROM T_CRM_0021\n```")));

        try (AiAgentHarness harness = new AiAgentHarness(model, List.of())) {
            AiAgentHarness.Run run = harness.ask("查询客户名称和手机号");

            assertThat(run.outcome()).isEqualTo("success");
            assertThat(run.answer()).contains("T_CRM_0021");
            List<LlmAgentMessage> secondRound = model.seen().get(1).messages();
            assertThat(secondRound.get(secondRound.size() - 1).text()).contains("search_schema");
        }
    }

    @Test
    void rejectsAWriteStatementAndAsksForASelectInstead() throws Exception {
        ScriptedLlmClient model = new ScriptedLlmClient(List.of(
                toolCall("t1", "search_schema", "{\"query\":\"客户\",\"limit\":10}"),
                toolCall("t2", "describe_objects", "{\"names\":[\"T_CRM_0021\"]}"),
                answer("```sql\nDELETE FROM T_CRM_0021 WHERE ENABLED = FALSE\n```"),
                answer("```sql\nSELECT CUST_NM FROM T_CRM_0021 WHERE ENABLED = FALSE\n```")));

        try (AiAgentHarness harness = new AiAgentHarness(model, List.of())) {
            AiAgentHarness.Run run = harness.ask("清理未启用的客户");

            assertThat(run.outcome()).isEqualTo("success");
            assertThat(run.answer()).doesNotContain("DELETE");
            List<LlmAgentMessage> fourthRound = model.seen().get(3).messages();
            assertThat(fourthRound.get(fourthRound.size() - 1).text()).contains("SELECT");
        }
    }

    @Test
    void writesAnAuditRecordWithTheRunStatisticsEvenWhenTheModelFails() throws Exception {
        ScriptedLlmClient model = new ScriptedLlmClient(List.of(
                toolCall("t1", "search_schema", "{\"query\":\"客户\",\"limit\":10}")));

        try (AiAgentHarness harness = new AiAgentHarness(model, List.of())) {
            // 剧本只有一轮，第二轮会抛异常 —— 模拟上游挂掉。
            AiAgentHarness.Run run = harness.ask("查询客户");

            assertThat(run.outcome()).isEqualTo("error");
            assertThat(run.stats()).containsKeys("conversation", "rounds", "tools", "inputTokens", "model");
            assertThat(run.number("tools")).isEqualTo(1);
        }
    }
}
