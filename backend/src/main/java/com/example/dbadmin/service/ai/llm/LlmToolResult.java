package com.example.dbadmin.service.ai.llm;

/** 一次工具调用的文本结果；数据库内容始终按不可信数据处理。 */
public record LlmToolResult(String callId, String content, boolean error) {
    public LlmToolResult {
        if (callId == null || callId.isBlank()) throw new IllegalArgumentException("工具调用 id 不能为空。");
        content = content == null ? "" : content;
    }
}
