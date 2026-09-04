package com.example.dbadmin.service.ai;

import com.example.dbadmin.api.ApiProblemException;
import com.example.dbadmin.config.AppProperties;
import com.example.dbadmin.dto.AiDtos.AiGroundingReport;
import com.example.dbadmin.service.ai.llm.LlmAgentMessage;
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
