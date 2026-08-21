package com.example.dbadmin.service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * SQL 美化器。
 *
 * <p>旧实现只是在子句关键字前插入换行，长 SQL 格式化后所有行都顶格排列，子查询、
 * JOIN、AND/OR 全部混在一起，可读性很差。这里改成「词法切分 + 层级排版」：先把语句
 * 切成词法单元（标识符、字面量、注释、运算符、括号），再按子句、括号层级、条件连接词
 * 重新排版，输出与 DataGrip / PL/SQL Developer 接近的缩进风格：
 *
 * <pre>
 * SELECT c.pk_accountingbook,
 *        d.pk_accasoa
 * FROM bd_accasoa a
 *     INNER JOIN bd_accchart b ON a.pk_accchart = b.pk_accchart
 *     INNER JOIN (SELECT pk_account
 *                 FROM bd_accasoa
 *                 WHERE nvl(dr, 0) = 0) d
 *         ON d.pk_account = a.pk_account
 * WHERE nvl(a.dr, 0) = 0
 *   AND nvl(b.dr, 0) = 0
 * </pre>
 *
 * <p>字面量、注释、Oracle q'' 串、Postgres $$ 串在词法阶段整体保留，排版不会改动其内容。
 * 存储过程 / 匿名块（含 BEGIN、DECLARE 等）无法用子句模型可靠排版，直接原样返回，
 * 避免把可运行的脚本改坏。
 */
public final class SqlFormatter {
    /** 内联内容（函数参数、IN 列表）超过该宽度后在逗号处折行。 */
    private static final int MAX_LINE_WIDTH = 120;
    /** JOIN 相对于所属查询块的缩进。 */
    private static final int JOIN_INDENT = 4;
    /** JOIN 的连接条件另起一行时，相对 JOIN 的缩进。 */
    private static final int JOIN_CONDITION_INDENT = 4;
    /** CASE 分支相对 CASE 的缩进。 */
    private static final int CASE_INDENT = 4;

    /** 会独占一行的主子句；多词子句必须能整体匹配，否则 GROUP BY 会被拆成两段。 */
    private static final List<String> MAIN_CLAUSES = sortedByWords(List.of(
            "WITH", "SELECT", "FROM", "WHERE", "GROUP BY", "HAVING", "ORDER BY", "WINDOW",
            "LIMIT", "FETCH FIRST", "FETCH NEXT", "UNION ALL", "UNION",
            "INTERSECT ALL", "INTERSECT", "EXCEPT ALL", "EXCEPT", "MINUS",
            "CONNECT BY", "START WITH", "INSERT INTO", "VALUES", "UPDATE", "SET",
            "DELETE FROM", "RETURNING", "MERGE INTO", "USING", "QUALIFY", "FOR UPDATE"
    ));

    /** JOIN 子句，长短语优先，避免 LEFT OUTER JOIN 被当成 JOIN 提前断开。 */
    private static final List<String> JOIN_CLAUSES = sortedByWords(List.of(
            "NATURAL LEFT OUTER JOIN", "NATURAL RIGHT OUTER JOIN", "NATURAL FULL OUTER JOIN",
            "LEFT OUTER JOIN", "RIGHT OUTER JOIN", "FULL OUTER JOIN",
            "NATURAL LEFT JOIN", "NATURAL RIGHT JOIN", "NATURAL FULL JOIN",
            "LEFT JOIN", "RIGHT JOIN", "INNER JOIN", "FULL JOIN", "CROSS JOIN",
            "NATURAL JOIN", "STRAIGHT_JOIN", "CROSS APPLY", "OUTER APPLY", "JOIN"
    ));

    /** 这些子句下的顶层逗号会换行，每项一行并与首项对齐。 */
    private static final Set<String> LIST_CLAUSES = Set.of(
            "SELECT", "FROM", "GROUP BY", "ORDER BY", "SET", "RETURNING", "WITH", "VALUES"
    );

    /** 这些子句下的顶层 AND / OR 会换行，并与子句关键字右端对齐。 */
    private static final Set<String> CONDITION_CLAUSES = Set.of(
            "WHERE", "HAVING", "CONNECT BY", "START WITH", "QUALIFY"
    );

