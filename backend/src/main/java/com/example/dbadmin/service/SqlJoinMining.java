package com.example.dbadmin.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 从一条 SQL 里挖出「哪两张表的哪两列被拿来做等值关联」。
 *
 * <p>不建外键的库里，团队真正在用的关联就写在执行过的 SQL 里：{@code JOIN org_orgs o ON o.pk_org = v.pk_org}、
 * Oracle 老写法的 {@code FROM a, b WHERE a.pk_org = b.pk_org}。这里认出这两种等值条件，把别名还原成表名，
 * 只产出 (表.列, 表.列) 的名字对 —— 不保留任何字面量，SQL 正文也不出这个类。</p>
 *
 * <p>不是解析器，方向是「宁可漏认」：</p>
 * <ul>
 *   <li>只认两边都是「限定名.列」的条件（{@code a.x = b.y}）；没写前缀的列分不清属于哪张表，不猜。</li>
 *   <li>等号任一侧还连着运算（{@code a.x = b.y + 1}、{@code a.x || '-' = b.y}）的不算 —— 那不是关联键。</li>
 *   <li>前缀指向子查询、CTE，或者同一个别名在一条语句的不同层里指向不同的表，都跳过。</li>
 *   <li>同一张表的同一列（{@code a.id = a.id}）没有意义，跳过；同表不同列（树形结构的 pk_parent = pk_xxx）保留。</li>
 * </ul>
 */
public final class SqlJoinMining {
    /** 一条 SQL 再长也只看这么多字符：挖关联靠的是 FROM / ON / WHERE，不需要整份几十 KB 的脚本。 */
    static final int MAX_SQL_CHARS = 20_000;

    /** 一个等值关联，两侧按 (表, 列) 的小写字典序排好，同一对关联不论写成 a=b 还是 b=a 都是同一个键。 */
    public record JoinPair(String leftTable, String leftColumn, String rightTable, String rightColumn) {
        public String key() {
            return (leftTable + "." + leftColumn + "=" + rightTable + "." + rightColumn).toLowerCase(Locale.ROOT);
        }

        static JoinPair of(String tableA, String columnA, String tableB, String columnB) {
            String a = (tableA + "." + columnA).toLowerCase(Locale.ROOT);
            String b = (tableB + "." + columnB).toLowerCase(Locale.ROOT);
            return a.compareTo(b) <= 0 ? new JoinPair(tableA, columnA, tableB, columnB) : new JoinPair(tableB, columnB, tableA, columnA);
        }
    }

    private static final Set<String> NOT_ALIAS = Set.of(
            "where", "join", "inner", "left", "right", "full", "cross", "outer", "on", "using", "natural", "group", "order",
            "having", "union", "minus", "except", "intersect", "limit", "offset", "fetch", "for", "connect", "start", "window",
            "partition", "set", "values", "with", "lateral", "apply", "pivot", "unpivot", "sample", "as", "into", "returning");
    private static final Set<String> FROM_BOUNDARY = Set.of(
            "where", "group", "order", "having", "union", "minus", "except", "intersect", "limit", "offset", "fetch", "for",
            "connect", "start", "window", "on", "using", "returning");
    private static final Set<String> JOIN_WORDS = Set.of("join");
    /** 等号两侧紧挨着这些符号，说明它是表达式的一部分而不是关联键。 */
    private static final Set<String> ARITHMETIC = Set.of("+", "-", "*", "/", "||", "%");

    private SqlJoinMining() {
    }

