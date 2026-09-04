package com.example.dbadmin.service.ai;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从一条 SQL 里挑出它引用了哪些表。
 *
 * <p>不是解析器，也不打算成为解析器：这里只需要「该把哪几张表的结构发给模型」这一个答案，
 * 多认出几个不存在的名字，代价是后面查元数据时查不到、跳过；漏认一个，代价是模型少看一张表。
 * 两边都不致命，所以用正则而不是引一个 SQL 解析库。</p>
 *
 * <p>刻意保留原始大小写：Oracle 与 PostgreSQL 的标识符大小写语义不同，规范化留给元数据查询那一层。</p>
 */
public final class SqlTableReferences {
    /** 一次最多认多少张表。再多就不是「这条 SQL 涉及的表」，而是整库了。 */
    public static final int MAX_TABLES = 12;

    /** FROM / JOIN / INTO / UPDATE / TABLE 后面跟的第一个标识符。 */
    private static final Pattern REFERENCE = Pattern.compile(
            "(?is)\\b(?:from|join|into|update|table)\\s+((?:\"[^\"]+\"|`[^`]+`|\\[[^\\]]+\\]|[A-Za-z_$][\\w$]*)"
                    + "(?:\\s*\\.\\s*(?:\"[^\"]+\"|`[^`]+`|\\[[^\\]]+\\]|[A-Za-z_$][\\w$]*)){0,2})");
    /** 行注释、块注释与字符串字面量：注释里的表名不算引用，字面量里的更不算。 */
    private static final Pattern NOISE = Pattern.compile("(?s)--[^\\n]*|/\\*.*?\\*/|'(?:''|[^'])*'");
    /** FROM 后面可能直接跟子查询或函数调用，这些不是表名。 */
    private static final Set<String> NOT_A_TABLE = Set.of("select", "values", "lateral", "unnest", "table", "dual");

    private SqlTableReferences() {
    }

    /**
     * @return 引用到的表名，保持出现顺序去重；带库名/模式名前缀时原样保留
     */
    public static Set<String> extract(String sql) {
        Set<String> tables = new LinkedHashSet<>();
        if (sql == null || sql.isBlank()) return tables;
        String cleaned = NOISE.matcher(sql).replaceAll(" ");
        Matcher matcher = REFERENCE.matcher(cleaned);
        while (matcher.find() && tables.size() < MAX_TABLES) {
            String candidate = matcher.group(1).replaceAll("\\s*\\.\\s*", ".").trim();
            if (candidate.isEmpty()) continue;
            String last = candidate.substring(candidate.lastIndexOf('.') + 1);
            if (NOT_A_TABLE.contains(unquote(last).toLowerCase(Locale.ROOT))) continue;
            tables.add(candidate);
        }
        return tables;
    }

    /** 拆成命名空间与对象名两段；没有前缀时命名空间为 {@code null}。 */
    public static String[] split(String reference) {
        String value = reference == null ? "" : reference.trim();
        int lastDot = value.lastIndexOf('.');
        if (lastDot < 0) return new String[]{null, unquote(value)};
        return new String[]{unquote(value.substring(0, lastDot)), unquote(value.substring(lastDot + 1))};
    }

    public static String unquote(String identifier) {
        String value = identifier == null ? "" : identifier.trim();
        if (value.length() < 2) return value;
        char first = value.charAt(0);
        char last = value.charAt(value.length() - 1);
        boolean quoted = (first == '"' && last == '"') || (first == '`' && last == '`') || (first == '[' && last == ']');
        return quoted ? value.substring(1, value.length() - 1) : value;
    }
}
