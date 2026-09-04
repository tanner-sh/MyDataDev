package com.example.dbadmin.service.ai;

/**
 * 一条连接的 AI 共享策略。
 *
 * @param sampleRowLimit 允许发送的样本行数；非 {@code STRUCTURE_AND_SAMPLE} 档恒为 0
 */
public record AiConnectionPolicy(long connectionId, AiSchemaSharing sharing, int sampleRowLimit) {
    public static AiConnectionPolicy none(long connectionId) {
        return new AiConnectionPolicy(connectionId, AiSchemaSharing.NONE, 0);
    }
}
