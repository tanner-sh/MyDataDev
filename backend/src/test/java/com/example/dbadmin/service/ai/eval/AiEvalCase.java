package com.example.dbadmin.service.ai.eval;

import java.util.List;
import java.util.Set;

/**
 * 一条评测用例：自然语言问题，加上「这条 SQL 必须命中哪些表」。
 *
 * <p>只校验表集合，不校验 SQL 文本。同一个需求有无数种写法（子查询、JOIN、窗口函数都对），
 * 比对文本量的是模型的风格而不是它有没有理解这个库；而「有没有找对表」恰恰是这套 Agent
 * 存在的理由 —— 找错表，后面写得再漂亮都是错的。</p>
 *
 * @param expectedTables 必须出现的表；少一张就算没通过
 * @param forbiddenTables 出现即失败的表，用来钉住归档表这类近似干扰项
 */
public record AiEvalCase(
        String id,
        String question,
        Set<String> expectedTables,
        Set<String> forbiddenTables,
        String note
) {
    public AiEvalCase {
        expectedTables = Set.copyOf(expectedTables);
        forbiddenTables = forbiddenTables == null ? Set.of() : Set.copyOf(forbiddenTables);
    }

    static AiEvalCase of(String id, String question, List<String> expected, String note) {
        return new AiEvalCase(id, question, Set.copyOf(expected), Set.of(), note);
    }

    static AiEvalCase of(String id, String question, List<String> expected, List<String> forbidden, String note) {
        return new AiEvalCase(id, question, Set.copyOf(expected), Set.copyOf(forbidden), note);
    }
}
