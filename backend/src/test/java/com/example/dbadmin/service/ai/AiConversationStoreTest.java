package com.example.dbadmin.service.ai;

import com.example.dbadmin.api.ApiProblemException;
import com.example.dbadmin.config.AppProperties;
import com.example.dbadmin.dto.AiDtos.AiGroundingReport;
import com.example.dbadmin.service.ai.llm.LlmAgentMessage;
import com.example.dbadmin.service.ai.llm.LlmToolResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiConversationStoreTest {
    @Test
    void keepsTrustedHistoryOnTheServerAndRestoresVisibleMessages() {
        AiConversationStore store = new AiConversationStore(new AppProperties());
        AiConversationStore.Turn turn = store.begin(null, "user:7", 3, "PUBLIC", 1,
                "查询用户", "SELECT 1");
        List<LlmAgentMessage> internal = new ArrayList<>(turn.messages());
        internal.add(LlmAgentMessage.assistant("```sql\nSELECT id FROM app_user\n```", List.of()));
        store.complete(turn, internal, "```sql\nSELECT id FROM app_user\n```",
                new AiGroundingReport(true, "校验通过", List.of()), List.of());

        var restored = store.get(turn.id(), "user:7", 3, "public");

        assertThat(restored.messages()).extracting(message -> message.role())
                .containsExactly("USER", "ASSISTANT");
        assertThat(restored.messages().get(1).grounding().validated()).isTrue();
        AiConversationStore.Turn followUp = store.begin(turn.id(), "user:7", 3, "PUBLIC", 1,
                "只要启用用户", null);
        assertThat(followUp.requireInspection()).isFalse();
        assertThat(followUp.messages()).hasSize(3);
        store.fail(followUp);
    }

    @Test
    void isolatesOwnersScopesAndConcurrentTurns() {
        AiConversationStore store = new AiConversationStore(new AppProperties());
        AiConversationStore.Turn turn = store.begin(null, "user:7", 3, "PUBLIC", 1, "问题", null);

        assertThatThrownBy(() -> store.get(turn.id(), "user:8", 3, "PUBLIC"))
                .isInstanceOf(ApiProblemException.class).hasMessageContaining("其他用户");
        assertThatThrownBy(() -> store.get(turn.id(), "user:7", 4, "PUBLIC"))
                .isInstanceOf(ApiProblemException.class).hasMessageContaining("不匹配");
        assertThatThrownBy(() -> store.begin(turn.id(), "user:7", 3, "PUBLIC", 1, "并发问题", null))
                .isInstanceOf(ApiProblemException.class).hasMessageContaining("正在处理");
        store.fail(turn);
    }

    /**
     * 会话里存的是工具结果原文（结构 JSON、DDL），一条就可能有几百 KB。历史必须按字符数裁，
     * 只按消息条数裁等于让缓存占用不受控。
     */
    @Test
    void dropsOldestHistoryOnceTheCharacterBudgetIsExceeded() {
        AppProperties properties = new AppProperties();
        properties.getAiAgent().setMaxConversationChars(10_000);
        AiConversationStore store = new AiConversationStore(properties);
        AiConversationStore.Turn turn = store.begin(null, "local", 3, "PUBLIC", 1, "问题", null);

        List<LlmAgentMessage> history = new ArrayList<>(turn.messages());
        for (int round = 0; round < 6; round++) {
            history.add(LlmAgentMessage.user("第 " + round + " 轮"));
            history.add(LlmAgentMessage.assistant("", List.of()));
            history.add(LlmAgentMessage.toolResults(List.of(
                    new LlmToolResult("call-" + round, "x".repeat(4_000), false))));
        }
        store.complete(turn, history, "回答", new AiGroundingReport(false, "无 SQL", List.of()), List.of());

        AiConversationStore.Turn next = store.begin(turn.id(), "local", 3, "PUBLIC", 1, "再问", null);

        // 最后一条是新提的问题，其余是被裁剩下的历史；窗口必须从 USER 开始，否则工具结果会
        // 变成找不到 tool_use 的孤儿，模型侧直接报错。
        assertThat(next.messages()).hasSizeLessThan(history.size());
        assertThat(next.messages().get(0).role()).isEqualTo(LlmAgentMessage.Role.USER);
        long chars = next.messages().stream()
                .flatMap(message -> message.toolResults().stream())
                .mapToLong(result -> result.content().length())
                .sum();
        assertThat(chars).isLessThanOrEqualTo(10_000);
        store.fail(next);
    }

    @Test
    void requiresFreshStructureInspectionAfterMetadataChanges() {
        AiConversationStore store = new AiConversationStore(new AppProperties());
        AiConversationStore.Turn first = store.begin(null, "local", 3, "PUBLIC", 1, "问题", null);
        List<LlmAgentMessage> history = new ArrayList<>(first.messages());
        history.add(LlmAgentMessage.assistant("回答", List.of()));
        store.complete(first, history, "回答", new AiGroundingReport(false, "无 SQL", List.of()), List.of());

        AiConversationStore.Turn refreshed = store.begin(first.id(), "local", 3, "PUBLIC", 2, "再问", null);

        assertThat(refreshed.requireInspection()).isTrue();
        store.fail(refreshed);
    }
}
