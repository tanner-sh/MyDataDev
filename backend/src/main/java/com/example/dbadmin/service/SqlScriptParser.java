package com.example.dbadmin.service;

import java.io.Reader;
import java.io.PushbackReader;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Shared streaming lexer: all SQL entry points must use the same execution boundaries. */
final class SqlScriptParser {
    record Unit(long index, String sql, long startOffset, long endOffset, int startLine, int endLine) { }
    @FunctionalInterface interface UnitConsumer { void accept(Unit unit) throws Exception; }
    static void read(Reader reader, String dbType, int maxChars, UnitConsumer consumer) throws Exception {
        Parser parser = new Parser(dbType, maxChars, consumer);
        StringBuilder line = new StringBuilder();
        PushbackReader input = new PushbackReader(reader, 1);
        int c;
        boolean first = true;
        while ((c = input.read()) != -1) {
            if (first && c == '\ufeff') { first = false; parser.sourceOffset++; continue; }
            first = false;
            line.append((char) c);
            if (line.length() > maxChars) throw new IllegalArgumentException("单行 SQL 超过允许大小。");
            if (c == '\r') {
                int next = input.read();
                if (next == '\n') line.append((char) next);
                else if (next != -1) input.unread(next);
            }
            if (c == '\n' || c == '\r') { parser.acceptLine(line.toString()); line.setLength(0); }
        }
        if (!line.isEmpty()) parser.acceptLine(line.toString());
        parser.finish();
    }
    static final class Parser {
        /** 这些 END 收的是 IF/LOOP/WHILE/REPEAT 语句，开头那个词本来就没计数，收尾也不该减。 */
        private static final Set<String> UNCOUNTED_END_SUFFIXES = Set.of("IF", "LOOP", "WHILE", "REPEAT");
        private static final Pattern MYSQL_COMPOUND_START = Pattern.compile(
                "CREATE (?:OR REPLACE )?(?:DEFINER ?= ?(?:CURRENT_USER(?: ?\\( ?\\))?|\\S+) )?(?:SQL SECURITY (?:DEFINER|INVOKER) )?"
                        + "(?:AGGREGATE )?(?:PROCEDURE|FUNCTION|TRIGGER|EVENT)\\b.*|BEGIN NOT ATOMIC\\b.*");
        private static final Pattern SQLITE_COMPOUND_START = Pattern.compile("CREATE (?:TEMP |TEMPORARY )?TRIGGER\\b.*");
        private final boolean mysql;
        private final boolean backslashEscapes;
        private final boolean sqlServer;
        private final boolean oracle;
        private final Pattern compoundStart;
        private final int maxStatementChars;
        private final UnitConsumer consumer;
        private final StringBuilder statement = new StringBuilder();
        private final StringBuilder codeTail = new StringBuilder();
        private String delimiter = ";";
        private String dollarQuote;
        private char oracleQuoteEnd;
        private boolean single;
        private boolean doubleQuoted;
        private boolean backtick;
        private boolean bracket;
        private boolean blockComment;
        private boolean oracleBlock;
        private boolean statementStarted;
        private long index;
        private long sourceOffset;
        private long statementOffset;
        private int lineNumber = 1;
        private int statementLine = 1;
        private int cursorOffset;
        private boolean blockDetected;
        // MySQL 系与 SQLite 的 BEGIN … END 复合块：块内的分号属于例程正文，不是语句边界。
        private final StringBuilder word = new StringBuilder();
        private int compoundDepth;
        private boolean pendingEnd;
        private Boolean compoundStatement;

        Parser(String dbType, int maxStatementChars, UnitConsumer consumer) {
            String type = dbType == null ? "" : dbType.toLowerCase(Locale.ROOT);
            this.mysql = type.equals("mysql") || type.equals("mariadb") || type.equals("oceanbase-mysql");
            this.backslashEscapes = mysql || type.equals("clickhouse");
            this.sqlServer = type.equals("sqlserver") || type.equals("sql-server") || type.equals("mssql");
            this.oracle = type.equals("generic") || type.equals("oracle") || type.equals("dm") || type.equals("dameng") || type.equals("oceanbase-oracle");
            this.compoundStart = mysql ? MYSQL_COMPOUND_START : type.equals("sqlite") ? SQLITE_COMPOUND_START : null;
            this.maxStatementChars = Math.max(1, maxStatementChars);
            this.consumer = consumer;
        }

