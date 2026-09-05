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
            // 汇总计数说不出它是搜了两次还是读了三次表；序列说得出，事后筛「哪类问题让它反复摸索」靠的是这个。
            assertThat(run.stats()).containsEntry("seq", "search_schema,describe_objects");

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

    /**
     * 搜空的检索词是词典缺口的现场采样：注释里推得出的词本来就搜得到，推不出的那半份
     * （用户嘴里的「会员」「买家」）只能从真实提问里采。此前这个信号只用来决定要不要重试，
     * 用完就丢了。
     */
    @Test
    void remembersTheWordsItSearchedForAndFoundNothingFor() throws Exception {
        ScriptedLlmClient model = new ScriptedLlmClient(List.of(
                toolCall("t1", "search_schema", "{\"queries\":[\"会员\",\"客户\"]}"),
                toolCall("t2", "describe_objects", "{\"names\":[\"T_CRM_0021\"]}"),
                answer("```sql\nSELECT CUST_NM FROM T_CRM_0021\n```")));

        try (AiAgentHarness harness = new AiAgentHarness(model, List.of())) {
            AiAgentHarness.Run run = harness.ask("查询会员名称");

            assertThat(run.outcome()).isEqualTo("success");
            // 「客户」搜得到，只有「会员」该被记下来 —— 记下搜得到的词等于把清单变成噪音。
            org.mockito.ArgumentCaptor<java.util.Collection<String>> recorded =
                    org.mockito.ArgumentCaptor.forClass(java.util.Collection.class);
            // 缺口记录排在审计之后，而审计才是 harness 等的那个信号 —— 这里要给它一点追上来的时间。
            org.mockito.Mockito.verify(harness.glossary(), org.mockito.Mockito.timeout(5_000))
                    .recordGaps(org.mockito.ArgumentMatchers.eq(AiAgentHarness.CONNECTION_ID), recorded.capture());
            assertThat(recorded.getValue()).containsExactly("会员");
        }
    }

    /** 请求跑挂了，搜空的词一样是缺口 —— 搜不到东西本来就是它答不出来的原因之一。 */
    @Test
    void remembersThoseWordsEvenWhenTheRequestNeverFinishes() throws Exception {
        ScriptedLlmClient model = new ScriptedLlmClient(List.of(
                toolCall("t1", "search_schema", "{\"queries\":[\"会员\"]}")));

        try (AiAgentHarness harness = new AiAgentHarness(model, List.of())) {
            // 剧本只有一轮，第二轮直接抛异常。
            assertThat(harness.ask("查询会员名称").outcome()).isEqualTo("error");

            org.mockito.Mockito.verify(harness.glossary(), org.mockito.Mockito.timeout(5_000))
                    .recordGaps(org.mockito.ArgumentMatchers.eq(AiAgentHarness.CONNECTION_ID),
                            org.mockito.ArgumentMatchers.argThat(terms -> terms.contains("会员")));
        }
    }

    /**
     * 需求有歧义时问一句，而不是挑一个猜。
     *
     * <p>此前系统提示里就写着「找不到时明确询问用户」，但没有出口形状：反问和答案一样是一段
     * 正文，用户得自己把问题读出来再手打回答。做成工具之后，问题和选项是结构化的，界面能画成
     * 按钮，点一下就接上下一轮。</p>
     */
    @Test
    void asksOneQuestionAndPicksTheAnswerUpInTheSameConversation() throws Exception {
        ScriptedLlmClient model = new ScriptedLlmClient(List.of(
                toolCall("t1", "search_schema", "{\"queries\":[\"订单\",\"金额\"]}"),
                toolCall("t2", "describe_objects", "{\"names\":[\"SALES_ORDER\"]}"),
                toolCall("t3", "ask_user", """
                        {"question":"按下单时间还是支付时间统计？","options":[
                          {"label":"下单时间","detail":"SALES_ORDER.ORDER_DATE"},
                          {"label":"支付时间","detail":"PAYMENT.PAID_AT"}]}"""),
                answer("```sql\nSELECT ORDER_DATE, SUM(TOTAL_AMOUNT) FROM SALES_ORDER GROUP BY ORDER_DATE\n```")));

        try (AiAgentHarness harness = new AiAgentHarness(model, List.of())) {
            AiAgentHarness.Run asked = harness.ask("统计每天的销售额");

            assertThat(asked.outcome()).isEqualTo("clarified");
            assertThat(asked.question()).isNotNull();
            assertThat(asked.question().question()).contains("下单时间");
            assertThat(asked.question().options()).hasSize(2);
            // 反问这一轮没有 SQL，证据面板不该声称校验通过。
            assertThat(asked.validated()).isFalse();

            AiAgentHarness.Run answered = harness.askIn(asked.stats().get("conversation"), "下单时间");
            assertThat(answered.outcome()).isEqualTo("success");
            assertThat(answered.answer()).contains("ORDER_DATE");

            // 关键：ask_user 也得有工具结果。少一条，下一轮的历史里就留下一个没有结果的工具
            // 调用，两家协议都会直接报错 —— 而那要到用户回答之后才炸。
            List<LlmAgentMessage> lastRound = model.seen().get(3).messages();
            assertThat(lastRound).anySatisfy(message -> {
                assertThat(message.role()).isEqualTo(LlmAgentMessage.Role.TOOL_RESULTS);
                assertThat(message.toolResults()).anyMatch(result -> result.content().contains("等待回答"));
            });
        }
    }

    /** 没有问题的反问会让对话停在一个空气泡上，所以打回去让模型重来。 */
    @Test
    void sendsAnEmptyClarificationBackToTheModel() throws Exception {
        ScriptedLlmClient model = new ScriptedLlmClient(List.of(
                toolCall("t1", "search_schema", "{\"queries\":[\"客户\"]}"),
                toolCall("t2", "describe_objects", "{\"names\":[\"T_CRM_0021\"]}"),
                toolCall("t3", "ask_user", "{\"options\":[{\"label\":\"A\"}]}"),
                answer("```sql\nSELECT CUST_NM FROM T_CRM_0021\n```")));

        try (AiAgentHarness harness = new AiAgentHarness(model, List.of())) {
            AiAgentHarness.Run run = harness.ask("查询客户名称");

            assertThat(run.outcome()).isEqualTo("success");
            assertThat(run.question()).isNull();
            List<LlmAgentMessage> afterAsk = model.seen().get(3).messages();
            assertThat(afterAsk).anySatisfy(message -> assertThat(message.toolResults())
                    .anyMatch(result -> result.error() && result.content().contains("question")));
        }
    }

    /**
     * 计划解读走 Agent 而不是单次问答：只把计划文本发过去，模型看不到这张表上真实存在哪些
     * 索引，只能泛泛地说「加个索引」，甚至建议一个已经有了的。
     */
    @Test
    void readsTheRealIndexesBeforeSuggestingOneAndHandsTheScriptToTheUser() throws Exception {
        ScriptedLlmClient model = new ScriptedLlmClient(List.of(
                toolCall("t1", "search_schema", "{\"queries\":[\"订单\"]}"),
                toolCall("t2", "describe_objects", "{\"names\":[\"SALES_ORDER\"]}"),
                answer("""
                        ```sql
                        CREATE INDEX IDX_SALES_ORDER_STATUS ON SALES_ORDER(ORDER_STATUS)
                        ```
                        计划在 SALES_ORDER 上做了全表扫描，而 ORDER_STATUS 上没有索引。""")));

        try (AiAgentHarness harness = new AiAgentHarness(model, List.of())) {
            AiAgentHarness.Run run = harness.askAboutPlan("这条为什么慢",
                    new com.example.dbadmin.dto.AiDtos.AiExecutionPlan(
                            "SELECT * FROM SALES_ORDER WHERE ORDER_STATUS = 'PAID'",
                            "Seq Scan on SALES_ORDER  (cost=0.00..18334.00 rows=1200000)",
                            "SALES_ORDER 全表扫描，预估 120 万行"));

            assertThat(run.outcome()).isEqualTo("success");
            assertThat(run.stats()).containsEntry("mode", "explain");
            assertThat(run.answer()).contains("CREATE INDEX");
            // 建索引语句没法编译校验（compileQuery 只接 SELECT），所以要明说它没校验过、不会自动执行。
            assertThat(run.validated()).isFalse();
            assertThat(run.stats()).containsEntry("seq", "search_schema,describe_objects");
        }
    }

    /** 「优化」的名义下给出删索引、改表结构或写数据，都要被打回。 */
    @Test
    void refusesToTurnPlanAdviceIntoADropOrAnAlter() throws Exception {
        ScriptedLlmClient model = new ScriptedLlmClient(List.of(
                toolCall("t1", "search_schema", "{\"queries\":[\"订单\"]}"),
                toolCall("t2", "describe_objects", "{\"names\":[\"SALES_ORDER\"]}"),
                answer("```sql\nDROP INDEX IDX_SALES_ORDER_CREATED\n```"),
                answer("""
                        ```sql
                        CREATE INDEX IDX_SALES_ORDER_STATUS ON SALES_ORDER(ORDER_STATUS)
                        ```
                        另外 IDX_SALES_ORDER_CREATED 看起来没被用到，是否删除请你自己判断。""")));

        try (AiAgentHarness harness = new AiAgentHarness(model, List.of())) {
            AiAgentHarness.Run run = harness.askAboutPlan("这条为什么慢",
                    new com.example.dbadmin.dto.AiDtos.AiExecutionPlan(
                            "SELECT * FROM SALES_ORDER", "Seq Scan on SALES_ORDER", null));

            assertThat(run.outcome()).isEqualTo("success");
            assertThat(run.answer()).doesNotContain("DROP INDEX\n```");
            List<LlmAgentMessage> retryRound = model.seen().get(3).messages();
            assertThat(retryRound.get(retryRound.size() - 1).text()).contains("CREATE INDEX");
        }
    }

    /**
     * 预算闸门只有在记账可靠时才有意义，而「跑挂了就不记」会让最容易失控的那类请求
     * （反复重试、每次都烧 token）刚好绕开额度。
     */
    @Test
    void booksTheTokensEvenWhenTheRunEndsInAnError() throws Exception {
        ScriptedLlmClient model = new ScriptedLlmClient(List.of(
                toolCall("t1", "search_schema", "{\"queries\":[\"客户\"]}")));

        try (AiAgentHarness harness = new AiAgentHarness(model, List.of())) {
            // 剧本只有一轮，第二轮抛异常 —— 但第一轮的 token 已经花出去了。
            assertThat(harness.ask("查询客户").outcome()).isEqualTo("error");

            org.mockito.Mockito.verify(harness.settings(), org.mockito.Mockito.timeout(5_000))
                    .recordUsage(org.mockito.ArgumentMatchers.eq("eval"),
                            org.mockito.ArgumentMatchers.eq("eval-model"),
                            org.mockito.ArgumentMatchers.eq(100L),
                            org.mockito.ArgumentMatchers.eq(20L),
                            org.mockito.ArgumentMatchers.eq(0L));
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
