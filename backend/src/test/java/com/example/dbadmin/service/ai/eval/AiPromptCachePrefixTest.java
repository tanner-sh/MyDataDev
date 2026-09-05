package com.example.dbadmin.service.ai.eval;

import com.example.dbadmin.service.ai.llm.LlmAgentMessage;
import com.example.dbadmin.service.ai.llm.LlmAgentRequest;
import com.example.dbadmin.service.ai.llm.LlmToolDefinition;
import org.junit.jupiter.api.Test;

import java.time.Year;
import java.util.List;

import static com.example.dbadmin.service.ai.eval.ScriptedLlmClient.answer;
import static com.example.dbadmin.service.ai.eval.ScriptedLlmClient.toolCall;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * prompt cache 的前缀纪律。
 *
 * <p>评测报告里的「缓存读 token」是这条纪律唯一的现有指标，可它只有跑真模型才拿得到 ——
 * 等到有人在系统提示里塞进一个时间戳、或者在历史中间插一条消息，要到下一次手动评测才会
 * 发现，而那时账单已经按全价出过好几轮了。这里用剧本模型把同一件事变成 CI 能跑的断言：
 * 前缀只准在尾部增长，一个字节都不许改。</p>
 *
 * <p>断言的是发给 provider 的请求本身，而不是某个实现细节 —— 两家协议对缓存断点的处理不同，
 * 但「稳定前缀 + 尾部追加」是两家共同的前提。</p>
 */
class AiPromptCachePrefixTest {
    @Test
    void growsTheContextOnlyAtTheTailAcrossRoundsAndFollowUpTurns() throws Exception {
        ScriptedLlmClient model = new ScriptedLlmClient(List.of(
                toolCall("t1", "search_schema", "{\"queries\":[\"客户\"]}"),
                toolCall("t2", "describe_objects", "{\"names\":[\"T_CRM_0021\"]}"),
                // 故意让第一版编译不过：自动修正那一轮同样只能往尾部追加。
                answer("```sql\nSELECT CUST_NM, PHONE_NO FROM T_CRM_0021\n```"),
                answer("```sql\nSELECT CUST_NM, MOBILE FROM T_CRM_0021\n```"),
                answer("```sql\nSELECT CUST_NM, MOBILE FROM T_CRM_0021 WHERE ENABLED = TRUE\n```")));

        try (AiAgentHarness harness = new AiAgentHarness(model, List.of())) {
            AiAgentHarness.Run first = harness.ask("查询客户名称和手机号");
            AiAgentHarness.Run second = harness.askIn(first.stats().get("conversation"), "只要启用的");

            assertThat(first.outcome()).isEqualTo("success");
            assertThat(second.outcome()).isEqualTo("success");

            List<LlmAgentRequest> requests = model.seen();
            assertThat(requests).hasSize(5);

            String systemPrompt = requests.get(0).systemPrompt();
            List<LlmToolDefinition> tools = requests.get(0).tools();
            List<LlmAgentMessage> previous = requests.get(0).messages();
            for (int index = 1; index < requests.size(); index++) {
                LlmAgentRequest request = requests.get(index);
                // 系统提示和工具定义排在消息之前，它们一变，整段前缀就全部失效。
                assertThat(request.systemPrompt()).as("第 %d 轮的系统提示", index + 1).isEqualTo(systemPrompt);
                assertThat(request.tools()).as("第 %d 轮的工具定义", index + 1).isEqualTo(tools);

                List<LlmAgentMessage> messages = request.messages();
                assertThat(messages.size()).as("第 %d 轮的消息条数", index + 1).isGreaterThan(previous.size());
                assertThat(messages.subList(0, previous.size()))
                        .as("第 %d 轮把已有消息改写了，缓存前缀会整段失效", index + 1)
                        .isEqualTo(previous);
                previous = messages;
            }
        }
    }

    /**
     * 系统提示里不放任何每次都变的东西。写死一条「不含当前年份」的断言是因为最容易犯的那个
     * 错误就是加时间：「今天是 2026-09-05」这类句子看着无害，但它让每一天、甚至每一秒的前缀
     * 都对不上，缓存命中率直接归零。
     */
    @Test
    void keepsTheSystemPromptIdenticalBetweenIndependentRequests() throws Exception {
        ScriptedLlmClient model = new ScriptedLlmClient(List.of(
                toolCall("t1", "search_schema", "{\"queries\":[\"客户\"]}"),
                toolCall("t2", "describe_objects", "{\"names\":[\"T_CRM_0021\"]}"),
                answer("```sql\nSELECT CUST_NM FROM T_CRM_0021\n```"),
                toolCall("t3", "search_schema", "{\"queries\":[\"客户\"]}"),
                toolCall("t4", "describe_objects", "{\"names\":[\"T_CRM_0021\"]}"),
                answer("```sql\nSELECT MOBILE FROM T_CRM_0021\n```")));

        try (AiAgentHarness harness = new AiAgentHarness(model, List.of())) {
            harness.ask("查询客户名称");
            harness.ask("查询客户手机号");

            String first = model.seen().get(0).systemPrompt();
            assertThat(model.seen()).extracting(LlmAgentRequest::systemPrompt).containsOnly(first);
            assertThat(first).doesNotContain(String.valueOf(Year.now().getValue()));
        }
    }
}