    /** 出现这些关键字说明是过程化脚本，交给调用方原样保留。 */
    private static final Set<String> PROCEDURAL_KEYWORDS = Set.of(
            "BEGIN", "DECLARE", "PACKAGE"
    );

    /** 子句短语的首词，用来先做一次廉价过滤，避免每个词都去匹配全部短语。 */
    private static final Set<String> CLAUSE_FIRST_WORDS = firstWords(MAIN_CLAUSES, JOIN_CLAUSES);

    private static final Set<String> KEYWORDS = Set.of(
            "SELECT", "FROM", "WHERE", "GROUP", "BY", "HAVING", "ORDER", "WINDOW", "PARTITION",
            "LIMIT", "OFFSET", "FETCH", "ONLY", "WITH", "RECURSIVE", "UNION", "INTERSECT",
            "EXCEPT", "MINUS", "ALL", "DISTINCT", "AS", "ON", "USING", "JOIN", "INNER", "LEFT",
            "RIGHT", "FULL", "OUTER", "CROSS", "NATURAL", "APPLY", "AND", "OR", "NOT", "IN",
            "EXISTS", "BETWEEN", "LIKE", "ILIKE", "RLIKE", "REGEXP", "IS", "NULL", "ASC", "DESC",
            "NULLS", "CASE", "WHEN", "THEN", "ELSE", "END", "INSERT", "INTO", "VALUES", "UPDATE",
            "SET", "DELETE", "MERGE", "MATCHED", "RETURNING", "TRUNCATE", "CREATE", "REPLACE",
            "ALTER", "DROP", "TABLE", "VIEW", "INDEX", "SEQUENCE", "SCHEMA", "DATABASE", "COLUMN",
            "CONSTRAINT", "PRIMARY", "FOREIGN", "REFERENCES", "UNIQUE", "CHECK", "DEFAULT",
            "CASCADE", "RESTRICT", "ADD", "MODIFY", "RENAME", "TO", "OVER", "QUALIFY", "CONNECT",
            "START", "PRIOR", "GRANT", "REVOKE", "COMMIT", "ROLLBACK", "SAVEPOINT", "EXPLAIN",
            "ANALYZE", "CAST", "INTERVAL", "ELSIF", "FOR", "UNBOUNDED", "PRECEDING", "FOLLOWING",
            "CURRENT", "ROW", "RANGE", "GROUPS", "LATERAL", "TEMPORARY", "IF", "TOP", "STRAIGHT_JOIN"
    );

    public String format(String sql) {
        if (sql == null || sql.isBlank()) {
            return "";
        }
        List<Token> tokens = tokenize(sql);
        if (tokens.isEmpty() || containsProceduralBlock(tokens)) {
            return sql.strip();
        }
        return new Renderer(tokens).render();
    }

    // ---------------------------------------------------------------- 排版

    private static final class Renderer {
        private final List<Token> tokens;
        private final Output out = new Output();
        private final Deque<Block> blocks = new ArrayDeque<>();
        private Token previous;
        private Token beforePrevious;
        private boolean previousUnary;
        private boolean pendingBlankLine;
        private boolean pendingNewLine;
        private int pendingIndent;
        /** 括号内的子查询首个子句要贴着左括号，不能另起一行。 */
        private boolean keepClauseInline;
        /** 内联括号内最近一个逗号的折行落点，-1 表示当前不允许折行。 */
        private int wrapIndent = -1;
        private int index;

        private Renderer(List<Token> tokens) {
            this.tokens = tokens;
        }

        private String render() {
            blocks.push(Block.root());
            while (index < tokens.size()) {
                step();
            }
            return out.finish();
        }

        private void step() {
            Token token = tokens.get(index);
            switch (token.kind()) {
                case LINE_COMMENT -> {
                    if (token.ownLine()) breakLine(blocks.peek().indent);
                    emit(token.text(), token);
                    breakLine(blocks.peek().indent);
                    index++;
                }
                case BLOCK_COMMENT -> {
                    if (token.ownLine()) breakLine(blocks.peek().indent);
                    emit(token.text(), token);
                    index++;
                }
                case OPEN -> openParen(token);
                case CLOSE -> closeParen(token);
                case COMMA -> comma(token);
                case SEMICOLON -> semicolon(token);
                case WORD -> word(token);
                default -> {
                    emit(textOf(token), token);
                    index++;
                }
            }
        }

