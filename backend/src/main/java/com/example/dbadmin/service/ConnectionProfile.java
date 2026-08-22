package com.example.dbadmin.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 连接档案字段的规范化与校验。
 *
 * <p>分组、标签、备注只影响连接列表怎么组织；默认命名空间与会话初始化 SQL 会作用在真实
 * 会话上，所以这两项要严一些。</p>
 */
public final class ConnectionProfile {
    public static final int MAX_GROUP_LENGTH = 120;
    public static final int MAX_TAGS = 12;
    public static final int MAX_TAG_LENGTH = 40;
    public static final int MAX_DEFAULT_SCHEMA_LENGTH = 240;
    public static final int MAX_DESCRIPTION_LENGTH = 1_000;
    public static final int MAX_INIT_STATEMENTS = 10;

    /** 标签里不允许逗号（存储用逗号分隔）和控制字符。 */
    private static final Pattern TAG_SEPARATOR = Pattern.compile("[,，]");
    /** Oracle 的会话设置以 ALTER SESSION 开头，分类器会把它当成 DDL，这里单独放行。 */
    private static final Pattern ALTER_SESSION = Pattern.compile("^\\s*ALTER\\s+SESSION\\b", Pattern.CASE_INSENSITIVE);

    private ConnectionProfile() {
    }

    public static String normalizeGroup(String value) {
        return trimToNull(value, MAX_GROUP_LENGTH);
    }

    public static String normalizeDefaultSchema(String value) {
        return trimToNull(value, MAX_DEFAULT_SCHEMA_LENGTH);
    }

    public static String normalizeDescription(String value) {
        return trimToNull(value, MAX_DESCRIPTION_LENGTH);
    }

    /**
     * 把用户输入的标签整理成存储用的逗号分隔串。
     *
     * <p>去空白、去重（忽略大小写，保留首次出现的写法）、按输入顺序保留 —— 排序交给前端，
     * 存储层不该替用户决定标签顺序。</p>
     */
    public static String normalizeTags(String value) {
        List<String> tags = parseTags(value);
        return tags.isEmpty() ? null : String.join(",", tags);
    }

    public static List<String> parseTags(String value) {
        if (value == null || value.isBlank()) return List.of();
        Map<String, String> unique = new LinkedHashMap<>();
        for (String raw : TAG_SEPARATOR.split(value)) {
            String tag = raw.trim();
            if (tag.isEmpty()) continue;
            if (tag.length() > MAX_TAG_LENGTH) {
                throw new IllegalArgumentException("标签「" + tag + "」超过 " + MAX_TAG_LENGTH + " 个字符。");
            }
            unique.putIfAbsent(tag.toLowerCase(Locale.ROOT), tag);
            if (unique.size() > MAX_TAGS) {
                throw new IllegalArgumentException("最多只能设置 " + MAX_TAGS + " 个标签。");
            }
        }
        return List.copyOf(unique.values());
    }

    public static String normalizeInitSql(String value) {
        String trimmed = value == null ? null : value.trim();
        return trimmed == null || trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * 拆出会话初始化语句，并拒绝不属于会话设置的语句。
     *
     * <p>初始化 SQL 会在每条物理连接上隐式执行，包括 MCP 只读代理借用的连接。放任任意语句
     * 等于给只读通道开了一个写入后门，所以这里只放行会话级设置（SET / USE / RESET /
     * PRAGMA…，以及 Oracle 的 ALTER SESSION）。</p>
     */
    public static List<String> initStatements(String initSql, SqlScriptSplitter splitter, SqlStatementClassifier classifier) {
        String normalized = normalizeInitSql(initSql);
        if (normalized == null) return List.of();
        List<String> statements = new ArrayList<>();
        for (SqlScriptSplitter.StatementSegment segment : splitter.split(normalized)) {
            String sql = segment.sql().trim();
            if (sql.isEmpty()) continue;
            if (!classifier.changesSession(sql) && !ALTER_SESSION.matcher(sql).find()) {
                throw new IllegalArgumentException(
                        "会话初始化 SQL 只允许会话级设置语句（SET / USE / RESET / ALTER SESSION 等），不被允许的语句：" + preview(sql)
                );
            }
            statements.add(sql);
            if (statements.size() > MAX_INIT_STATEMENTS) {
                throw new IllegalArgumentException("会话初始化 SQL 最多 " + MAX_INIT_STATEMENTS + " 条语句。");
            }
        }
        return List.copyOf(statements);
    }

    private static String preview(String sql) {
        String single = sql.replaceAll("\\s+", " ");
        return single.length() <= 60 ? single : single.substring(0, 60) + "…";
    }

    private static String trimToNull(String value, int maxLength) {
        if (value == null) return null;
        String trimmed = value.trim();
        if (trimmed.isEmpty()) return null;
        if (trimmed.length() > maxLength) {
            throw new IllegalArgumentException("内容超过 " + maxLength + " 个字符。");
        }
        return trimmed;
    }
}
