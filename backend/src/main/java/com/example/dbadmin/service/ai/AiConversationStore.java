package com.example.dbadmin.service.ai;

import com.example.dbadmin.api.ApiProblemException;
import com.example.dbadmin.config.AppProperties;
import com.example.dbadmin.dto.AiDtos.AiChatMessageResponse;
import com.example.dbadmin.dto.AiDtos.AiConversationResponse;
import com.example.dbadmin.dto.AiDtos.AiGroundingReport;
import com.example.dbadmin.dto.AiDtos.AiExecutionFailure;
import com.example.dbadmin.dto.AiDtos.AiExecutionOutcome;
import com.example.dbadmin.dto.AiDtos.AiExecutionPlan;
import com.example.dbadmin.dto.AiDtos.AiGroundingReference;
import com.example.dbadmin.service.ai.llm.LlmAgentMessage;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 进程内、短时的可信 Agent 会话。
 *
 * <p>工具调用结果只保存在服务端，浏览器拿到的会话 ID 不能伪造“已检查结构”的历史。会话按
 * 登录用户隔离并自动过期，不写入长期数据库。</p>
 */
@Component
public class AiConversationStore {
    private static final int MAX_VISIBLE_MESSAGES = 40;
    private static final int MAX_INTERNAL_MESSAGES = 48;
    private final Cache<String, Conversation> conversations;
    private final int maxConversationChars;

    public AiConversationStore(AppProperties properties) {
        AppProperties.AiAgent config = properties.getAiAgent();
        maxConversationChars = Math.max(10_000, config.getMaxConversationChars());
        conversations = Caffeine.newBuilder()
                // 按字符数而不是按条数淘汰：会话里存的是工具结果原文，条数完全说明不了占用。
                .maximumWeight(Math.max(maxConversationChars, config.getMaxCachedChars()))
                .weigher((String ignored, Conversation conversation) -> conversation.weight)
                .expireAfterAccess(Duration.ofMinutes(Math.max(1, config.getConversationTtlMinutes())))
                .build();
    }

    public Turn begin(
            String requestedId,
            String ownerKey,
            long connectionId,
            String schemaName,
            long metadataVersion,
            String question,
            String currentSql,
            AiExecutionFailure failure,
            AiExecutionOutcome outcome,
            AiExecutionPlan plan
    ) {
        Conversation conversation = requestedId == null || requestedId.isBlank()
                ? create(ownerKey, connectionId, schemaName, metadataVersion)
                : require(requestedId, ownerKey, connectionId, schemaName);
        if (!conversation.busy.compareAndSet(false, true)) {
            throw new ApiProblemException(HttpStatus.CONFLICT, "AI_CONVERSATION_BUSY",
                    "这段 AI 对话已有请求正在处理，请等待它结束。");
        }
        try {
            synchronized (conversation) {
                boolean requireInspection = conversation.visibleMessages.isEmpty();
                if (conversation.metadataVersion != metadataVersion) {
                    conversation.internalMessages = visibleHistory(conversation.visibleMessages);
                    conversation.evidence = List.of();
                    conversation.metadataVersion = metadataVersion;
                    requireInspection = true;
                }
                // 执行失败或结果不对，都意味着此前对结构的理解就是错的（字段名不对、表不对、
                // 关联方向不对），所以带着执行现场进来时一律重新查一遍结构，不沿用已有结论。
                // 计划解读同理，而且更硬：不读出这张表上真实存在哪些索引，「该建什么索引」
                // 就只能靠猜，很可能建议一个已经有了的。
                if (failure != null || outcome != null || plan != null) requireInspection = true;
                List<LlmAgentMessage> messages = new ArrayList<>(conversation.internalMessages);
                messages.add(LlmAgentMessage.user(AiChatPrompt.compose(question, currentSql, failure, outcome, plan)));
                return new Turn(conversation, question, messages, requireInspection,
                        new ArrayList<>(conversation.evidence));
            }
        } catch (RuntimeException error) {
            conversation.busy.set(false);
            throw error;
        }
    }

    public void complete(
            Turn turn,
            List<LlmAgentMessage> internalMessages,
            String answer,
            AiGroundingReport grounding,
            List<AiGroundingReference> evidence
    ) {
        synchronized (turn.conversation) {
            turn.conversation.internalMessages = trimHistory(internalMessages, maxConversationChars);
            turn.conversation.evidence = distinctEvidence(evidence);
            turn.conversation.visibleMessages.add(new AiChatMessageResponse("USER", turn.question, null));
            turn.conversation.visibleMessages.add(new AiChatMessageResponse("ASSISTANT", answer, grounding));
            while (turn.conversation.visibleMessages.size() > MAX_VISIBLE_MESSAGES) {
                turn.conversation.visibleMessages.remove(0);
            }
            turn.conversation.weight = weight(turn.conversation);
        }
        // Caffeine 只在写入时称重，而会话是原地长大的；不重新 put，权重会一直停在创建那一刻。
        conversations.put(turn.conversation.id, turn.conversation);
        turn.conversation.busy.set(false);
    }

    public void fail(Turn turn) {
        turn.conversation.busy.set(false);
    }

    public AiConversationResponse get(String id, String ownerKey, long connectionId, String schemaName) {
        Conversation conversation = require(id, ownerKey, connectionId, schemaName);
        synchronized (conversation) {
            return new AiConversationResponse(conversation.id, conversation.connectionId, conversation.schemaName,
                    List.copyOf(conversation.visibleMessages));
        }
    }

