package com.example.dbadmin.service.ai;

import java.util.regex.Pattern;

/**
 * 执行计划解读这一轮，允许模型给出什么样的 SQL。
 *
 * <p>其他轮次只收 SELECT，这一轮还得收「建这个索引」—— 那恰恰是计划解读最有价值的产出。
 * 但也只收到建索引为止：模型在优化的名义下给出 {@code DROP INDEX}、{@code ALTER TABLE} 甚至
 * {@code DELETE} 都不是没有可能，而这些语句一旦被顺手写进编辑器执行，代价和收益完全不对等。
 * 删一个索引是不是安全，取决于这个库上还有谁在用它，那是人的判断。</p>
 *
 * <p>索引脚本不做编译校验：{@code compileQuery} 只接 SELECT，而 prepare 一条 DDL 在有些驱动上
 * 就等于执行它。所以它按原样交给用户，在 SQL 工作台里走正常的执行路径 —— 生产确认与审计
 * 都在那条路上。</p>
 */
public final class AiPlanAdvice {
    private static final Pattern CREATE_INDEX =
            Pattern.compile("(?is)^\\s*create\\s+(unique\\s+)?index\\b.*");
    /** 行注释与块注释：模型常在语句前写一句「-- 给订单表加索引」。 */
    private static final Pattern LEADING_COMMENTS =
            Pattern.compile("(?s)^(\\s*(--[^\\n]*\\n|/\\*.*?\\*/))+");

    private AiPlanAdvice() {
    }

    /** 这条 SQL 是不是一条建索引语句。 */
    public static boolean isIndexScript(String sql) {
        if (sql == null || sql.isBlank()) return false;
        return CREATE_INDEX.matcher(stripLeadingComments(sql)).matches();
    }

    static String stripLeadingComments(String sql) {
        return LEADING_COMMENTS.matcher(sql).replaceFirst("").trim();
    }
}