        void acceptLine(String line) throws Exception {
            String trimmed = line.trim();
            cursorOffset = 0;
            if (cleanState()) {
                if (mysql && !statementStarted && trimmed.toUpperCase(Locale.ROOT).startsWith("DELIMITER ")) {
                    String next = trimmed.substring("DELIMITER".length()).trim();
                    if (next.isBlank() || next.length() > 16 || next.chars().anyMatch(Character::isWhitespace)) {
                        throw new IllegalArgumentException("MySQL DELIMITER 指令不合法。");
                    }
                    delimiter = next;
                    statement.setLength(0);
                    advance(line);
                    return;
                }
                if (sqlServer && trimmed.equalsIgnoreCase("GO")) {
                    emit();
                    advance(line);
                    return;
                }
                if (sqlServer && trimmed.toUpperCase(Locale.ROOT).matches("GO\\s+\\d+")) {
                    throw new IllegalArgumentException("暂不支持带重复次数的 SQL Server GO 指令。");
                }
                if (oracle && trimmed.equals("/")) {
                    validateBlockEnd();
                    emit();
                    oracleBlock = false;
                    advance(line);
                    return;
                }
            }

            if (!blockDetected && cleanState() && !statementStarted && trimmed.matches("(?i)(PROMPT|SPOOL|WHENEVER|SET SERVEROUTPUT|@@?)\\b.*")) {
                throw failure("暂不支持该客户端指令");
            }
            boolean lineComment = false;
            for (int cursor = 0; cursor < line.length(); cursor++) {
                cursorOffset = cursor;
                char current = line.charAt(cursor);
                char next = cursor + 1 < line.length() ? line.charAt(cursor + 1) : '\0';
                append(current);
                if (lineComment) continue;
                if (dollarQuote != null) {
                    if (line.startsWith(dollarQuote, cursor)) {
                        for (int extra = 1; extra < dollarQuote.length(); extra++) append(line.charAt(cursor + extra));
                        cursor += dollarQuote.length() - 1;
                        dollarQuote = null;
                    }
                    continue;
                }
                if (oracleQuoteEnd != '\0') {
                    if (current == oracleQuoteEnd && next == '\'') {
                        append(next);
                        cursor++;
                        oracleQuoteEnd = '\0';
                    }
                    continue;
                }
                if (blockComment) {
                    if (current == '*' && next == '/') {
                        append(next);
                        cursor++;
                        blockComment = false;
                    }
                    continue;
                }
                if (single) {
                    if (current == '\'' && next == '\'') {
                        append(next);
                        cursor++;
                    } else if (current == '\'' && !escapedByBackslash(line, cursor)) single = false;
                    continue;
                }
                if (doubleQuoted) {
                    if (current == '"' && next == '"') {
                        append(next);
                        cursor++;
                    } else if (current == '"') doubleQuoted = false;
                    continue;
                }
                if (backtick) {
                    if (current == '`' && next == '`') {
                        append(next);
                        cursor++;
                    } else if (current == '`') backtick = false;
                    continue;
                }
                if (bracket) {
                    if (current == ']' && next == ']') {
                        append(next);
                        cursor++;
                    } else if (current == ']') bracket = false;
                    continue;
                }

                if (compoundStart != null) trackCompoundBlock(current, next);
                if (oracle && !(current == '-' && next == '-') && !(current == '/' && next == '*')) {
                    // Track only lexical code, so '--' inside a quoted value cannot hide the real END;.
                    boolean quoteStart = current == '\'' || current == '"' || current == '`' || current == '['
                            || ((current == 'q' || current == 'Q') && next == '\'') || current == '$';
                    codeTail.append(quoteStart ? 'Q' : current);
                    if (codeTail.length() > 256) codeTail.deleteCharAt(0);
                }
                if (!Character.isWhitespace(current) && !(current == '-' && next == '-') && !(current == '/' && next == '*')) statementStarted = true;
                if (current == '-' && next == '-') {
                    if (oracle) codeTail.append(' ');
                    append(next);
                    cursor++;
                    lineComment = true;
                } else if (current == '/' && next == '*') {
                    if (oracle) codeTail.append(' ');
                    append(next);
                    cursor++;
                    blockComment = true;
                } else if (current == '\'') single = true;
                else if (current == '"') doubleQuoted = true;
                else if (current == '`') backtick = true;
                else if (current == '[') bracket = true;
                else if ((current == 'q' || current == 'Q') && next == '\'' && cursor + 2 < line.length()) {
                    append(next);
                    append(line.charAt(cursor + 2));
                    oracleQuoteEnd = matchingOracleQuote(line.charAt(cursor + 2));
                    cursor += 2;
                } else if (current == '$' && !mysql) {
                    String tag = dollarDelimiter(line, cursor);
                    if (tag != null) {
                        for (int extra = 1; extra < tag.length(); extra++) append(line.charAt(cursor + extra));
                        cursor += tag.length() - 1;
                        dollarQuote = tag;
                    }
                }

                if (current == ';' && cleanState() && oracle && !blockDetected) {
                    oracleBlock = isOracleBlockStart(SqlScriptSyntax.leadingSql(statement.toString()));
                    blockDetected = true;
                }
                if (cleanState() && !sqlServer && !(oracle && oracleBlock) && endsWithDelimiter() && !insideCompoundBlock()) {
                    statement.setLength(statement.length() - delimiter.length());
                    emit();
                }
            }
            advance(line);
        }

