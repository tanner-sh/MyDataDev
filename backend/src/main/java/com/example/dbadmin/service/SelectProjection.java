package com.example.dbadmin.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 判断一条 SELECT 的投影列表是不是「直投影」：只有 {@code *} 和裸字段名，没有别名，也没有表达式。
 *
 * <p>为什么需要它：结果集就地编辑要把界面上的一列映射回表里的一个字段，而 JDBC 元数据给不了
 * 这个映射。{@code ResultSetMetaData.getColumnLabel} 返回的是别名，{@code getColumnName} 在
 * 一部分驱动上（例如 pgjdbc）直接返回同一个别名，{@code getTableName} 又不受别名影响。于是
 * {@code SELECT nickname AS code, code AS nickname FROM people} 会被判成一张表的普通结果，
 * 而主键 code 的值会从 nickname 那一列取 —— 定位到的可能是另一行，写回的也是另一个字段。</p>
 *
 * <p>驱动层面无解，就退回到 SQL 文本：只要投影里出现别名或表达式，就不再提供就地编辑。宁可
 * 少给一次编辑能力，也不能改错行。判断刻意保守 —— 看不懂的写法一律返回 false。</p>
 */
final class SelectProjection {
    private SelectProjection() {
    }

    /**
     * 这条 SQL 的投影列表是否可以按列名安全地映射回源表字段。
     *
     * <p>接受 {@code SELECT *}、{@code SELECT a, b}、{@code SELECT t.a, "My Col"}；
     * 拒绝别名、表达式、函数调用、DISTINCT、CTE、UNION 以及任何解析不了的写法。</p>
     */
    static boolean isDirectColumnProjection(String sql) {
        if (sql == null || sql.isBlank()) return false;
        String stripped = stripComments(sql);
        if (stripped == null) return false;
        int cursor = skipWhitespace(stripped, 0);
        if (!matchesKeyword(stripped, cursor, "SELECT")) return false;
        cursor = skipWhitespace(stripped, cursor + "SELECT".length());
        if (matchesKeyword(stripped, cursor, "ALL")) {
            cursor = skipWhitespace(stripped, cursor + "ALL".length());
        } else if (matchesKeyword(stripped, cursor, "DISTINCT") || matchesKeyword(stripped, cursor, "DISTINCTROW")) {
            // 去重后的行与表里的行不再一一对应，本来就不该编辑。
            return false;
        }
        List<String> items = projectionItems(stripped, cursor);
        if (items == null || items.isEmpty()) return false;
        for (String item : items) {
            if (!isColumnReference(item.trim())) return false;
        }
        return !hasSetOperator(stripped);
    }

    /**
     * 切出顶层 SELECT 与 FROM 之间的投影项。
     *
     * @return 投影项列表；没有顶层 FROM、括号不配对或引号不闭合时返回 {@code null}
     */
    private static List<String> projectionItems(String sql, int start) {
        List<String> items = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        int index = start;
        while (index < sql.length()) {
            char c = sql.charAt(index);
            char quote = quoteCharacter(c);
            if (quote != 0) {
                int end = skipQuoted(sql, index);
                if (end < 0) return null;
                current.append(sql, index, end);
                index = end;
                continue;
            }
            if (c == '(') depth++;
            if (c == ')') {
                depth--;
                if (depth < 0) return null;
            }
            if (depth == 0) {
                if (c == ',') {
                    items.add(current.toString());
                    current.setLength(0);
                    index++;
                    continue;
                }
                if (matchesKeyword(sql, index, "FROM")) {
                    items.add(current.toString());
                    return items;
                }
            }
            current.append(c);
            index++;
        }
        // 没有 FROM 的 SELECT 不来自任何表。
        return null;
    }

    /** 单个投影项：{@code *}、{@code col}、{@code t.col}、{@code s.t.*}，且不带别名。 */
    private static boolean isColumnReference(String item) {
        if (item.isEmpty()) return false;
        List<String> parts = splitQualifiedName(item);
        if (parts == null || parts.isEmpty() || parts.size() > 3) return false;
        for (int index = 0; index < parts.size(); index++) {
            String part = parts.get(index);
            boolean last = index == parts.size() - 1;
            if (last && part.equals("*")) continue;
            if (!isIdentifier(part)) return false;
        }
        return true;
    }

