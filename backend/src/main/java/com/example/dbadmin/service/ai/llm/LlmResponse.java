package com.example.dbadmin.service.ai.llm;

/**
 * 一次模型调用的产出。
 *
 * <p>带上 token 用量是为了让「结构摘要是否真的被缓存命中」可观测：
 * {@code cacheReadTokens} 长期为 0 说明前缀里混进了每次都变的内容。</p>
 */
public record LlmResponse(String text, long inputTokens, long outputTokens, long cacheReadTokens) {
    public static LlmResponse text(String text) {
        return new LlmResponse(text, 0, 0, 0);
    }
}