        private void word(Token token) {
            Block block = blocks.peek();
            if (block.betweenPending && "AND".equals(token.upper())) {
                block.betweenPending = false;
                emit("AND", token);
                index++;
                return;
            }
            if (block.subquery && startClause(block)) {
                return;
            }
            switch (token.upper()) {
                case "ON" -> {
                    if (block.joinClause) {
                        if (out.line() != block.joinLine) {
                            breakLine(block.joinIndent + JOIN_CONDITION_INDENT);
                        }
                        block.joinClause = false;
                        block.clause = "ON";
                        emit("ON", token);
                        index++;
                        return;
                    }
                }
                case "AND", "OR" -> {
                    if (CONDITION_CLAUSES.contains(block.clause)) {
                        breakLine(block.conditionIndent(token.upper().length()));
                        emit(token.upper(), token);
                        index++;
                        return;
                    }
                }
                case "CASE" -> {
                    emit("CASE", token);
                    index++;
                    blocks.push(Block.caseBlock(out.column() - "CASE".length()));
                    return;
                }
                case "WHEN", "ELSE" -> {
                    if (block.caseBlock) {
                        breakLine(block.indent + CASE_INDENT);
                        emit(token.upper(), token);
                        index++;
                        return;
                    }
                }
                case "END" -> {
                    if (block.caseBlock) {
                        blocks.pop();
                        breakLine(block.indent);
                        emit("END", token);
                        index++;
                        return;
                    }
                }
                case "BETWEEN" -> block.betweenPending = true;
                default -> {
                    // 普通标识符或关键字，按内联规则输出。
                }
            }
            emit(textOf(token), token);
            index++;
        }

        /** 尝试把当前位置当作主子句 / JOIN 子句排版，命中时返回 true 并前进。 */
        private boolean startClause(Block block) {
            if (!CLAUSE_FIRST_WORDS.contains(tokens.get(index).upper())) return false;
            String join = matchPhrase(JOIN_CLAUSES);
            if (join != null) {
                block.joinIndent = block.indent + JOIN_INDENT;
                breakLine(block.joinIndent);
                emitPhrase(join);
                block.clause = "JOIN";
                block.joinClause = true;
                block.joinLine = out.line();
                return true;
            }
            String clause = matchPhrase(MAIN_CLAUSES);
            if (clause == null) {
                return false;
            }
            // JOIN ... USING (a) 属于连接条件，不是独立子句。
            if ("USING".equals(clause) && block.joinClause) {
                return false;
            }
            if (keepClauseInline) {
                keepClauseInline = false;
                pendingNewLine = false;
            } else {
                breakLine(block.indent);
            }
            emitPhrase(clause);
            block.clause = clause;
            block.clauseIndent = out.column() - clause.length();
            block.joinClause = false;
            block.alignPending = true;
            return true;
        }

        private void openParen(Token token) {
            Block block = blocks.peek();
            boolean subquery = startsSubquery(tokens, index);
            emit("(", token);
            index++;
            int indent = out.column();
            if (subquery) {
                Block nested = Block.subquery(indent);
                blocks.push(nested);
                keepClauseInline = true;
            } else {
                blocks.push(Block.inline(indent));
            }
            wrapIndent = -1;
        }

        private void closeParen(Token token) {
            // CASE 少写 END 时先把它丢掉，别让括号层级跟着错位。
            while (blocks.size() > 1 && blocks.peek().caseBlock) blocks.pop();
            if (blocks.size() > 1) blocks.pop();
            keepClauseInline = false;
            pendingNewLine = false;
            emit(")", token);
            index++;
            wrapIndent = -1;
        }

        private void comma(Token token) {
            Block block = blocks.peek();
            emit(",", token);
            index++;
            if (block.subquery && LIST_CLAUSES.contains(block.clause)) {
                breakLine(block.listIndent);
                wrapIndent = -1;
            } else if (!block.subquery) {
                wrapIndent = block.indent;
            }
        }