    public boolean remove(String id, String ownerKey, long connectionId) {
        Conversation conversation = conversations.getIfPresent(id);
        if (conversation == null) return false;
        requireOwner(conversation, ownerKey);
        if (conversation.connectionId != connectionId) throw mismatch();
        if (conversation.busy.get()) {
            throw new ApiProblemException(HttpStatus.CONFLICT, "AI_CONVERSATION_BUSY", "请先停止当前 AI 请求再新建对话。");
        }
        conversations.invalidate(id);
        return true;
    }

    private Conversation create(String ownerKey, long connectionId, String schemaName, long metadataVersion) {
        String id = UUID.randomUUID().toString();
        Conversation conversation = new Conversation(id, ownerKey, connectionId, normalizedSchema(schemaName), metadataVersion);
        conversations.put(id, conversation);
        return conversation;
    }

    private Conversation require(String id, String ownerKey, long connectionId, String schemaName) {
        try {
            UUID.fromString(id);
        } catch (RuntimeException e) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, "AI_CONVERSATION_INVALID", "AI 对话 ID 无效。");
        }
        Conversation conversation = conversations.getIfPresent(id);
        if (conversation == null) {
            throw new ApiProblemException(HttpStatus.NOT_FOUND, "AI_CONVERSATION_EXPIRED", "AI 对话已过期，请新建对话。");
        }
        requireOwner(conversation, ownerKey);
        if (conversation.connectionId != connectionId
                || !conversation.schemaName.equals(normalizedSchema(schemaName))) throw mismatch();
        return conversation;
    }

    private static void requireOwner(Conversation conversation, String ownerKey) {
        if (!conversation.ownerKey.equals(ownerKey)) {
            throw new ApiProblemException(HttpStatus.FORBIDDEN, "AI_CONVERSATION_NOT_OWNED", "不能访问其他用户的 AI 对话。");
        }
    }

    private static ApiProblemException mismatch() {
        return new ApiProblemException(HttpStatus.CONFLICT, "AI_CONVERSATION_SCOPE_MISMATCH",
                "AI 对话与当前连接或命名空间不匹配，请新建对话。");
    }

    private static List<LlmAgentMessage> visibleHistory(List<AiChatMessageResponse> visible) {
        int from = Math.max(0, visible.size() - 12);
        List<LlmAgentMessage> result = new ArrayList<>();
        for (int index = from; index < visible.size(); index++) {
            AiChatMessageResponse message = visible.get(index);
            result.add("ASSISTANT".equals(message.role())
                    ? LlmAgentMessage.assistant(clamp(message.text(), 20_000), List.of())
                    : LlmAgentMessage.user(clamp(message.text(), 20_000)));
        }
        return result;
    }

    /**
     * 保留最近的历史，同时受条数与字符数两道约束。
     *
     * <p>窗口一律从一条 USER 消息开始：协议要求 tool_use 和 tool_result 成对出现，从中间切会留下
     * 找不到调用的工具结果，模型侧直接报错。</p>
     */
    private static List<LlmAgentMessage> trimHistory(List<LlmAgentMessage> input, int maxChars) {
        int from = Math.max(0, input.size() - MAX_INTERNAL_MESSAGES);
        long chars = 0;
        for (int index = input.size() - 1; index >= from; index--) {
            chars += chars(input.get(index));
            if (chars > maxChars) {
                from = index + 1;
                break;
            }
        }
        while (from < input.size() && input.get(from).role() != LlmAgentMessage.Role.USER) from++;
        return List.copyOf(input.subList(from, input.size()));
    }

    private static int weight(Conversation conversation) {
        long chars = 0;
        for (LlmAgentMessage message : conversation.internalMessages) chars += chars(message);
        for (AiChatMessageResponse message : conversation.visibleMessages) {
            chars += message.text() == null ? 0 : message.text().length();
        }
        for (AiGroundingReference reference : conversation.evidence) {
            chars += reference.label().length() + (reference.detail() == null ? 0 : reference.detail().length());
        }
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1, chars));
    }

    private static int chars(LlmAgentMessage message) {
        int total = message.text().length();
        for (var call : message.toolCalls()) total += call.arguments().toString().length();
        for (var result : message.toolResults()) total += result.content().length();
        return total;
    }

    private static List<AiGroundingReference> distinctEvidence(List<AiGroundingReference> input) {
        java.util.LinkedHashMap<String, AiGroundingReference> result = new java.util.LinkedHashMap<>();
        if (input != null) {
            for (AiGroundingReference item : input) {
                result.putIfAbsent(item.kind() + '\0' + item.label(), item);
                if (result.size() >= 2_000) break;
            }
        }
        return List.copyOf(result.values());
    }

    private static String normalizedSchema(String schemaName) {
        return schemaName == null ? "" : schemaName.trim().toLowerCase(Locale.ROOT);
    }

    private static String clamp(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }

    public record Turn(
            Conversation conversation,
            String question,
            List<LlmAgentMessage> messages,
            boolean requireInspection,
            List<AiGroundingReference> evidence
    ) {
        public String id() { return conversation.id; }
    }

    public static final class Conversation {
        private final String id;
        private final String ownerKey;
        private final long connectionId;
        private final String schemaName;
        private final AtomicBoolean busy = new AtomicBoolean();
        private long metadataVersion;
        /** 缓存权重（字符数）。由 {@link AiConversationStore#weight} 在每轮结束后重算。 */
        private volatile int weight = 1;
        private List<LlmAgentMessage> internalMessages = List.of();
        private List<AiGroundingReference> evidence = List.of();
        private final List<AiChatMessageResponse> visibleMessages = new ArrayList<>();

        private Conversation(String id, String ownerKey, long connectionId, String schemaName, long metadataVersion) {
            this.id = id;
            this.ownerKey = ownerKey;
            this.connectionId = connectionId;
            this.schemaName = schemaName;
            this.metadataVersion = metadataVersion;
        }
    }
}
