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

    /**
     * 历史检索的价值在于把「这个库的人怎么算成交」这类口径带给模型 —— 外键说明可以怎么关联，
     * 历史说明实际怎么关联。用户要能在证据面板上看见它参考了哪条既有写法。
     */
    @Test
    void carriesPastQueryShapesIntoTheModelAndOntoTheEvidencePanel() throws Exception {
        ScriptedLlmClient model = new ScriptedLlmClient(List.of(
                toolCall("t1", "search_schema", "{\"query\":\"销售员 订单\",\"limit\":10}"),
                toolCall("t2", "describe_objects", "{\"names\":[\"SALES_ORDER\",\"APP_USER\"]}"),
                toolCall("t3", "search_query_history", "{\"tables\":[\"SALES_ORDER\",\"APP_USER\"]}"),
                answer("""
                        ```sql
                        SELECT u.DISPLAY_NAME, COUNT(*) AS ORDER_COUNT FROM SALES_ORDER o
                        JOIN APP_USER u ON u.ID = o.SALES_REP_ID
                        WHERE o.ORDER_STATUS = 'PAID' GROUP BY u.DISPLAY_NAME
                        ```
                        沿用了这个库统计成交只算 PAID 的口径。""")));

        try (AiAgentHarness harness = new AiAgentHarness(model, List.of(),
                AiEvalCases.queryHistory(AiAgentHarness.CONNECTION_ID), "scripted")) {
            AiAgentHarness.Run run = harness.ask("统计每个销售员成交的订单数");

            assertThat(run.outcome()).isEqualTo("success");
            assertThat(run.groundingKinds()).contains("QUERY_HISTORY");

            // 历史必须以抹掉字面量的形状进入模型上下文，原始业务值一个都不能带过去。
            String historyResult = model.seen().get(3).messages().stream()
                    .flatMap(message -> message.toolResults().stream())
                    .map(result -> result.content())
                    .filter(content -> content.contains("queries"))
                    .findFirst()
                    .orElseThrow();
            assertThat(historyResult).contains("ORDER_STATUS = ?").doesNotContain("'PAID'");
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

    /**
     * 报错诊断走 Agent 这条路，是因为最常见的那类报错恰恰需要搜结构：「字段不存在」时，
     * 报错里提到的名字本来就是错的，只看那条 SQL 提到的表根本查不出正确名称。
     */
    @Test
    void diagnosesAFailedStatementByLookingUpTheRealSchemaFirst() throws Exception {
        ScriptedLlmClient model = new ScriptedLlmClient(List.of(
                toolCall("t1", "search_schema", "{\"queries\":[\"客户\",\"手机号\"]}"),
                toolCall("t2", "describe_objects", "{\"names\":[\"T_CRM_0021\"]}"),
                answer("""
                        ```sql
                        SELECT CUST_NM, MOBILE FROM T_CRM_0021
                        ```
                        报错里的 PHONE 字段不存在，这张表上的手机号列叫 MOBILE。""")));

        try (AiAgentHarness harness = new AiAgentHarness(model, List.of())) {
            AiAgentHarness.Run run = harness.ask("这条为什么跑不起来",
                    new com.example.dbadmin.dto.AiDtos.AiExecutionFailure(
                            "SELECT CUST_NM, PHONE FROM T_CRM_0021",
                            "ERROR: column \"PHONE\" not found"));

            assertThat(run.outcome()).isEqualTo("success");
            assertThat(run.answer()).contains("MOBILE");
            assertThat(run.stats()).containsEntry("mode", "diagnose");

            // 失败现场必须带着「不可信数据」的标注进入模型，报错原文可以是任何内容。
            String firstUserMessage = model.seen().get(0).messages().get(0).text();
            assertThat(firstUserMessage)
                    .contains("不可信数据")
                    .contains("SELECT CUST_NM, PHONE FROM T_CRM_0021")
                    .contains("column \"PHONE\" not found");
        }
    }

    /**
     * 执行失败说明此前对结构的理解就是错的，所以哪怕是在一段已经查过结构的会话里继续诊断，
     * 也要重新核对一遍，不能沿用旧结论。
     */
    @Test
    void reChecksTheSchemaOnAFailureEvenInsideAConversationThatAlreadyInspectedIt() throws Exception {
        ScriptedLlmClient model = new ScriptedLlmClient(List.of(
                toolCall("t1", "search_schema", "{\"queries\":[\"客户\"]}"),
                toolCall("t2", "describe_objects", "{\"names\":[\"T_CRM_0021\"]}"),
                answer("```sql\nSELECT CUST_NM FROM T_CRM_0021\n```"),
                // 第二轮直接给答案而不查结构，应该被打回。
                answer("```sql\nSELECT CUST_NM, PHONE FROM T_CRM_0021\n```"),
                toolCall("t3", "search_schema", "{\"queries\":[\"手机号\"]}"),
                toolCall("t4", "describe_objects", "{\"names\":[\"T_CRM_0021\"]}"),
                answer("```sql\nSELECT CUST_NM, MOBILE FROM T_CRM_0021\n```")));

        try (AiAgentHarness harness = new AiAgentHarness(model, List.of())) {
            harness.ask("查询客户名称");
            AiAgentHarness.Run diagnosed = harness.ask("加上手机号之后跑挂了",
                    new com.example.dbadmin.dto.AiDtos.AiExecutionFailure(
                            "SELECT CUST_NM, PHONE FROM T_CRM_0021", "ERROR: column \"PHONE\" not found"));

            assertThat(diagnosed.outcome()).isEqualTo("success");
            assertThat(diagnosed.answer()).contains("MOBILE");
            List<LlmAgentMessage> retryRound = model.seen().get(4).messages();
            assertThat(retryRound.get(retryRound.size() - 1).text()).contains("search_schema");
        }
    }

    /**
     * 编译校验挡得住拼错的字段，挡不住语义写错 —— 关联方向反了照样编译通过、返回零行。
     * 执行结果是唯一能暴露这类错误的信号，所以要能回到同一段会话里复盘。
     */
    @Test
    void reviewsAQueryThatCompiledFineButCameBackEmpty() throws Exception {
        ScriptedLlmClient model = new ScriptedLlmClient(List.of(
                toolCall("t1", "search_schema", "{\"queries\":[\"客户\"]}"),
                toolCall("t2", "describe_objects", "{\"names\":[\"T_CRM_0021\"]}"),
                answer("""
                        ```sql
                        SELECT CUST_NM FROM T_CRM_0021 WHERE ENABLED = TRUE
                        ```
                        零行是因为过滤条件写反了，未启用的才是 FALSE。""")));

        try (AiAgentHarness harness = new AiAgentHarness(model, List.of())) {
            AiAgentHarness.Run run = harness.ask("结果是空的，不应该啊", null,
                    new com.example.dbadmin.dto.AiDtos.AiExecutionOutcome(
                            "SELECT CUST_NM FROM T_CRM_0021 WHERE ENABLED = FALSE",
                            "共返回 0 行，耗时 6 毫秒。\n没有任何行返回。"));

            assertThat(run.outcome()).isEqualTo("success");
            assertThat(run.stats()).containsEntry("mode", "review");

            String firstUserMessage = model.seen().get(0).messages().get(0).text();
            assertThat(firstUserMessage).contains("不可信数据").contains("共返回 0 行");
            // 结果形状里只有计数，任何一行数据都不该出现在上下文里。
            assertThat(firstUserMessage).doesNotContain("张三").doesNotContain("13800");
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