        private void semicolon(Token token) {
            emit(";", token);
            index++;
            while (blocks.size() > 1) blocks.pop();
            blocks.peek().reset();
            keepClauseInline = false;
            pendingNewLine = false;
            wrapIndent = -1;
            if (index < tokens.size()) pendingBlankLine = true;
        }

        private void breakLine(int indent) {
            pendingNewLine = true;
            pendingIndent = Math.max(0, indent);
            wrapIndent = -1;
        }

        private void emitPhrase(String phrase) {
            Token first = tokens.get(index);
            index += phrase.split(" ").length;
            // 多词子句整体当成一个词法单元，后续的空格规则才能看到完整的 INSERT INTO 之类。
            emit(phrase, new Token(Kind.WORD, phrase, phrase, first.ownLine()));
        }

        private void emit(String text, Token token) {
            if (pendingBlankLine) {
                out.blankLine();
                pendingBlankLine = false;
                pendingNewLine = false;
            }
            if (pendingNewLine) {
                out.newLine(pendingIndent);
                pendingNewLine = false;
            }
            boolean space = !out.lineEmpty() && needsSpace(beforePrevious, previous, token, previousUnary);
            if (wrapIndent >= 0 && !out.lineEmpty() && out.column() + text.length() + (space ? 1 : 0) > MAX_LINE_WIDTH) {
                out.newLine(wrapIndent);
                space = false;
            }
            Block block = blocks.peek();
            if (block.alignPending && token.kind() != Kind.LINE_COMMENT && token.kind() != Kind.BLOCK_COMMENT) {
                block.listIndent = out.column() + (space ? 1 : 0);
                block.alignPending = false;
            }
            if (space) out.append(" ");
            out.append(text);
            previousUnary = isUnary(previous, token, previousUnary);
            beforePrevious = previous;
            previous = token;
            if (token.kind() != Kind.OPEN && token.kind() != Kind.COMMA) wrapIndent = -1;
        }

        private String matchPhrase(List<String> phrases) {
            for (String phrase : phrases) {
                if (matches(phrase)) return phrase;
            }
            return null;
        }

        private boolean matches(String phrase) {
            String[] words = phrase.split(" ");
            if (index + words.length > tokens.size()) return false;
            for (int offset = 0; offset < words.length; offset++) {
                Token token = tokens.get(index + offset);
                if (token.kind() != Kind.WORD || !words[offset].equals(token.upper())) return false;
            }
            return true;
        }
    }

    // ---------------------------------------------------------------- 排版状态

    private static final class Block {
        private int indent;
        private final boolean subquery;
        private final boolean caseBlock;
        private String clause = "";
        private int clauseIndent;
        private int listIndent;
        private boolean alignPending;
        private boolean joinClause;
        private int joinIndent;
        private int joinLine = -1;
        private boolean betweenPending;

        private Block(int indent, boolean subquery, boolean caseBlock) {
            this.indent = indent;
            this.subquery = subquery;
            this.caseBlock = caseBlock;
            this.listIndent = indent;
        }

        private static Block root() {
            return new Block(0, true, false);
        }

        private static Block subquery(int indent) {
            return new Block(indent, true, false);
        }

        private static Block inline(int indent) {
            return new Block(indent, false, false);
        }

        private static Block caseBlock(int indent) {
            return new Block(indent, false, true);
        }

        /** AND / OR 换行后与子句关键字右端对齐：WHERE 下的 AND 缩进 2 格。 */
        private int conditionIndent(int operatorLength) {
            return Math.max(indent, clauseIndent + clause.length() - operatorLength);
        }

        private void reset() {
            clause = "";
            clauseIndent = 0;
            listIndent = indent;
            alignPending = false;
            joinClause = false;
            joinLine = -1;
            betweenPending = false;
        }
    }

    private static final class Output {
        private final StringBuilder text = new StringBuilder();
        private int lineStart;
        private int line;
        private boolean lineHasContent;

        /** 当前行是否只有缩进：缩进不算内容，否则每次换行后都会多出一个空格。 */
        private boolean lineEmpty() {
            return !lineHasContent;
        }

        private int column() {
            return text.length() - lineStart;
        }

        private int line() {
            return line;
        }

        private void append(String value) {
            text.append(value);
            lineHasContent = true;
        }

