package com.example.dbadmin.service;

/**
 * 把用户写的一条 SQL 变成「可以被包一层、也可以被追加子句」的文本。
 *
 * <p>结果网格的服务端分页要对同一条 SQL 做两件事：{@link SqlResultPushdown} 把它包进
 * {@code SELECT * FROM (…) mdd_view}，方言的 {@code pageQuery} 再往外面追加分页子句。两件事
 * 都假设这条 SQL 的末尾是「语句结束」，而 SQL 工作台里的原文不一定满足这个假设。</p>
 *
 * <p>两种结尾会破坏它：</p>
 *
 * <ul>
 *   <li><b>结尾的分号</b> —— 子查询里带分号是语法错误，而在编辑器里写分号是常态；</li>
 *   <li><b>结尾的行注释</b> —— {@code -- 只看这张表} 写在最后一行（后面没有换行）时，追加上去的
 *       {@code LIMIT … OFFSET …} 会整段落进注释里。这一条尤其难发现：它不报错，也不多返回行
 *       （{@code setMaxRows} 还在），只是每一页都返回第一页 —— 用户往后翻，看到的是同一批行。
 *       包一层的那条路则是闭括号被注释吞掉，直接语法错误。</li>
 * </ul>
 */
final class EmbeddableSql {
    /**
     * 行注释的起头。{@code --} 是标准写法，{@code #} 是 MySQL 系的。
     *
     * <p>只看「出现过没有」，<b>不判断它到底是不是注释</b>：真要判断，就得有一个能分清字符串
     * 字面量、{@code $$} 引用与 Oracle {@code q'[]'} 的扫描器，写错一个分支等于把这个 bug 放回来。
     * 认错了的代价只是多一个换行 —— 在任何数据库上都不改变语义。所以这里宁可宽。</p>
     */
    private static final String[] LINE_COMMENT_STARTS = {"--", "#"};

    private EmbeddableSql() {
    }

    /** 去掉结尾的分号，并保证末尾不会停在一行注释里。 */
    static String of(String sql) {
        String trimmed = sql == null ? "" : sql.trim();
        while (trimmed.endsWith(";")) trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
        return mayEndInLineComment(trimmed) ? trimmed + "\n" : trimmed;
    }

    private static boolean mayEndInLineComment(String sql) {
        for (String start : LINE_COMMENT_STARTS) {
            if (sql.contains(start)) return true;
        }
        return false;
    }
}