    /** 一条 SQL 里出现的等值关联，去重、按出现顺序。 */
    public static List<JoinPair> extract(String sql) {
        if (sql == null || sql.isBlank()) return List.of();
        List<Token> tokens = tokenize(sql.length() > MAX_SQL_CHARS ? sql.substring(0, MAX_SQL_CHARS) : sql);
        Map<String, String> aliases = tableAliases(tokens);
        Set<String> seen = new LinkedHashSet<>();
        List<JoinPair> pairs = new ArrayList<>();
        for (int index = 0; index < tokens.size(); index++) {
            if (!tokens.get(index).is("=")) continue;
            QualifiedColumn left = columnEndingAt(tokens, index - 1);
            QualifiedColumn right = columnStartingAt(tokens, index + 1);
            if (left == null || right == null) continue;
            if (left.start() > 0 && isOperator(tokens.get(left.start() - 1))) continue;
            if (right.end() + 1 < tokens.size() && isOperator(tokens.get(right.end() + 1))) continue;
            String leftTable = resolve(aliases, left.qualifier());
            String rightTable = resolve(aliases, right.qualifier());
            if (leftTable == null || rightTable == null) continue;
            boolean sameTable = leftTable.equalsIgnoreCase(rightTable);
            if (sameTable && left.column().equalsIgnoreCase(right.column())) continue;
            JoinPair pair = JoinPair.of(leftTable, left.column(), rightTable, right.column());
            if (seen.add(pair.key())) pairs.add(pair);
        }
        return pairs;
    }

    private record Token(String text, boolean identifier) {
        boolean is(String value) {
            return !identifier && text.equals(value);
        }

        boolean word(Set<String> words) {
            return identifier && words.contains(text.toLowerCase(Locale.ROOT));
        }

        String lower() {
            return text.toLowerCase(Locale.ROOT);
        }
    }

    private record QualifiedColumn(String qualifier, String column, int start, int end) {
    }

    /**
     * 别名 → 表名（不带 Schema）。表名本身也登记成可用的前缀（{@code orders.id}）。
     * 指向子查询的别名登记为 null；同一个前缀指向两张不同的表时也置 null —— 宁可不认。
     */
    private static Map<String, String> tableAliases(List<Token> tokens) {
        Map<String, String> aliases = new HashMap<>();
        Set<String> conflicted = new LinkedHashSet<>();
        // CTE 的名字不是表：WITH report_rows AS (…) SELECT … FROM report_rows JOIN sales —— 不先认出来的话，
        // report_rows 会被当成一张真实的表，挖出一条库里根本不存在的关联。
        Set<String> cteNames = cteNames(tokens);
        for (int index = 0; index < tokens.size(); index++) {
            Token token = tokens.get(index);
            boolean from = token.identifier() && token.lower().equals("from");
            boolean join = token.word(JOIN_WORDS);
            if (!from && !join) continue;
            int cursor = index + 1;
            while (cursor < tokens.size()) {
                cursor = readTableReference(tokens, cursor, aliases, conflicted);
                // FROM a, b, c：逗号后面还是表；JOIN 后面只有一张。
                if (from && cursor < tokens.size() && tokens.get(cursor).is(",")) {
                    cursor++;
                    continue;
                }
                break;
            }
        }
        for (String key : conflicted) aliases.put(key, null);
        for (Map.Entry<String, String> entry : aliases.entrySet()) {
            if (entry.getValue() != null && cteNames.contains(entry.getValue().toLowerCase(Locale.ROOT))) entry.setValue(null);
        }
        return aliases;
    }

    /**
     * WITH [RECURSIVE] name [(列…)] AS (…) [, name AS (…)]… 里定义的名字（小写）。
     * 要求紧跟 AS 和左括号才算 —— Oracle 的 START WITH 后面是条件，不会被误认。
     */
    private static Set<String> cteNames(List<Token> tokens) {
        Set<String> names = new HashSet<>();
        for (int index = 0; index < tokens.size(); index++) {
            if (!tokens.get(index).identifier() || !tokens.get(index).lower().equals("with")) continue;
            int cursor = index + 1;
            if (cursor < tokens.size() && tokens.get(cursor).identifier() && tokens.get(cursor).lower().equals("recursive")) cursor++;
            while (cursor < tokens.size() && tokens.get(cursor).identifier()) {
                String name = tokens.get(cursor).lower();
                int next = cursor + 1;
                if (next < tokens.size() && tokens.get(next).is("(")) next = afterBalanced(tokens, next);
                if (next + 1 >= tokens.size() || !tokens.get(next).identifier() || !tokens.get(next).lower().equals("as")
                        || !tokens.get(next + 1).is("(")) break;
                names.add(name);
                cursor = afterBalanced(tokens, next + 1);
                if (cursor < tokens.size() && tokens.get(cursor).is(",")) cursor++;
                else break;
            }
        }
        return names;
    }