        private void newLine(int indent) {
            trimLineEnd();
            if (text.length() > 0) {
                text.append('\n');
                line++;
            }
            lineStart = text.length();
            text.append(" ".repeat(Math.max(0, indent)));
            lineHasContent = false;
        }

        private void blankLine() {
            trimLineEnd();
            if (text.length() > 0) {
                text.append("\n\n");
                line += 2;
            }
            lineStart = text.length();
            lineHasContent = false;
        }

        private void trimLineEnd() {
            while (text.length() > lineStart && text.charAt(text.length() - 1) == ' ') {
                text.setLength(text.length() - 1);
            }
        }

        private String finish() {
            trimLineEnd();
            return text.toString().strip();
        }
    }

    // ---------------------------------------------------------------- 间距规则

    private static boolean needsSpace(Token beforePrevious, Token previous, Token token, boolean previousUnary) {
        if (previous == null) return false;
        if (token.kind() == Kind.COMMA || token.kind() == Kind.SEMICOLON || token.kind() == Kind.DOT) return false;
        if (token.kind() == Kind.CLOSE) return false;
        if (previous.kind() == Kind.OPEN || previous.kind() == Kind.DOT) return false;
        if (previousUnary) return false;
        if ("::".equals(previous.text()) || "::".equals(token.text())) return false;
        if (token.kind() == Kind.OPEN) return spaceBeforeOpenParen(beforePrevious, previous);
        if (previous.kind() == Kind.OPERATOR || token.kind() == Kind.OPERATOR) return true;
        return true;
    }

    /** 函数名后面不留空格（nvl(dr, 0)），关键字与表名后面留空格（IN (SELECT …)、INSERT INTO t (a, b)）。 */
    private static boolean spaceBeforeOpenParen(Token beforePrevious, Token previous) {
        return switch (previous.kind()) {
            case WORD -> KEYWORDS.contains(previous.upper()) || previous.upper().indexOf(' ') >= 0
                    || isTableReference(beforePrevious);
            case OPERATOR, COMMA, SEMICOLON -> true;
            case CLOSE, LITERAL -> true;
            default -> false;
        };
    }

    private static final Set<String> TABLE_INTRODUCERS = Set.of("INTO", "INSERT INTO", "MERGE INTO", "TABLE");

    /** INSERT INTO / CREATE TABLE 后面的词是表名而不是函数名，括号前要留空格。 */
    private static boolean isTableReference(Token beforePrevious) {
        return beforePrevious != null && beforePrevious.kind() == Kind.WORD
                && TABLE_INTRODUCERS.contains(beforePrevious.upper());
    }

    private static boolean isUnary(Token previous, Token token, boolean previousUnary) {
        if (token.kind() != Kind.OPERATOR) return false;
        if (!"-".equals(token.text()) && !"+".equals(token.text()) && !"~".equals(token.text())) return false;
        if (previous == null) return true;
        return switch (previous.kind()) {
            case OPERATOR -> !previousUnary;
            case OPEN, COMMA, SEMICOLON -> true;
            case WORD -> KEYWORDS.contains(previous.upper());
            default -> false;
        };
    }

    private static String textOf(Token token) {
        if (token.kind() != Kind.WORD || !KEYWORDS.contains(token.upper())) return token.text();
        char first = token.text().charAt(0);
        // 带引号的标识符（"order"、`from`、[from]）大小写敏感，必须原样保留。
        if (first == '"' || first == '`' || first == '[') return token.text();
        return token.upper();
    }

    private static boolean startsSubquery(List<Token> tokens, int openIndex) {
        for (int scan = openIndex + 1; scan < tokens.size(); scan++) {
            Token token = tokens.get(scan);
            if (token.kind() == Kind.LINE_COMMENT || token.kind() == Kind.BLOCK_COMMENT) continue;
            if (token.kind() == Kind.OPEN) return startsSubquery(tokens, scan);
            return token.kind() == Kind.WORD && ("SELECT".equals(token.upper()) || "WITH".equals(token.upper()));
        }
        return false;
    }

    private static boolean containsProceduralBlock(List<Token> tokens) {
        for (Token token : tokens) {
            if (token.kind() == Kind.WORD && PROCEDURAL_KEYWORDS.contains(token.upper())) return true;
        }
        return false;
    }

