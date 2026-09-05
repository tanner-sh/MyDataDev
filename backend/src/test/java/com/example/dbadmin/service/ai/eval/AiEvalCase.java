package com.example.dbadmin.service.ai.eval;

import java.util.List;
import java.util.Set;

/**
 * 一条评测用例：自然语言问题，加上「这条 SQL 必须命中哪些表」。
 *
 * <p>主要看表集合，不比对 SQL 文本。同一个需求有无数种写法（子查询、JOIN、窗口函数都对），
 * 比对文本量的是模型的风格而不是它有没有理解这个库；而「有没有找对表」恰恰是这套 Agent
 * 存在的理由 —— 找错表，后面写得再漂亮都是错的。</p>
 *
 * <p>{@code expectedTokens} 是补充的一维：表选对了，口径仍然可能错 —— 销售额取商品标价还是
 * 取明细金额、统计成交算不算未支付的单，这类规矩只写在这个库跑过的语句里。要求某个字段名
 * 出现，是在不绑死写法的前提下量这一维最省事的办法。</p>
 *
 * <p>还有一类用例的正确答案不是 SQL 而是一个问题（{@code expectsClarification}）。没有它，
 * 打分机制只奖励「猜出一条 SQL」—— 猜一个总比问一句得分高，模型学到的就是别问。</p>
 *
 * @param expectedTables 必须出现的表；少一张就算没通过
 * @param forbiddenTables 出现即失败的表，用来钉住归档表这类近似干扰项
 * @param expectedTokens 必须出现的标识符，通常是决定口径的那个字段
 * @param expectsClarification 正确答案是反问：需求本身有歧义，猜一个就算错
 */
public record AiEvalCase(
        String id,
        String question,
        Set<String> expectedTables,
        Set<String> forbiddenTables,
        Set<String> expectedTokens,
        boolean expectsClarification,
        String note
) {
    public AiEvalCase {
        expectedTables = Set.copyOf(expectedTables);
        forbiddenTables = forbiddenTables == null ? Set.of() : Set.copyOf(forbiddenTables);
        expectedTokens = expectedTokens == null ? Set.of() : Set.copyOf(expectedTokens);
    }

    public AiEvalCase(String id, String question, Set<String> expectedTables, Set<String> forbiddenTables,
                      Set<String> expectedTokens, String note) {
        this(id, question, expectedTables, forbiddenTables, expectedTokens, false, note);
    }

    static AiEvalCase of(String id, String question, List<String> expected, String note) {
        return new AiEvalCase(id, question, Set.copyOf(expected), Set.of(), Set.of(), note);
    }

    /** 正确答案是一个问题，不是一条 SQL。 */
    static AiEvalCase clarify(String id, String question, String note) {
        return new AiEvalCase(id, question, Set.of(), Set.of(), Set.of(), true, note);
    }

    static AiEvalCase of(String id, String question, List<String> expected, List<String> tokens, String note) {
        return new AiEvalCase(id, question, Set.copyOf(expected), Set.of(), Set.copyOf(tokens), note);
    }

    static AiEvalCase of(
            String id, String question, List<String> expected,
            List<String> forbidden, List<String> tokens, String note
    ) {
        return new AiEvalCase(id, question, Set.copyOf(expected), Set.copyOf(forbidden), Set.copyOf(tokens), note);
    }
}
