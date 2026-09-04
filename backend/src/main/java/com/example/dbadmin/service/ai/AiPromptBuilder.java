package com.example.dbadmin.service.ai;

/**
 * 提示词组装。
 *
 * <p>纯逻辑，并且刻意把「稳定的部分」和「每次都变的部分」分成两个方法：系统提示（角色、
 * 方言、结构上下文）是缓存前缀，用户提示（这次的 SQL、这次的报错）放在断点之后。把时间戳
 * 或本次 SQL 混进系统提示，缓存就永远打不中 —— 这是这个类存在的主要理由。</p>
 */
public final class AiPromptBuilder {
    /** 单条 SQL 发给模型的字符上限。超长脚本没有诊断价值，只会烧 token。 */
    public static final int MAX_SQL_CHARS = 8_000;
    /** 报错原文的字符上限。驱动偶尔会把整个执行计划塞进异常信息里。 */
    public static final int MAX_ERROR_CHARS = 4_000;

    private AiPromptBuilder() {
    }

    /**
     * 系统提示：角色 + 方言 + 结构上下文。同一条连接的连续提问，这段是完全一样的。
     */
    public static String system(SchemaContext context, String dialectHint) {
        StringBuilder text = new StringBuilder();
        text.append("""
                你是 MyDataDev 数据库工作台里的 SQL 助手，帮助用户读懂和写好 SQL。

                硬性要求：
                - 用简体中文回答，语气直接，不要寒暄，不要复述问题。
                - 只依据下面给出的表结构作答；结构里没有的表或列，明说「结构里没有这张表/这一列」，绝不臆造。
                - 给出 SQL 时，用 ```sql 代码块包裹，且只给一条语句。
                - 你没有执行权限。任何 SQL 都由用户自己在编辑器里确认后执行，所以不要说「我已经执行」这类话。
                """);
        if (dialectHint != null && !dialectHint.isBlank()) {
            text.append("\n目标数据库：").append(dialectHint).append('\n');
        }
        String schema = SchemaContextFormat.render(context);
        if (!schema.isBlank()) {
            text.append("\n可用的表结构：\n\n").append(schema);
        } else {
            text.append("\n（这次没有可用的表结构，回答时说明你看不到结构。）\n");
        }
        return text.toString();
    }

    /** 报错诊断：先说原因，再说怎么改。 */
    public static String diagnose(String sql, String errorMessage) {
        return """
                下面这条 SQL 执行失败了。请依次回答三件事，每件事不超过三句：
                1. 数据库为什么报这个错；
                2. 具体改哪里；
                3. 改好之后的完整 SQL（放在 ```sql 代码块里）。
                如果失败原因与表结构无关（比如权限、连接、锁），直接说明，不要硬凑一条 SQL。

                SQL：
                ```sql
                %s
                ```

                数据库返回的错误：
                %s
                """.formatted(clamp(sql, MAX_SQL_CHARS), clamp(errorMessage, MAX_ERROR_CHARS));
    }

    /** 自然语言转 SQL：只要一条语句，不解释一堆。 */
    public static String generate(String question, boolean readonlyConnection) {
        String constraint = readonlyConnection
                ? "这是一条只读连接，只能给 SELECT 语句。"
                : "如果需求本身是写操作，可以给写语句，但要在 SQL 前一句话点明它会修改数据。";
        return """
                把下面的需求写成一条 SQL。%s
                先给 SQL（```sql 代码块，只给一条语句），再用一两句话说明它做了什么、有哪些前提。
                如果需求含糊到写不出确定的 SQL，直接说缺什么信息，不要猜着写。

                需求：
                %s
                """.formatted(constraint, clamp(question, MAX_SQL_CHARS));
    }

    /**
     * 执行计划解读。
     *
     * <p>确定性规则已经判断出来的结论（{@code ruleFindings}）一并发过去，让模型在它们之上
     * 解释「为什么慢、建什么索引」，而不是重新判断一遍 —— 规则那部分不该由模型来做。</p>
     */
    public static String explain(String sql, String planText, String ruleFindings) {
        StringBuilder text = new StringBuilder("""
                下面是一条 SQL 和它的执行计划。请回答两件事：
                1. 这个计划为什么可能慢（指出具体是哪一步）；
                2. 可以怎么改 —— 建索引就给出完整的建索引语句，改写 SQL 就给出改写后的语句。
                如果计划本身没有明显问题，直接说「这个计划没有明显问题」，不要为了凑建议而建议。

                SQL：
                ```sql
                """);
        text.append(clamp(sql, MAX_SQL_CHARS)).append("\n```\n\n执行计划：\n").append(clamp(planText, MAX_SQL_CHARS)).append('\n');
        if (ruleFindings != null && !ruleFindings.isBlank()) {
            text.append("\n工作台已经用固定规则识别出的信号（不必重复判断，请在此之上解释与给建议）：\n")
                    .append(clamp(ruleFindings, 2_000)).append('\n');
        }
        return text.toString();
    }

    private static String clamp(String value, int max) {
        String text = value == null ? "" : value.trim();
        return text.length() <= max ? text : text.substring(0, max) + "\n…（已截断）";
    }
}