    private static Set<String> firstWords(List<String> first, List<String> second) {
        Set<String> words = new HashSet<>();
        for (String phrase : first) words.add(phrase.split(" ")[0]);
        for (String phrase : second) words.add(phrase.split(" ")[0]);
        return Set.copyOf(words);
    }

    private static List<String> sortedByWords(List<String> phrases) {
        return phrases.stream()
                .sorted(Comparator.comparingInt((String phrase) -> phrase.split(" ").length).reversed()
                        .thenComparing(Comparator.naturalOrder()))
                .toList();
    }

    // ---------------------------------------------------------------- 词法切分

    private enum Kind { WORD, LITERAL, LINE_COMMENT, BLOCK_COMMENT, OPERATOR, COMMA, SEMICOLON, OPEN, CLOSE, DOT }

    private record Token(Kind kind, String text, String upper, boolean ownLine) {
    }

    private static final String[] MULTI_CHAR_OPERATORS = {
            "->>", "#>>", "<=>", "::", ":=", "<=", ">=", "<>", "!=", "||", "->", "#>", "**", "^=", "!<", "!>"
    };

    private List<Token> tokenize(String sql) {
        List<Token> tokens = new ArrayList<>();
        boolean ownLine = false;
        int index = 0;
        while (index < sql.length()) {
            char current = sql.charAt(index);
            if (Character.isWhitespace(current)) {
                if (current == '\n') ownLine = true;
                index++;
                continue;
            }
            int segmentEnd = protectedSegmentEnd(sql, index);
            if (segmentEnd > index) {
                String text = sql.substring(index, segmentEnd);
                Kind kind = segmentKind(text);
                if (kind == Kind.LINE_COMMENT) text = text.stripTrailing();
                tokens.add(newToken(kind, text, ownLine));
                ownLine = false;
                index = segmentEnd;
                continue;
            }
            if (Character.isDigit(current)) {
                int end = numberEnd(sql, index);
                tokens.add(newToken(Kind.LITERAL, sql.substring(index, end), ownLine));
                ownLine = false;
                index = end;
                continue;
            }
            if (isIdentifierStart(current) || (current == ':' && index + 1 < sql.length() && isIdentifierStart(sql.charAt(index + 1)))) {
                int end = current == ':' ? index + 1 : index;
                end++;
                while (end < sql.length() && isIdentifierPart(sql.charAt(end))) end++;
                tokens.add(newToken(Kind.WORD, sql.substring(index, end), ownLine));
                ownLine = false;
                index = end;
                continue;
            }
            Kind punctuation = switch (current) {
                case '(' -> Kind.OPEN;
                case ')' -> Kind.CLOSE;
                case ',' -> Kind.COMMA;
                case ';' -> Kind.SEMICOLON;
                case '.' -> Kind.DOT;
                default -> null;
            };
            if (punctuation != null) {
                tokens.add(newToken(punctuation, String.valueOf(current), ownLine));
                ownLine = false;
                index++;
                continue;
            }
            String operator = matchOperator(sql, index);
            tokens.add(newToken(Kind.OPERATOR, operator, ownLine));
            ownLine = false;
            index += operator.length();
        }
        return tokens;
    }

    private static Token newToken(Kind kind, String text, boolean ownLine) {
        return new Token(kind, text, text.toUpperCase(Locale.ROOT), ownLine);
    }

    private static String matchOperator(String sql, int index) {
        for (String operator : MULTI_CHAR_OPERATORS) {
            if (sql.startsWith(operator, index)) return operator;
        }
        return String.valueOf(sql.charAt(index));
    }

    private static int numberEnd(String sql, int index) {
        int end = index;
        while (end < sql.length() && (Character.isLetterOrDigit(sql.charAt(end)) || sql.charAt(end) == '.')) {
            char current = sql.charAt(end);
            boolean exponent = (current == 'e' || current == 'E')
                    && end + 2 < sql.length()
                    && (sql.charAt(end + 1) == '+' || sql.charAt(end + 1) == '-')
                    && Character.isDigit(sql.charAt(end + 2));
            end += exponent ? 2 : 1;
        }
        return end;
    }

