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
     * 结果集解读与图表推荐。
     *
     * <p>候选图表由前端的 {@code resultChart.ts} 算出来一并发过来：能画什么是确定的事（哪些列是
     * 数值、有多少个分类），让模型在真实候选里挑，而不是凭空推荐一个画不出来的图。</p>
     */
    public static String interpret(String sql, String resultPreview, String chartCandidates) {
        StringBuilder text = new StringBuilder("""
                下面是一条查询和它的部分结果。请回答两件事，各不超过三句：
                1. 这批数据说明了什么（只说数据本身支持的结论，不要外推）；
                2. 有没有值得注意的异常值或缺失。
                注意：你看到的只是前几行，不是全量数据，涉及总量的结论要说明这一点。

                SQL：
                ```sql
                """);
        text.append(clamp(sql, MAX_SQL_CHARS)).append("\n```\n\n结果预览：\n").append(clamp(resultPreview, MAX_SQL_CHARS)).append('\n');
        if (chartCandidates != null && !chartCandidates.isBlank()) {
            text.append("""

                    工作台能画出来的图表候选如下。如果其中某个更适合表达这批数据，用一句话推荐它并说明
                    理由；都不合适就直说不必画图。不要推荐候选之外的图表类型。
                    """).append(clamp(chartCandidates, 1_000)).append('\n');
        }
        return text.toString();
    }

    /**
     * Schema 文档：给一批表写数据字典。
     *
     * <p>要求逐表输出固定结构，方便把分批调用的结果拼成一份文档。</p>
     */
    public static String document(String namespace, String tableNames) {
        return """
                为下面这些表写一份数据字典，供不熟悉这个库的同事阅读。每张表按这个结构输出：

                ## 表名
                一句话说明这张表存什么、什么时候写入。
                | 字段 | 类型 | 说明 |
                （只列出需要解释的字段：主键、外键、状态码、含义不明显的字段。id、created_at 这类
                自解释的字段不必逐个写。）

                只依据给出的表结构与注释推断；推断不出来的字段写「用途不明」，不要编造业务含义。
                命名空间：%s
                本次要写的表：%s
                """.formatted(namespace == null || namespace.isBlank() ? "（默认）" : namespace, tableNames);
    }

    /**
     * 结构同步脚本的风险说明。
     *
     * <p>脚本本身由结构对比按目标端方言生成，这里只做解读 —— 模型不改脚本，改了用户也不该信。</p>
     */
    public static String reviewScript(String script) {
        return """
                下面是结构对比生成的同步脚本，将在目标库上执行。请按风险从高到低列出需要注意的地方，
                每条一行，说明是哪条语句、风险是什么（数据丢失、锁表、不可回滚、依赖顺序等）。
                没有风险的语句不必逐条复述。最后用一句话给出总体判断。
                不要改写脚本 —— 脚本由工作台按目标库方言生成，你只负责解读。

                ```sql
                %s
                ```
                """.formatted(clamp(script, MAX_SQL_CHARS * 2));
    }

    private static String clamp(String value, int max) {
        String text = value == null ? "" : value.trim();
        return text.length() <= max ? text : text.substring(0, max) + "\n…（已截断）";
    }
}
