package com.example.dbadmin.service;

import com.example.dbadmin.core.DatabaseDialect;

import java.util.Locale;

/**
 * 把结果网格上的一次排序下推成 SQL。
 *
 * <p>为什么要下推：结果是服务端分页的，而网格此前只对**当前这一页**排序。用户在第 1 页点一下
 * 列头，看到的是「这 500 行内部有序」，很容易当成整个结果集有序 —— 这种错觉比没有排序更糟。
 * 表浏览那条路（{@link DataEditService}）本来就是服务端排序的，两条路的语义不该不一致。</p>
 *
 * <p>做法是把原查询包一层：{@code SELECT * FROM (原 SQL) t ORDER BY "列" ASC}。不直接往原文
 * 末尾追加 ORDER BY —— 原查询自己可能已经带了一个，追加只会变成语法错误或者更糟的静默改写。
 * 包一层还有个好处：分页由方言的 {@code pageQuery} 在外面再包一次，两件事互不干涉。</p>
 *
 * <p>列名来自结果集元数据里的列标签，用方言的引用规则转义后拼进 SQL。这里不做「这个标签是不是
 * 真的存在」的校验：那要先执行一次查询才知道，而写错的标签本来就会被数据库直接顶回来，代价
 * 只是一条报错。真正要防的是引号逃逸，那由 {@code quoteIdentifier} 负责。</p>
 */
public final class SqlSortPushdown {
    /** 派生表的别名。MySQL、PostgreSQL、SQL Server 都要求子查询有别名。 */
    static final String ALIAS = "mdd_sorted";
    /** 列标签的长度上限。真实标签不会有这么长，超了说明这不是从结果集里来的。 */
    static final int MAX_COLUMN_CHARS = 128;

    private SqlSortPushdown() {
    }

    /** 排序方向；空值按升序。只认 ASC / DESC 两种，其余一律拒绝。 */
    public static String normalizeDirection(String direction) {
        if (direction == null || direction.isBlank()) return "ASC";
        String normalized = direction.trim().toUpperCase(Locale.ROOT);
        if (normalized.equals("ASC") || normalized.equals("DESC")) return normalized;
        throw new IllegalArgumentException("排序方向只能是 ASC 或 DESC：" + direction);
    }

    /**
     * 给这条查询套上排序。
     *
     * @param column 结果集里的列标签；为空表示不排序，原样返回
     */
    public static String apply(String sql, String column, String direction, DatabaseDialect dialect) {
        if (column == null || column.isBlank()) return sql;
        String trimmed = column.trim();
        if (trimmed.length() > MAX_COLUMN_CHARS) {
            throw new IllegalArgumentException("排序列名过长（上限 " + MAX_COLUMN_CHARS + " 字符）。");
        }
        // 控制字符不可能出现在结果集列标签里，出现了就说明这个参数不是从界面来的。
        for (int index = 0; index < trimmed.length(); index++) {
            if (Character.isISOControl(trimmed.charAt(index))) {
                throw new IllegalArgumentException("排序列名包含非法字符。");
            }
        }
        return "SELECT * FROM (" + stripTrailingSemicolon(sql) + ") " + ALIAS
                + " ORDER BY " + dialect.quoteIdentifier(trimmed) + " " + normalizeDirection(direction);
    }

    /** 结尾的分号会让子查询语法错误，而 SQL 工作台里带分号是常态。 */
    private static String stripTrailingSemicolon(String sql) {
        String trimmed = sql == null ? "" : sql.trim();
        while (trimmed.endsWith(";")) trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
        return trimmed;
    }
}
