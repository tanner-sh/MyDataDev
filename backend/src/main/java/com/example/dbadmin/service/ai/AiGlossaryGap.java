package com.example.dbadmin.service.ai;

import java.time.Instant;

/**
 * 一条待补词条：AI 搜过、这个库里什么都没搜到的业务词。
 *
 * @param hits 被搜空过多少次；同一个词反复搜不到，说明它是这条连接上真正缺的那个说法
 */
public record AiGlossaryGap(String term, int hits, Instant lastSeenAt) {
}
