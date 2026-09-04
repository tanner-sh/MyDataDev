package com.example.dbadmin.service.ai.llm;

/**
 * 一次模型调用的输入。
 *
 * <p>刻意只有「一段系统提示 + 一段用户提示」这一种形状：本期没有多轮对话，把 messages
 * 数组提前抽象出来只会多一层没人用的包装。</p>
 *
 * @param systemPrompt 结构摘要等稳定前缀。Claude 侧会对它打 cache_control 断点，所以
 *                     调用方必须把每次都变的内容放进 {@code userPrompt}，否则缓存永远打不中
 * @param maxTokens    输出上限
 */
public record LlmRequest(String systemPrompt, String userPrompt, long maxTokens) {
    public static final long DEFAULT_MAX_TOKENS = 8_000;

    public LlmRequest {
        if (userPrompt == null || userPrompt.isBlank()) throw new IllegalArgumentException("用户提示不能为空。");
        if (maxTokens <= 0) maxTokens = DEFAULT_MAX_TOKENS;
    }

    public static LlmRequest of(String systemPrompt, String userPrompt) {
        return new LlmRequest(systemPrompt, userPrompt, DEFAULT_MAX_TOKENS);
    }
}
