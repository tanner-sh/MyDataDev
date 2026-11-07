package com.example.dbadmin.service;

import com.example.dbadmin.core.DatabaseFamily;

import java.io.Reader;
import java.io.PushbackReader;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Shared streaming lexer: all SQL entry points must use the same execution boundaries. */
final class SqlScriptParser {
    record Unit(long index, String sql, long startOffset, long endOffset, int startLine, int endLine) { }
    /** 解析过程中顺带说明的事：被忽略的 SQL*Plus 指令、PROMPT 文本、EXIT 之后被跳过的内容。 */
    record Summary(List<String> notices) { }
    @FunctionalInterface interface UnitConsumer { void accept(Unit unit) throws Exception; }

    /**
     * 命令窗口要的不只是语句：PROMPT 要按它在脚本里的位置输出，WHENEVER SQLERROR 要在执行到那一行时
     * 才改变遇错策略。有监听器时这些指令按出现顺序报给它（夹在前后两条语句之间）；没有监听器时
     * 行为不变 —— 工作台与普通文件任务仍然拒绝 WHENEVER SQLERROR CONTINUE。
     */
    enum DirectiveKind { NOTICE, PROMPT, ON_ERROR_CONTINUE, ON_ERROR_EXIT }
    record Directive(DirectiveKind kind, String text, int line) { }
    @FunctionalInterface interface DirectiveListener { void accept(Directive directive) throws Exception; }

