package com.example.dbadmin.service.ai;

import java.time.LocalDate;

/** 某一天、某个人、某个模型上的用量。 */
public record AiUsageEntry(
        LocalDate day,
        String actor,
        String model,
        int requests,
        long inputTokens,
        long outputTokens,
        long cacheReadTokens
) {
    /** 计入预算的口径：输入加输出。缓存读单独看 —— 它说明的是省了多少，不是花了多少。 */
    public long billable() {
        return inputTokens + outputTokens;
    }
}