    /** 读一个表引用（含可选别名），返回读完之后的位置。 */
    private static int readTableReference(List<Token> tokens, int start, Map<String, String> aliases, Set<String> conflicted) {
        if (start >= tokens.size()) return start;
        int cursor = start;
        String table = null;
        if (tokens.get(cursor).is("(")) {
            cursor = afterBalanced(tokens, cursor);
        } else {
            if (!tokens.get(cursor).identifier() || tokens.get(cursor).word(FROM_BOUNDARY)) return cursor;
            table = tokens.get(cursor).text();
            cursor++;
            // schema.table / catalog.schema.table：只留最后一段。
            while (cursor + 1 < tokens.size() && tokens.get(cursor).is(".") && tokens.get(cursor + 1).identifier()) {
                table = tokens.get(cursor + 1).text();
                cursor += 2;
            }
            // 表函数调用 TABLE(...) 之类不是表。
            if (cursor < tokens.size() && tokens.get(cursor).is("(")) {
                cursor = afterBalanced(tokens, cursor);
                table = null;
            }
        }
        if (cursor < tokens.size() && tokens.get(cursor).identifier() && tokens.get(cursor).lower().equals("as")) cursor++;
        String alias = null;
        if (cursor < tokens.size() && tokens.get(cursor).identifier() && !tokens.get(cursor).word(NOT_ALIAS)) {
            alias = tokens.get(cursor).text();
            cursor++;
        }
        if (table != null) register(aliases, conflicted, table, table);
        if (alias != null) register(aliases, conflicted, alias, table);
        return cursor;
    }

    private static void register(Map<String, String> aliases, Set<String> conflicted, String name, String table) {
        String key = name.toLowerCase(Locale.ROOT);
        if (!aliases.containsKey(key)) {
            aliases.put(key, table);
            return;
        }
        String existing = aliases.get(key);
        boolean same = existing == null ? table == null : table != null && existing.equalsIgnoreCase(table);
        if (!same) conflicted.add(key);
    }

    private static String resolve(Map<String, String> aliases, String qualifier) {
        return aliases.get(qualifier.toLowerCase(Locale.ROOT));
    }

    /** 以 end 结尾的「前缀.列」（也认 schema.table.列，前缀取倒数第二段），以及 Oracle 外连接的 (+)。 */
    private static QualifiedColumn columnEndingAt(List<Token> tokens, int end) {
        int cursor = end;
        // a.x(+) = b.y
        if (cursor >= 3 && tokens.get(cursor).is(")") && tokens.get(cursor - 1).is("+") && tokens.get(cursor - 2).is("(")) cursor -= 3;
        if (cursor < 2 || !tokens.get(cursor).identifier() || !tokens.get(cursor - 1).is(".") || !tokens.get(cursor - 2).identifier()) return null;
        int start = cursor - 2;
        if (start >= 2 && tokens.get(start - 1).is(".") && tokens.get(start - 2).identifier()) start -= 2;
        return new QualifiedColumn(tokens.get(cursor - 2).text(), tokens.get(cursor).text(), start, end);
    }