    static Summary read(Reader reader, String dbType, int maxChars, UnitConsumer consumer) throws Exception {
        return read(reader, dbType, maxChars, SqlScriptDelimiter.AUTO, consumer);
    }
    static Summary read(Reader reader, String dbType, int maxChars, SqlScriptDelimiter delimiterMode, UnitConsumer consumer) throws Exception {
        return read(reader, dbType, maxChars, delimiterMode, consumer, null);
    }
    static Summary read(Reader reader, String dbType, int maxChars, SqlScriptDelimiter delimiterMode, UnitConsumer consumer,
                        DirectiveListener directives) throws Exception {
        Parser parser = new Parser(dbType, maxChars, delimiterMode == null ? SqlScriptDelimiter.AUTO : delimiterMode, consumer);
        parser.directives = directives;
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
        return new Summary(List.copyOf(parser.notices));
    }
    static final class Parser {
        /** 这些 END 收的是 IF/LOOP/WHILE/REPEAT 语句，开头那个词本来就没计数，收尾也不该减。 */
        private static final Set<String> UNCOUNTED_END_SUFFIXES = Set.of("IF", "LOOP", "WHILE", "REPEAT");
        private static final Pattern MYSQL_COMPOUND_START = Pattern.compile(
                "CREATE (?:OR REPLACE )?(?:DEFINER ?= ?(?:CURRENT_USER(?: ?\\( ?\\))?|\\S+) )?(?:SQL SECURITY (?:DEFINER|INVOKER) )?"
                        + "(?:AGGREGATE )?(?:PROCEDURE|FUNCTION|TRIGGER|EVENT)\\b.*|BEGIN NOT ATOMIC\\b.*");
        private static final Pattern SQLITE_COMPOUND_START = Pattern.compile("CREATE (?:TEMP |TEMPORARY )?TRIGGER\\b.*");
        private static final Pattern PLSQL_END = Pattern.compile("(?is).*\\bEND(?:\\s+[\\p{L}\\p{N}_$#\"]+)?\\s*;");
        /**
         * PL/SQL 块（深度回到 0 且以 END …; 收尾）之后，下一行以这些词开头时，块一定已经结束了：
         * 它们不可能是块的声明部分（声明部分只会是变量、游标、类型、子程序或 BEGIN）。BEGIN 与
         * DECLARE 不在里面 —— 外层过程声明区里的嵌套子程序结束之后，紧跟的正是外层的 BEGIN。
         */
        private static final Set<String> TOP_LEVEL_STARTS = Set.of(
                "CREATE", "ALTER", "DROP", "INSERT", "UPDATE", "DELETE", "MERGE", "SELECT", "WITH", "GRANT", "REVOKE",
                "COMMENT", "TRUNCATE", "COMMIT", "ROLLBACK", "SAVEPOINT", "CALL", "EXEC", "EXECUTE", "RENAME", "ANALYZE",
                "LOCK", "PURGE", "FLASHBACK", "AUDIT", "NOAUDIT", "SET", "PROMPT", "PRO", "REM", "REMARK", "SPOOL", "SPO",
                "WHENEVER", "EXIT", "QUIT", "SHOW", "SHO", "DEFINE", "DEF", "UNDEFINE", "UNDEF", "COLUMN", "COL",
                "CLEAR", "CL", "TTITLE", "BTITLE", "BREAK", "COMPUTE");
        /** SQL*Plus 的显示类设置：对脚本执行没有影响，忽略并在结果里说明。不在清单里的 SET 当作 SQL（SET TRANSACTION、SET ROLE）。 */
        private static final Pattern SQLPLUS_SET = Pattern.compile(
                "SET\\s+(?:DEFINE|DEF|SERVEROUTPUT|SERVEROUT|ECHO|FEEDBACK|FEED|VERIFY|VER|TIMING|TIMI|LINESIZE|LIN|LINES|"
                        + "PAGESIZE|PAGES|HEADING|HEA|TERMOUT|TERM|TRIMSPOOL|TRIMS|TRIMOUT|TRIM|SQLBLANKLINES|SQLBL|SCAN|ESCAPE|ESC|"
                        + "LONG|LONGCHUNKSIZE|NUMWIDTH|NUMW|COLSEP|WRAP|TAB|TIME|AUTOPRINT|SHOWMODE|CONCAT|NULL|UNDERLINE|NEWPAGE|"
                        + "EMBEDDED|ARRAYSIZE|COPYCOMMIT|MARKUP|APPINFO|EXITCOMMIT|SQLPROMPT|SQLTERMINATOR|SQLT|HEADSEP|PAUSE|"
                        + "BLOCKTERMINATOR|BLO|CMDSEP|NUMFORMAT|RECSEP|RECSEPCHAR|SUFFIX|LOBOFFSET|FLUSH|SECUREDCOL|ERRORLOGGING|"
                        + "SQLCASE|STATEMENTCACHE|XQUERY|DESCRIBE|INSTANCE|EDITFILE|AUTOTRACE|AUTOT)\\b.*");
        private final String customDelimiter;
        private final boolean blankLineDelimiter;
        private final boolean sqlPlus;
        private final Set<String> notices = new LinkedHashSet<>();
        private DirectiveListener directives;
        private int plsqlDepth;
        private boolean blockComplete;
        /** blockComplete 成立时，块的代码在 statement 里结束的位置；其后的空白与注释属于下一条。 */
        private int blockEndLength;
        private boolean exited;
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
        /**
         * statement 里最后一个代码字符（字符串里的算，注释里的不算）的位置，由主词法顺带记下 —— q'[…]'、
         * 反斜杠转义、$$ 正文这些写法只有这里认得全，另写一个小扫描器去找末尾分号一定会在其中某种上判错。
         */
        private int lastCode = -1;
        private int iterationStart;
        private boolean iterationComment;
        /**
         * 包体的初始化段（{@code … END b; BEGIN init; END pkg;}）和包共用最后那个 END：包这一层直接出现的
         * BEGIN 不另开一层。要和子程序的 BEGIN 分开 —— 那个也出现在包这一层，但前面一定有
         * PROCEDURE/FUNCTION … IS|AS 的头，pendingBodies 数的就是「写了头、还没见到 BEGIN」的子程序。
         */
        private Boolean packageBody;
        private boolean subprogramHeader;
        private int pendingBodies;

        Parser(String dbType, int maxStatementChars, SqlScriptDelimiter delimiterMode, UnitConsumer consumer) {
            String type = dbType == null ? "" : dbType.trim().toLowerCase(Locale.ROOT);
            String family = DatabaseFamily.of(type);
            this.customDelimiter = delimiterMode.mode() == SqlScriptDelimiter.Mode.CUSTOM ? delimiterMode.token() : null;
            this.blankLineDelimiter = delimiterMode.mode() == SqlScriptDelimiter.Mode.BLANK_LINE;
            // 只对明确的 Oracle 系连接认 SQL*Plus 指令；"generic" 也走 Oracle 词法，但 MySQL 的 SHOW ERRORS
            // 这类真 SQL 不能被当成客户端指令吞掉。
            // 类型串到家族只认 DatabaseFamily 那一份映射。
            this.sqlPlus = family.equals("oracle");
            this.mysql = DatabaseFamily.isMySqlCompatible(type);
            this.backslashEscapes = mysql || family.equals("clickhouse");
            this.sqlServer = family.equals("sqlserver");
            this.oracle = sqlPlus || type.equals("generic");
            this.compoundStart = mysql ? MYSQL_COMPOUND_START : family.equals("sqlite") ? SQLITE_COMPOUND_START : null;
            this.maxStatementChars = Math.max(1, maxStatementChars);
            this.consumer = consumer;
        }