    /** 按顶层的点切分限定名；引号内的点属于标识符本身。 */
    private static List<String> splitQualifiedName(String item) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int index = 0;
        while (index < item.length()) {
            char c = item.charAt(index);
            if (quoteCharacter(c) != 0) {
                int end = skipQuoted(item, index);
                if (end < 0) return null;
                current.append(item, index, end);
                index = end;
                continue;
            }
            if (c == '.') {
                parts.add(current.toString());
                current.setLength(0);
                index++;
                continue;
            }
            current.append(c);
            index++;
        }
        parts.add(current.toString());
        return parts;
    }

    private static boolean isIdentifier(String part) {
        if (part.isEmpty()) return false;
        char first = part.charAt(0);
        // 单引号包的是字符串常量，不是标识符 —— 'x' 是一个表达式，不能编辑。
        char closing = first == '\'' ? 0 : closingQuote(first);
        if (closing != 0) {
            // 带引号的标识符：内部允许空格和保留字，但必须自成一体地闭合。
            return part.length() >= 2 && part.charAt(part.length() - 1) == closing
                    && skipQuoted(part, 0) == part.length();
        }
        if (!Character.isLetter(first) && first != '_') return false;
        for (int index = 1; index < part.length(); index++) {
            char c = part.charAt(index);
            if (!Character.isLetterOrDigit(c) && c != '_' && c != '$' && c != '#') return false;
        }
        return true;
    }

    /**
     * UNION / INTERSECT / EXCEPT 之后的行来自多个查询，第一段的投影说明不了整体。
     * 这里只在顶层扫一遍关键字，扫不准就当成有。
     */
    private static boolean hasSetOperator(String sql) {
        int depth = 0;
        int index = 0;
        while (index < sql.length()) {
            char c = sql.charAt(index);
            if (quoteCharacter(c) != 0) {
                int end = skipQuoted(sql, index);
                if (end < 0) return true;
                index = end;
                continue;
            }
            if (c == '(') depth++;
            if (c == ')') depth--;
            if (depth == 0 && (matchesKeyword(sql, index, "UNION")
                    || matchesKeyword(sql, index, "INTERSECT")
                    || matchesKeyword(sql, index, "EXCEPT")
                    || matchesKeyword(sql, index, "MINUS"))) {
                return true;
            }
            index++;
        }
        return false;
    }

    /** 去掉注释，保留原有的字符位置语义（注释整体换成一个空格）。 */
    private static String stripComments(String sql) {
        StringBuilder result = new StringBuilder(sql.length());
        int index = 0;
        while (index < sql.length()) {
            char c = sql.charAt(index);
            if (quoteCharacter(c) != 0) {
                int end = skipQuoted(sql, index);
                if (end < 0) return null;
                result.append(sql, index, end);
                index = end;
                continue;
            }
            if (c == '-' && index + 1 < sql.length() && sql.charAt(index + 1) == '-') {
                while (index < sql.length() && sql.charAt(index) != '\n') index++;
                result.append(' ');
                continue;
            }
            if (c == '/' && index + 1 < sql.length() && sql.charAt(index + 1) == '*') {
                int end = sql.indexOf("*/", index + 2);
                if (end < 0) return null;
                index = end + 2;
                result.append(' ');
                continue;
            }
            result.append(c);
            index++;
        }
        return result.toString();
    }

    private static char quoteCharacter(char c) {
        return closingQuote(c);
    }

    private static char closingQuote(char c) {
        return switch (c) {
            case '\'' -> '\'';
            case '"' -> '"';
            case '`' -> '`';
            case '[' -> ']';
            default -> 0;
        };
    }

    /**
     * 跳过一段引号包裹的内容。
     *
     * @return 结束引号之后的下标；没有闭合时返回 -1
     */
    private static int skipQuoted(String text, int start) {
        char open = text.charAt(start);
        char close = closingQuote(open);
        int index = start + 1;
        while (index < text.length()) {
            char c = text.charAt(index);
            if (c == close) {
                // 连写两个引号是转义，不是结束（'' 与 ""）。
                if (open != '[' && index + 1 < text.length() && text.charAt(index + 1) == close) {
                    index += 2;
                    continue;
                }
                return index + 1;
            }
            index++;
        }
        return -1;
    }

    private static int skipWhitespace(String text, int start) {
        int index = start;
        while (index < text.length() && Character.isWhitespace(text.charAt(index))) index++;
        return index;
    }

    /** 关键字必须整词匹配，避免把 {@code fromage} 当成 FROM。 */
    private static boolean matchesKeyword(String text, int index, String keyword) {
        if (index < 0 || index + keyword.length() > text.length()) return false;
        if (!text.regionMatches(true, index, keyword, 0, keyword.length())) return false;
        if (index > 0 && isWordCharacter(text.charAt(index - 1))) return false;
        int after = index + keyword.length();
        return after >= text.length() || !isWordCharacter(text.charAt(after));
    }

    private static boolean isWordCharacter(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '#';
    }
}
