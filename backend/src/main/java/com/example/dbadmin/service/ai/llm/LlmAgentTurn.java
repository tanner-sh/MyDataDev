package com.example.dbadmin.service.ai.llm;

import java.util.List;

/** 模型的一轮响应：可能请求工具，也可能直接给出最终文本。 */
public record LlmAgentTurn(
        String text,
        List<LlmToolCall> toolCalls,
        long inputTokens,
        long outputTokens,
        long cacheReadTokens
) {
    public LlmAgentTurn {
        text = text == null ? "" : text;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }
}
