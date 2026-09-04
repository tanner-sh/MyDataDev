package com.example.dbadmin.service.ai.llm;

import java.util.List;

/** 一次 Agent 模型轮次；工具执行由上层完成，本对象只描述模型输入。 */
public record LlmAgentRequest(
        String systemPrompt,
        List<LlmAgentMessage> messages,
        List<LlmToolDefinition> tools,
        long maxTokens
) {
    public LlmAgentRequest {
        systemPrompt = systemPrompt == null ? "" : systemPrompt;
        messages = messages == null ? List.of() : List.copyOf(messages);
        tools = tools == null ? List.of() : List.copyOf(tools);
        if (messages.isEmpty()) throw new IllegalArgumentException("Agent 消息不能为空。");
        if (maxTokens <= 0) maxTokens = LlmRequest.DEFAULT_MAX_TOKENS;
    }
}