    private static QualifiedColumn columnStartingAt(List<Token> tokens, int start) {
        if (start + 2 >= tokens.size() || !tokens.get(start).identifier() || !tokens.get(start + 1).is(".") || !tokens.get(start + 2).identifier()) return null;
        int qualifierIndex = start;
        int columnIndex = start + 2;
        if (columnIndex + 2 < tokens.size() && tokens.get(columnIndex + 1).is(".") && tokens.get(columnIndex + 2).identifier()) {
            qualifierIndex = columnIndex;
            columnIndex += 2;
        }
        int end = columnIndex;
        if (end + 3 < tokens.size() && tokens.get(end + 1).is("(") && tokens.get(end + 2).is("+") && tokens.get(end + 3).is(")")) end += 3;
        // 后面紧跟 ( 是函数调用 schema.func(...)，不是列。
        if (end == columnIndex && end + 1 < tokens.size() && tokens.get(end + 1).is("(")) return null;
        return new QualifiedColumn(tokens.get(qualifierIndex).text(), tokens.get(columnIndex).text(), start, end);
    }

    private static boolean isOperator(Token token) {
        return !token.identifier() && ARITHMETIC.contains(token.text());
    }

    private static int afterBalanced(List<Token> tokens, int open) {
        int depth = 0;
        for (int index = open; index < tokens.size(); index++) {
            if (tokens.get(index).is("(")) depth++;
            else if (tokens.get(index).is(")") && --depth == 0) return index + 1;
        }
        return tokens.size();
    }

    /** 标识符（含引号标识符，去掉引号）与单个符号；注释与字符串字面量直接丢掉。 */
    private static List<Token> tokenize(String sql) {
        List<Token> tokens = new ArrayList<>();
        int length = sql.length();
        int index = 0;
        while (index < length) {
            char current = sql.charAt(index);
            if (Character.isWhitespace(current)) {
                index++;
            } else if (current == '-' && index + 1 < length && sql.charAt(index + 1) == '-') {
                int newline = sql.indexOf('\n', index);
                index = newline < 0 ? length : newline + 1;
            } else if (current == '/' && index + 1 < length && sql.charAt(index + 1) == '*') {
                int close = sql.indexOf("*/", index + 2);
                index = close < 0 ? length : close + 2;
            } else if (current == '\'') {
                index = skipQuoted(sql, index, '\'');
                tokens.add(new Token("?", false));
            } else if (current == '"' || current == '`') {
                int close = skipQuoted(sql, index, current);
                tokens.add(new Token(sql.substring(index + 1, Math.max(index + 1, close - 1)), true));
                index = close;
            } else if (current == '[') {
                int close = sql.indexOf(']', index);
                int end = close < 0 ? length : close;
                tokens.add(new Token(sql.substring(index + 1, end), true));
                index = end + 1;
            } else if (Character.isLetter(current) || current == '_' || current == '$' || current == '#') {
                int end = index + 1;
                while (end < length && (Character.isLetterOrDigit(sql.charAt(end)) || "_$#".indexOf(sql.charAt(end)) >= 0)) end++;
                tokens.add(new Token(sql.substring(index, end), true));
                index = end;
            } else if (Character.isDigit(current)) {
                int end = index + 1;
                while (end < length && (Character.isLetterOrDigit(sql.charAt(end)) || sql.charAt(end) == '.')) end++;
                tokens.add(new Token("0", false));
                index = end;
            } else if (current == '|' && index + 1 < length && sql.charAt(index + 1) == '|') {
                tokens.add(new Token("||", false));
                index += 2;
            } else if ((current == '<' || current == '>' || current == '!') && index + 1 < length && "=>".indexOf(sql.charAt(index + 1)) >= 0) {
                // <= >= <> != 不是等号，整体作为一个符号，免得被当成 "="。
                tokens.add(new Token(sql.substring(index, index + 2), false));
                index += 2;
            } else {
                tokens.add(new Token(String.valueOf(current), false));
                index++;
            }
        }
        return tokens;
    }

    private static int skipQuoted(String sql, int start, char quote) {
        int index = start + 1;
        while (index < sql.length()) {
            if (sql.charAt(index) == quote) {
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
}
