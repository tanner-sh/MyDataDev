package com.example.dbadmin.service.ai;

import java.util.Locale;

/**
 * 把一条 SQL 抹成「形状」：去掉字面量、注释和多余空白。
 *
 * <p>存在的理由是隐私边界。执行历史里带着真实业务值 —— {@code WHERE mobile = '13800138000'}
 * 是一条个人信息，{@code WHERE amount > 88888} 是一笔真实金额。而历史对 AI 的价值只在于
 * 「这个库里的人是怎么关联这几张表的」，那部分在去掉字面量之后一点没少。抹掉之后，历史就
 * 落在「只发结构」这一档里，不需要用户额外授权发送样本数据。</p>
 *
 * <p>不是解析器：它只认字符串、数字和注释这三类词法元素，标识符引号（双引号、反引号、方括号）
 * 里的内容原样保留 —— 那是表名和列名，正是要留下的东西。</p>
 */
public final class AiSqlShape {
    private AiSqlShape() {
    }

    /** 抹掉字面量和注释，保留语句结构与标识符。 */
    public static String mask(String sql) {
        if (sql == null || sql.isBlank()) return "";
        StringBuilder out = new StringBuilder(sql.length());
        int index = 0;
        while (index < sql.length()) {
            char ch = sql.charAt(index);
            char next = index + 1 < sql.length() ? sql.charAt(index + 1) : '\0';

            if (ch == '-' && next == '-') {
                index = skipTo(sql, index + 2, '\n');
                out.append(' ');
                continue;
            }
            if (ch == '/' && next == '*') {
                int end = sql.indexOf("*/", index + 2);
                index = end < 0 ? sql.length() : end + 2;
                out.append(' ');
                continue;
            }
            if (ch == '\'') {
                index = skipQuoted(sql, index + 1, '\'');
                out.append('?');
                continue;
            }
            // 标识符引号：内容是表名列名，原样留下。
            if (ch == '"' || ch == '`' || ch == '[') {
                char closing = ch == '[' ? ']' : ch;
                int end = skipQuoted(sql, index + 1, closing);
                out.append(sql, index, end);
                index = end;
                continue;
            }
            if (Character.isDigit(ch) && !isIdentifierPart(previous(out))) {
                int end = index;
                while (end < sql.length()
                        && (Character.isDigit(sql.charAt(end)) || sql.charAt(end) == '.'
                        || sql.charAt(end) == 'e' || sql.charAt(end) == 'E'
                        || ((sql.charAt(end) == '+' || sql.charAt(end) == '-')
                        && (sql.charAt(end - 1) == 'e' || sql.charAt(end - 1) == 'E')))) {
                    end++;
                }
                out.append('?');
                index = end;
                continue;
            }
            out.append(ch);
            index++;
        }
        return collapse(out.toString());
    }

    /**
     * 去重用的指纹：同一条查询跑一百次只应该给模型看一次。
     *
     * <p>在 {@link #mask} 之上再统一大小写 —— 大小写不同的同一条 SQL 是同一种写法，不是两种。</p>
     */
    public static String fingerprint(String sql) {
        return mask(sql).toLowerCase(Locale.ROOT);
    }

    private static char previous(StringBuilder out) {
        return out.isEmpty() ? ' ' : out.charAt(out.length() - 1);
    }

    private static boolean isIdentifierPart(char ch) {
        return Character.isLetterOrDigit(ch) || ch == '_' || ch == '$' || ch == '.';
    }

    private static int skipTo(String sql, int index, char stop) {
        while (index < sql.length() && sql.charAt(index) != stop) index++;
        return index;
    }

    /** @return 收尾引号之后的下标 */
    private static int skipQuoted(String sql, int index, char quote) {
        while (index < sql.length()) {
            char ch = sql.charAt(index);
            if (ch == '\\' && index + 1 < sql.length()) {
                index += 2;
                continue;
            }
            if (ch == quote) {
                if (index + 1 < sql.length() && sql.charAt(index + 1) == quote) {
                    index += 2;
                    continue;
                }
                return index + 1;
            }
            index++;
        }
        return sql.length();
    }

    private static String collapse(String value) {
        StringBuilder out = new StringBuilder(value.length());
        boolean pendingSpace = false;
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            if (Character.isWhitespace(ch)) {
                pendingSpace = !out.isEmpty();
                continue;
            }
            if (pendingSpace) {
                out.append(' ');
                pendingSpace = false;
            }
            out.append(ch);
        }
        return out.toString();
    }
}
