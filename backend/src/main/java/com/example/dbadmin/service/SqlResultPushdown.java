package com.example.dbadmin.service;

import com.example.dbadmin.core.DatabaseDialect;
import com.example.dbadmin.dto.ApiDtos.SqlResultFilter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 把结果网格上的排序和列筛选下推成 SQL。
 *
 * <p>为什么要下推：结果是服务端分页的，而网格原本只对**当前这一页**排序和筛选。用户在第 1 页
 * 点一下列头，看到的是「这 500 行内部有序」，很容易当成整个结果集有序 —— 这种错觉比没有功能
 * 更糟。表浏览那条路（{@link DataEditService}）本来就是服务端筛选加排序的，两条路的语义不该
 * 不一致。</p>
 *
 * <p>做法是把原查询包一层：{@code SELECT * FROM (原 SQL) mdd_view WHERE … ORDER BY …}。不往
 * 原文末尾追加 —— 原查询自己可能已经带了 ORDER BY，追加只会变成语法错误或更糟的静默改写。
 * 分页由方言的 {@code pageQuery} 在最外面再包一次，三件事互不干涉。</p>
 *
 * <p><b>筛选值一律走绑定参数，绝不拼进 SQL。</b>列名是标识符，只能靠方言的引用规则转义；
 * 而值来自输入框，拼进去就是注入点。类型也是绑定参数的理由：把列转成文本再比，数字列、
 * 时间列、布尔列都能用同一套「包含 / 等于 / 为空」语义，与用户此前在前端看到的行为一致。</p>
 */
public final class SqlResultPushdown {
    /** 派生表的别名。MySQL、PostgreSQL、SQL Server 都要求子查询有别名。 */
    static final String ALIAS = "mdd_view";
    /** 列标签的长度上限。真实标签不会有这么长，超了说明这不是从结果集里来的。 */
    static final int MAX_COLUMN_CHARS = 128;
    /** 单次最多几个筛选条件。一屏列数就那么多，再多说明这个请求不是界面发出来的。 */
    static final int MAX_FILTERS = 30;
    /** LIKE 的转义符。用 {@code /} 而不是反斜杠：MySQL 的 NO_BACKSLASH_ESCAPES 会改变反斜杠的含义。 */
    static final char LIKE_ESCAPE = '/';

    private SqlResultPushdown() {
    }

    /** 一次下推的产物：改写后的 SQL，以及要按顺序绑定的参数。 */
    public record Shaped(String sql, List<Object> parameters) {
        public Shaped {
            parameters = List.copyOf(parameters);
        }

        public boolean hasParameters() {
            return !parameters.isEmpty();
        }
    }

    /** 排序方向；空值按升序。只认 ASC / DESC 两种，其余一律拒绝。 */
    public static String normalizeDirection(String direction) {
        if (direction == null || direction.isBlank()) return "ASC";
        String normalized = direction.trim().toUpperCase(Locale.ROOT);
        if (normalized.equals("ASC") || normalized.equals("DESC")) return normalized;
        throw new IllegalArgumentException("排序方向只能是 ASC 或 DESC：" + direction);
    }

    /**
     * 给这条查询套上筛选与排序。
     *
     * @param column 排序列的结果集标签；为空表示不排序
     * @param filters 列筛选；为空表示不筛选
     */
    public static Shaped apply(
            String sql,
            List<SqlResultFilter> filters,
            String column,
            String direction,
            DatabaseDialect dialect
    ) {
        String sortColumn = column == null || column.isBlank() ? null : requireColumn(column);
        List<SqlResultFilter> conditions = filters == null ? List.of() : filters;
        if (conditions.size() > MAX_FILTERS) {
            throw new IllegalArgumentException("筛选条件过多（上限 " + MAX_FILTERS + " 个）。");
        }
        if (sortColumn == null && conditions.isEmpty()) return new Shaped(sql, List.of());

        List<Object> parameters = new ArrayList<>();
        StringBuilder where = new StringBuilder();
        for (SqlResultFilter filter : conditions) {
            String quoted = dialect.quoteIdentifier(requireColumn(filter.column()));
            // 转成文本再比：数字列、时间列、布尔列都能用同一套「包含 / 等于 / 为空」语义，
            // 和用户此前在前端看到的行为一致。COALESCE 让 NULL 与空串同样落进「为空」。
            String text = "LOWER(COALESCE(" + dialect.castToText(quoted) + ", ''))";
            if (!where.isEmpty()) where.append(" AND ");
            where.append(condition(filter, text, parameters));
        }

        StringBuilder shaped = new StringBuilder("SELECT * FROM (")
                .append(stripTrailingSemicolon(sql))
                .append(") ").append(ALIAS);
        if (!where.isEmpty()) shaped.append(" WHERE ").append(where);
        if (sortColumn != null) {
            shaped.append(" ORDER BY ").append(dialect.quoteIdentifier(sortColumn))
                    .append(' ').append(normalizeDirection(direction));
        }
        return new Shaped(shaped.toString(), parameters);
    }

    private static String condition(SqlResultFilter filter, String text, List<Object> parameters) {
        String operator = filter.operator() == null ? "" : filter.operator().trim().toLowerCase(Locale.ROOT);
        String value = filter.value() == null ? "" : filter.value().toLowerCase(Locale.ROOT);
        return switch (operator) {
            case "empty" -> text + " = ''";
            case "notempty" -> text + " <> ''";
            case "equals" -> bind(text + " = ?", value, parameters);
            case "notequals" -> bind(text + " <> ?", value, parameters);
            case "contains" -> bind(text + " LIKE ? ESCAPE '" + LIKE_ESCAPE + "'", like(value), parameters);
            case "notcontains" -> bind(text + " NOT LIKE ? ESCAPE '" + LIKE_ESCAPE + "'", like(value), parameters);
            default -> throw new IllegalArgumentException("不支持的筛选操作：" + filter.operator());
        };
    }

    private static String bind(String fragment, String value, List<Object> parameters) {
        parameters.add(value);
        return fragment;
    }

    /** 用户输入的 {@code %} 与 {@code _} 是字面量，不是通配符 —— 前端的「包含」一直是这个语义。 */
    static String like(String value) {
        StringBuilder escaped = new StringBuilder("%");
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            if (ch == '%' || ch == '_' || ch == LIKE_ESCAPE) escaped.append(LIKE_ESCAPE);
            escaped.append(ch);
        }
        return escaped.append('%').toString();
    }

    private static String requireColumn(String column) {
        String trimmed = column == null ? "" : column.trim();
        if (trimmed.isEmpty()) throw new IllegalArgumentException("筛选或排序的列名不能为空。");
        if (trimmed.length() > MAX_COLUMN_CHARS) {
            throw new IllegalArgumentException("列名过长（上限 " + MAX_COLUMN_CHARS + " 字符）。");
        }
        // 控制字符不可能出现在结果集列标签里，出现了就说明这个参数不是从界面来的。
        for (int index = 0; index < trimmed.length(); index++) {
            if (Character.isISOControl(trimmed.charAt(index))) {
                throw new IllegalArgumentException("列名包含非法字符。");
            }
        }
        return trimmed;
    }

    /** 结尾的分号会让子查询语法错误，而 SQL 工作台里带分号是常态。 */
    private static String stripTrailingSemicolon(String sql) {
        String trimmed = sql == null ? "" : sql.trim();
        while (trimmed.endsWith(";")) trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
        return trimmed;
    }
}