    private static Kind segmentKind(String text) {
        if (text.startsWith("--")) return Kind.LINE_COMMENT;
        if (text.startsWith("/*")) return Kind.BLOCK_COMMENT;
        char first = text.charAt(0);
        return first == '"' || first == '`' || first == '[' ? Kind.WORD : Kind.LITERAL;
    }

    private static boolean isIdentifierStart(char value) {
        return Character.isLetter(value) || value == '_' || value == '#' || value == '@' || value == '$';
    }

    private static boolean isIdentifierPart(char value) {
        return Character.isLetterOrDigit(value) || value == '_' || value == '#' || value == '@' || value == '$';
    }

    /**
     * 返回从 index 开始的「整体保留」片段（字符串、引用标识符、注释）的结束位置，
     * 不是这类片段时返回 index 本身。
     */
    private int protectedSegmentEnd(String sql, int index) {
        char current = sql.charAt(index);
        char next = index + 1 < sql.length() ? sql.charAt(index + 1) : '\0';
        if (current == '-' && next == '-') return lineCommentEnd(sql, index + 2);
        if (current == '/' && next == '*') return blockCommentEnd(sql, index + 2);
        if ((current == 'q' || current == 'Q') && next == '\'' && index + 2 < sql.length()) {
            return oracleQuoteEnd(sql, index);
        }
        if (current == '$') {
            String delimiter = dollarQuoteDelimiter(sql, index);
            if (delimiter != null) {
                int closing = sql.indexOf(delimiter, index + delimiter.length());
                return closing < 0 ? sql.length() : closing + delimiter.length();
            }
        }
        return switch (current) {
            case '\'', '"', '`' -> quotedSegmentEnd(sql, index + 1, current);
            case '[' -> bracketSegmentEnd(sql, index + 1);
            default -> index;
        };
    }

    private int lineCommentEnd(String sql, int index) {
        while (index < sql.length() && sql.charAt(index) != '\n' && sql.charAt(index) != '\r') index++;
        if (index < sql.length() && sql.charAt(index) == '\r') index++;
        if (index < sql.length() && sql.charAt(index) == '\n') index++;
        return index;
    }

    private int blockCommentEnd(String sql, int index) {
        int depth = 1;
        while (index < sql.length()) {
            if (index + 1 < sql.length() && sql.charAt(index) == '/' && sql.charAt(index + 1) == '*') {
                depth++;
                index += 2;
            } else if (index + 1 < sql.length() && sql.charAt(index) == '*' && sql.charAt(index + 1) == '/') {
                depth--;
                index += 2;
                if (depth == 0) return index;
            } else {
                index++;
            }
        }
        return sql.length();
    }

    private int quotedSegmentEnd(String sql, int index, char quote) {
        while (index < sql.length()) {
            char current = sql.charAt(index);
            if (current == quote) {
                if (index + 1 < sql.length() && sql.charAt(index + 1) == quote) {
                    index += 2;
                    continue;
                }
                return index + 1;
            }
            if (current == '\\' && index + 1 < sql.length()) index += 2;
            else index++;
        }
        return sql.length();
    }

    private int bracketSegmentEnd(String sql, int index) {
        while (index < sql.length()) {
            if (sql.charAt(index) == ']') {
                if (index + 1 < sql.length() && sql.charAt(index + 1) == ']') {
                    index += 2;
                    continue;
                }
                return index + 1;
            }
            index++;
        }
        return sql.length();
    }

    private int oracleQuoteEnd(String sql, int index) {
        char opening = sql.charAt(index + 2);
        char closing = switch (opening) {
            case '[' -> ']';
            case '(' -> ')';
            case '{' -> '}';
            case '<' -> '>';
            default -> opening;
        };
        int closingIndex = sql.indexOf(String.valueOf(closing) + '\'', index + 3);
        return closingIndex < 0 ? sql.length() : closingIndex + 2;
    }

    private String dollarQuoteDelimiter(String sql, int index) {
        int end = sql.indexOf('$', index + 1);
        if (end < 0) return null;
        String tag = sql.substring(index + 1, end);
        if (!tag.isEmpty() && !tag.matches("[A-Za-z_][A-Za-z0-9_]*")) return null;
        return sql.substring(index, end + 1);
    }
}
