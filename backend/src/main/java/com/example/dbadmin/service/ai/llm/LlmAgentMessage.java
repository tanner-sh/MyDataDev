package com.example.dbadmin.service.ai.llm;

import java.util.List;

/**
 * 一轮 Agent 对话中的消息。
 *
 * <p>TOOL_RESULTS 在 Claude 协议里会转成带 tool_result 内容块的 user 消息，在 OpenAI
 * 兼容协议里会展开成若干 role=tool 消息。把差异留在 provider 内，编排层只维护一种形状。</p>
 */
public record LlmAgentMessage(
        Role role,
        String text,
        List<LlmToolCall> toolCalls,
        List<LlmToolResult> toolResults
) {
    public enum Role { USER, ASSISTANT, TOOL_RESULTS }

    public LlmAgentMessage {
        text = text == null ? "" : text;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        toolResults = toolResults == null ? List.of() : List.copyOf(toolResults);
    }

    public static LlmAgentMessage user(String text) {
        return new LlmAgentMessage(Role.USER, text, List.of(), List.of());
    }

    public static LlmAgentMessage assistant(String text, List<LlmToolCall> calls) {
        return new LlmAgentMessage(Role.ASSISTANT, text, calls, List.of());
    }

    public static LlmAgentMessage toolResults(List<LlmToolResult> results) {
        return new LlmAgentMessage(Role.TOOL_RESULTS, "", List.of(), results);
    }
}