        void acceptLine(String line) throws Exception {
            String trimmed = line.trim();
            cursorOffset = 0;
            if (exited) {
                advance(line);
                return;
            }
            if (customDelimiter != null) {
                // 自定义分隔符：只有独占一行的分隔符是边界，分号、/、GO、DELIMITER 一律不再起作用。
                if (cleanState() && trimmed.equals(customDelimiter)) {
                    emitWithoutTrailingSemicolon();
                    advance(line);
                    return;
                }
            } else if (blankLineDelimiter && trimmed.isEmpty() && cleanState() && statementStarted && !blankLineInsideBlock()) {
                emitWithoutTrailingSemicolon();
                advance(line);
                return;
            }
            if (customDelimiter == null && cleanState()) {
                // 上一个 PL/SQL 块已经完整（深度回到 0、以 END …; 收尾），下一行又是一条新语句的开头：
                // 没写 / 也能确定块在这里结束。SQL*Plus 要求 /，但 PL/SQL Developer、DBeaver 导出的脚本经常不写。
                if (oracle && oracleBlock && blockComplete && startsTopLevelStatement(trimmed)) {
                    emitCompletedBlock();
                }
            }
            if (customDelimiter == null && cleanState()) {
                if (mysql && !statementStarted && trimmed.toUpperCase(Locale.ROOT).startsWith("DELIMITER ")) {
                    String next = trimmed.substring("DELIMITER".length()).trim();
                    if (next.isBlank() || next.length() > 16 || next.chars().anyMatch(Character::isWhitespace)) {
                        throw new IllegalArgumentException("MySQL DELIMITER 指令不合法。");
                    }
                    delimiter = next;
                    statement.setLength(0);
                    lastCode = -1;
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
                // PL/SQL 块只在独占行 / 或文件末尾结束。上一块漏了 / 时，下一条 CREATE 会被并进同一个执行单元，
                // 有的库只编译前一个对象就返回成功，后一个对象悄无声息地没建。例程正文里不可能出现顶层 CREATE，
                // 所以 END; 之后紧跟的 CREATE 一定是漏写了分隔符：宁可整份脚本在执行前被拒，也不要静默少建一个对象。
                if (oracle && oracleBlock && trimmed.matches("(?is)CREATE\\b.*")
                        && PLSQL_END.matcher(codeTail.toString().stripTrailing()).matches()) {
                    throw failure("上一个 PL/SQL 块缺少独占行 /，且无法确认它在哪里结束（BEGIN 与 END 没有配平），下一条 CREATE 会被并入同一条语句");
                }
            }

            if (!blockDetected && cleanState() && !statementStarted) {
                if (sqlPlus) {
                    if (sqlPlusCommand(line, trimmed)) {
                        advance(line);
                        return;
                    }
                } else if (trimmed.matches("(?i)(PROMPT|SPOOL|WHENEVER|SET SERVEROUTPUT|@@?)\\b.*")) {
                    throw failure("暂不支持该客户端指令");
                }
            }
            boolean lineComment = false;
            for (int cursor = 0; cursor < line.length(); cursor++, markCode()) {
                cursorOffset = cursor;
                iterationStart = statement.length();
                iterationComment = lineComment || blockComment;
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

                if (compoundStart != null || oracle) trackCompoundBlock(current, next);
                if (oracle && !(current == '-' && next == '-') && !(current == '/' && next == '*')) {
                    // Track only lexical code, so '--' inside a quoted value cannot hide the real END;.
                    boolean quoteStart = current == '\'' || current == '"' || current == '`' || current == '['
                            || ((current == 'q' || current == 'Q') && next == '\'') || current == '$';
                    codeTail.append(quoteStart ? 'Q' : current);
                    if (codeTail.length() > 256) codeTail.deleteCharAt(0);
                }
                if (!Character.isWhitespace(current) && !(current == '-' && next == '-') && !(current == '/' && next == '*')) {
                    statementStarted = true;
                    blockComplete = false;
                }
                if (current == '-' && next == '-') {
                    if (oracle) codeTail.append(' ');
                    iterationComment = true;
                    append(next);
                    cursor++;
                    lineComment = true;
                } else if (current == '/' && next == '*') {
                    if (oracle) codeTail.append(' ');
                    iterationComment = true;
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

                if (current == ';' && cleanState() && oracle && customDelimiter == null) {
                    if (!blockDetected) {
                        String prefix = SqlScriptSyntax.leadingSql(statement.toString());
                        oracleBlock = isOracleBlockStart(prefix);
                        blockDetected = true;
                        if (oracleBlock) plsqlDepth += plsqlBaseDepth(prefix);
                    }
                    if (oracleBlock) {
                        String prefix = normalizedPrefix();
                        blockComplete = isTypeSpec(prefix)
                                || (plsqlDepth <= 0 && PLSQL_END.matcher(codeTail.toString().stripTrailing()).matches());
                        blockEndLength = statement.length();
                    }
                }
                if (customDelimiter == null && cleanState() && !sqlServer && !(oracle && oracleBlock) && endsWithDelimiter() && !insideCompoundBlock()) {
                    statement.setLength(statement.length() - delimiter.length());
                    emit();
                }
            }
            advance(line);
        }

        void finish() throws Exception {
            if (!cleanState()) throw failure("SQL 存在未闭合的字符串或注释。");
            if (exited) return;
            if (customDelimiter != null) {
                // 最后一条后面常常不再写分隔符。
                emitWithoutTrailingSemicolon();
                return;
            }
            validateBlockEnd();
            if (blankLineDelimiter) emitWithoutTrailingSemicolon();
            else emit();
        }

        /**
         * 自定义分隔符与空行分隔下，语句末尾的分号是可写可不写的。Oracle JDBC 执行带分号的普通语句会报
         * ORA-00911，所以去掉；但 PL/SQL 块的 END; 是语法的一部分，必须留着。
         */
        private void emitWithoutTrailingSemicolon() throws Exception {
            String raw = statement.toString();
            boolean plsqlBlock = oracle && isOracleBlockStart(SqlScriptSyntax.leadingSql(raw));
            if (!plsqlBlock && lastCode >= 0 && lastCode < raw.length() && raw.charAt(lastCode) == ';') {
                statement.deleteCharAt(lastCode);
            }
            emit();
        }

        /** 每处理完一个字符：这一轮追加进 statement 的不是注释、也不全是空白，就推进 lastCode。 */
        private void markCode() {
            if (iterationComment) return;
            for (int i = statement.length() - 1; i >= iterationStart && i >= 0; i--) {
                if (!Character.isWhitespace(statement.charAt(i))) {
                    lastCode = i;
                    return;
                }
            }
        }

        /** 块之后、下一条语句之前的注释（「-- 第 2 条」这类）跟着下一条走，不粘在过程源码的末尾。 */
        private void emitCompletedBlock() throws Exception {
            String trailing = statement.substring(Math.min(blockEndLength, statement.length()));
            long trailingOffset = statementOffset + blockEndLength;
            int trailingLine = statementLine + countLines(statement.substring(0, Math.min(blockEndLength, statement.length())));
            statement.setLength(Math.min(blockEndLength, statement.length()));
            emit();
            if (!trailing.isEmpty()) {
                statement.append(trailing);
                statementOffset = trailingOffset;
                statementLine = trailingLine;
            }
        }

        /** 空行分隔是「聪明」的那种：PL/SQL 块、MySQL/SQLite 例程、SQL Server 例程内部的空行不算边界。 */
        private boolean blankLineInsideBlock() {
            String prefix = SqlScriptSyntax.leadingSql(statement.toString());
            if (oracle && isOracleBlockStart(prefix)) return true;
            String normalized = prefix.replaceAll("(?s)/\\*.*?\\*/|--[^\\r\\n]*", " ").toUpperCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
            if (compoundStart != null && compoundStart.matcher(normalized).matches()) return true;
            return sqlServer && normalized.matches("(?:CREATE(?: OR ALTER)?|ALTER) (?:PROC(?:EDURE)?|FUNCTION|TRIGGER)\\b.*");
        }

        private String normalizedPrefix() {
            return SqlScriptSyntax.leadingSql(statement.toString()).replaceAll("(?s)/\\*.*?\\*/|--[^\\r\\n]*", " ")
                    .toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
        }

        private static boolean isTypeSpec(String normalizedPrefix) {
            return normalizedPrefix.matches("CREATE( OR REPLACE)?( (?:NON)?EDITIONABLE)? TYPE (?!BODY\\b).*\\b(?:OBJECT|TABLE|VARRAY)\\b.*");
        }

        /** 包、类型体、复合触发器最外层没有 BEGIN，最后那个 END 收的是对象本身，所以从第 1 层算起。 */
        private int plsqlBaseDepth(String prefix) {
            String normalized = prefix.replaceAll("(?s)/\\*.*?\\*/|--[^\\r\\n]*", " ").toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
            if (normalized.matches("CREATE( OR REPLACE)?( (?:NON)?EDITIONABLE)? (?:PACKAGE|TYPE BODY)\\b.*")) return 1;
            return normalized.matches("CREATE( OR REPLACE)?( (?:NON)?EDITIONABLE)? TRIGGER\\b.*\\bCOMPOUND TRIGGER\\b.*") ? 1 : 0;
        }

        private boolean startsTopLevelStatement(String trimmed) {
            if (trimmed.isEmpty() || trimmed.equals("/")) return false;
            if (trimmed.startsWith("@")) return true;
            int end = 0;
            while (end < trimmed.length() && (Character.isLetter(trimmed.charAt(end)))) end++;
            return end > 0 && TOP_LEVEL_STARTS.contains(trimmed.substring(0, end).toUpperCase(Locale.ROOT));
        }

        /**
         * SQL*Plus 客户端指令。它们不是 SQL，不能发给数据库；按行生效，分号可写可不写。
         *
         * @return true 表示这一行已经处理掉（忽略或换成了等价的 SQL）
         */
        private boolean sqlPlusCommand(String line, String trimmed) throws Exception {
            String command = trimmed.endsWith(";") ? trimmed.substring(0, trimmed.length() - 1).trim() : trimmed;
            String upper = command.toUpperCase(Locale.ROOT);
            if (upper.isEmpty()) return false;
            if (upper.startsWith("@") || upper.matches("START\\s+\\S+.*")) {
                throw failure("不支持用 @ / START 引用其他脚本文件，请把内容合并进当前脚本");
            }
            if (upper.matches("CONN(?:ECT)?(?:\\s.*)?") || upper.matches("DISC(?:ONNECT)?")) throw failure("不支持在脚本里切换连接（CONNECT）");
            if (upper.matches("HOST(?:\\s.*)?")) throw failure("不支持在脚本里执行操作系统命令（HOST）");
            if (upper.matches("(?:ACC(?:EPT)?|PAU(?:SE)?|VAR(?:IABLE)?|PRI(?:NT)?)(?:\\s.*)?")) {
                throw failure("不支持需要交互或绑定变量的 SQL*Plus 指令：" + command.split("\\s+")[0]);
            }
            if (upper.matches("WHENEVER\\s+SQLERROR\\s+CONTINUE\\b.*")) {
                if (directives == null) {
                    throw failure("WHENEVER SQLERROR CONTINUE（遇错继续）只在命令窗口里支持；当前遇到错误会停在出错的那一条");
                }
                directives.accept(new Directive(DirectiveKind.ON_ERROR_CONTINUE, command, lineNumber));
                return true;
            }
            if (directives != null && upper.matches("WHENEVER\\s+SQLERROR\\s+EXIT\\b.*")) {
                directives.accept(new Directive(DirectiveKind.ON_ERROR_EXIT, command, lineNumber));
                return true;
            }
            if (upper.matches("(?:EXIT|QUIT)\\b.*")) {
                exited = true;
                notice("遇到 " + command + "：其后的内容按 SQL*Plus 的规则不再执行");
                return true;
            }
            if (upper.matches("REM(?:ARK)?(?:\\s.*)?")) return true;
            if (upper.matches("PRO(?:MPT)?(?:\\s.*)?")) {
                String text = command.replaceFirst("(?i)^PRO(?:MPT)?\\s*", "");
                notice("PROMPT " + (text.length() > 200 ? text.substring(0, 200) + "…" : text));
                return true;
            }
            if (SQLPLUS_SET.matcher(upper).matches()) {
                notice(upper.matches("SET\\s+SERVEROUT(?:PUT)?\\b.*")
                        ? "已忽略 " + command + "：SQL 工作台暂不显示 DBMS_OUTPUT"
                        : "已忽略 SQL*Plus 设置：" + command);
                return true;
            }
            if (upper.matches("WHENEVER\\s+(?:SQLERROR|OSERROR)\\b.*")) {
                notice("已忽略 " + command + "：遇到错误本来就会停止执行");
                return true;
            }
            if (upper.matches("(?:SPO(?:OL)?|DEF(?:INE)?|UNDEF(?:INE)?|COL(?:UMN)?|CL(?:EAR)?|TTI(?:TLE)?|BTI(?:TLE)?|BRE(?:AK)?|COMP(?:UTE)?)(?:\\s.*)?")
                    || upper.matches("SHO(?:W)?\\s+ERR(?:ORS)?\\b.*")) {
                notice("已忽略 SQL*Plus 指令：" + command + (upper.startsWith("DEF") ? "（本工具不做 & 变量替换）" : ""));
                return true;
            }
            if (upper.matches("EXEC(?:UTE)?\\s+.+")) {
                // EXEC proc(args) 是 SQL*Plus 对 BEGIN proc(args); END; 的简写。
                String call = command.replaceFirst("(?i)^EXEC(?:UTE)?\\s+", "");
                emitSynthetic("BEGIN " + call + "; END;", line);
                return true;
            }
            return false;
        }

        private void notice(String text) throws Exception {
            if (notices.size() < 100) notices.add(text);
            if (directives != null) {
                boolean prompt = text.startsWith("PROMPT ");
                directives.accept(new Directive(prompt ? DirectiveKind.PROMPT : DirectiveKind.NOTICE,
                        prompt ? text.substring("PROMPT ".length()) : text, lineNumber));
            }
        }

        /** 换写成另一条 SQL 的指令行：偏移与行号仍指向源文件里的那一行，报错时能定位回去。 */
        private void emitSynthetic(String sql, String line) throws Exception {
            int leading = line.length() - line.stripLeading().length();
            long start = sourceOffset + leading;
            statement.setLength(0);
            codeTail.setLength(0);
            lastCode = -1;
            consumer.accept(new Unit(++index, sql, start, start + line.strip().length(), lineNumber, lineNumber));
        }

        private void validateBlockEnd() {
            if (!oracleBlock) return;
            String sql = codeTail.toString().stripTrailing();
            boolean typeSpec = isTypeSpec(normalizedPrefix());
            if (!typeSpec && !PLSQL_END.matcher(sql).matches()) {
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
            lastCode = -1;
            packageBody = null;
            subprogramHeader = false;
            pendingBodies = 0;
            statementStarted = false;
            oracleBlock = false;
            blockDetected = false;
            word.setLength(0);
            compoundDepth = 0;
            plsqlDepth = 0;
            blockComplete = false;
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
            // PROCEDURE a; 是前向声明，没有正文。
            if (current == ';') subprogramHeader = false;
            boolean commentStart = (current == '-' && next == '-') || (current == '/' && next == '*');
            if (pendingEnd && !Character.isWhitespace(current) && !commentStart) {
                pendingEnd = false;
                compoundDepth = Math.max(0, compoundDepth - 1);
                plsqlDepth--;
            }
        }

        private void flushCompoundWord() {
            if (word.isEmpty()) return;
            String value = word.toString().toUpperCase(Locale.ROOT);
            word.setLength(0);
            if (pendingEnd) {
                pendingEnd = false;
                if (!UNCOUNTED_END_SUFFIXES.contains(value)) {
                    compoundDepth = Math.max(0, compoundDepth - 1);
                    plsqlDepth--;
                }
            } else if (value.equals("BEGIN") && oracle && isPackageBody()) {
                // 包这一层的深度：基础的那 1 层要到首个分号才加上（见 plsqlBaseDepth），之前是 0。
                int packageLevel = blockDetected && oracleBlock ? 1 : 0;
                if (pendingBodies > 0) pendingBodies--;
                else if (plsqlDepth == packageLevel) return; // 初始化段
                compoundDepth++;
                plsqlDepth++;
            } else if (value.equals("BEGIN") || value.equals("CASE")) {
                compoundDepth++;
                plsqlDepth++;
            } else if ((value.equals("PROCEDURE") || value.equals("FUNCTION")) && oracle && isPackageBody()) {
                subprogramHeader = true;
            } else if ((value.equals("IS") || value.equals("AS")) && subprogramHeader) {
                subprogramHeader = false;
                pendingBodies++;
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

        private boolean isPackageBody() {
            if (packageBody == null) {
                packageBody = normalizedPrefix().trim().matches("CREATE( OR REPLACE)?( (?:NON)?EDITIONABLE)? PACKAGE BODY\\b.*");
            }
            return packageBody;
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