        void finish() throws Exception {
            if (!cleanState()) throw failure("SQL 存在未闭合的字符串或注释。");
            validateBlockEnd();
            emit();
        }

        private void validateBlockEnd() {
            if (!oracleBlock) return;
            String sql = codeTail.toString().stripTrailing();
            String prefix = SqlScriptSyntax.leadingSql(statement.toString()).replaceAll("(?s)/\\*.*?\\*/|--[^\\r\\n]*", " ").toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
            boolean typeSpec = prefix.matches("CREATE( OR REPLACE)?( (?:NON)?EDITIONABLE)? TYPE (?!BODY\\b).*\\b(?:OBJECT|TABLE|VARRAY)\\b.*");
            if (!typeSpec && !sql.matches("(?is).*\\bEND(?:\\s+[\\p{L}\\p{N}_$#\"]+)?\\s*;")) {
                throw failure("PL/SQL 块未完整结束，需要 END; 和独占行 /");
            }
        }

        private IllegalArgumentException failure(String message) {
            return new IllegalArgumentException("第 " + lineNumber + " 行：" + message);
        }

        private void advance(String line) {
            sourceOffset += line.length();
            lineNumber += countLines(line);
        }

        private int countLines(String text) {
            int count = 0;
            for (int i = 0; i < text.length(); i++) {
                if (text.charAt(i) == '\r') {
                    count++;
                    if (i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                } else if (text.charAt(i) == '\n') count++;
            }
            return count;
        }

        /** A quote is escaped only after an odd run of backslashes, and only in dialects that support it. */
        private boolean escapedByBackslash(String line, int cursor) {
            if (!backslashEscapes) return false;
            int count = 0;
            for (int index = cursor - 1; index >= 0 && line.charAt(index) == '\\'; index--) count++;
            return (count & 1) == 1;
        }

        private boolean cleanState() {
            return !single && !doubleQuoted && !backtick && !bracket && !blockComment && dollarQuote == null && oracleQuoteEnd == '\0';
        }

        private boolean endsWithDelimiter() {
            if (statement.length() < delimiter.length()) return false;
            for (int i = 0; i < delimiter.length(); i++) {
                if (statement.charAt(statement.length() - delimiter.length() + i) != delimiter.charAt(i)) return false;
            }
            return true;
        }

        private void emit() throws Exception {
            String raw = statement.toString();
            String sql = raw.trim();
            int leading = raw.length() - raw.stripLeading().length();
            int trailing = raw.stripTrailing().length();
            statement.setLength(0);
            codeTail.setLength(0);
            statementStarted = false;
            oracleBlock = false;
            blockDetected = false;
            word.setLength(0);
            compoundDepth = 0;
            pendingEnd = false;
            compoundStatement = null;
            if (sql.isBlank() || SqlScriptSyntax.leadingSql(sql).isBlank()) return;
            int startLine = statementLine + countLines(raw.substring(0, leading));
            int endLine = statementLine + countLines(raw.substring(0, trailing));
            consumer.accept(new Unit(++index, sql, statementOffset + leading, statementOffset + trailing, startLine, endLine));
        }

        private void append(char value) {
            if (statement.isEmpty()) { statementOffset = sourceOffset + cursorOffset; statementLine = lineNumber; }
            statement.append(value);
            // A comment is not the start of SQL. Block recognition uses the complete prefix at the first semicolon.
            if (statement.length() > maxStatementChars) throw failure("单条 SQL 超过允许大小（" + maxStatementChars + " 字符）。");
        }

        /**
         * 只数 BEGIN 与 CASE 两种开块词：IF/LOOP/WHILE/REPEAT 语句一定写在某个 BEGIN 里面，靠外层就够了。
         * END 要看下一个记号才知道收的是什么 —— END IF 不减，END CASE、END 标签、END; 都减一层；
         * 中间隔着注释不算下一个记号。
         */
        private void trackCompoundBlock(char current, char next) {
            if (Character.isLetterOrDigit(current) || current == '_' || current == '$') {
                word.append(current);
                return;
            }
            flushCompoundWord();
            boolean commentStart = (current == '-' && next == '-') || (current == '/' && next == '*');
            if (pendingEnd && !Character.isWhitespace(current) && !commentStart) {
                pendingEnd = false;
                compoundDepth = Math.max(0, compoundDepth - 1);
            }
        }

        private void flushCompoundWord() {
            if (word.isEmpty()) return;
            String value = word.toString().toUpperCase(Locale.ROOT);
            word.setLength(0);
            if (pendingEnd) {
                pendingEnd = false;
                if (!UNCOUNTED_END_SUFFIXES.contains(value)) compoundDepth = Math.max(0, compoundDepth - 1);
            } else if (value.equals("BEGIN") || value.equals("CASE")) {
                compoundDepth++;
            } else if (value.equals("END")) {
                pendingEnd = true;
            }
        }

        /** 只在语句本身是例程/触发器定义时才认块，顶层的 BEGIN; 是开事务，不能把后面的语句吞进来。只在碰到分隔符时调用：判定结果按语句缓存，前缀没读完就判会判错。 */
        private boolean insideCompoundBlock() {
            if (compoundStart == null || compoundDepth == 0 || !delimiter.equals(";")) return false;
            if (compoundStatement == null) {
                String normalized = SqlScriptSyntax.leadingSql(statement.toString())
                        .replaceAll("(?s)/\\*.*?\\*/|--[^\\r\\n]*", " ").toUpperCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
                compoundStatement = compoundStart.matcher(normalized).matches();
            }
            return compoundStatement;
        }

        private boolean isOracleBlockStart(String value) {
            String normalized = value.replaceAll("(?s)/\\*.*?\\*/|--[^\\r\\n]*", " ").toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
            if (normalized.matches("(?s)BEGIN (?:WORK|TRAN|TRANSACTION)\\b.*") || normalized.startsWith("DECLARE @")) return false;
            return normalized.equals("BEGIN") || normalized.startsWith("BEGIN ") || normalized.equals("DECLARE")
                    || normalized.startsWith("DECLARE ")
                    || normalized.matches("CREATE( OR REPLACE)?( (?:NON)?EDITIONABLE)? (PROCEDURE|FUNCTION|PACKAGE|TRIGGER|TYPE)\\b.*");
        }

        private char matchingOracleQuote(char opening) {
            return switch (opening) { case '[' -> ']'; case '{' -> '}'; case '(' -> ')'; case '<' -> '>'; default -> opening; };
        }

        private String dollarDelimiter(String line, int start) {
            int end = start + 1;
            while (end < line.length() && (Character.isLetterOrDigit(line.charAt(end)) || line.charAt(end) == '_')) end++;
            if (end >= line.length() || line.charAt(end) != '$') return null;
            String tag = line.substring(start + 1, end);
            return tag.isEmpty() || Character.isLetter(tag.charAt(0)) || tag.charAt(0) == '_' ? line.substring(start, end + 1) : null;
        }
    }

}
