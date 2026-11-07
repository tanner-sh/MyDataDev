package com.example.dbadmin.service;

import com.example.dbadmin.core.DatabaseFamily;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 脚本的语句分隔方式。
 *
 * <ul>
 *   <li>{@link Mode#AUTO}：分号，加上各库自己的块规则（Oracle 系 PL/SQL 块与独占行 /、MySQL 的
 *   DELIMITER 与 BEGIN…END、SQL Server 的 GO）。这是默认，也是此前唯一的方式。</li>
 *   <li>{@link Mode#CUSTOM}：只有独占一行的指定符号才是边界，分号不再分段 —— DBeaver「自定义语句
 *   分隔符」导出的脚本就是这样，普通语句甚至不写分号。</li>
 *   <li>{@link Mode#BLANK_LINE}：在 AUTO 之上，空行也是边界（DBeaver「空行作为语句分隔」）。
 *   PL/SQL 块与例程定义内部的空行不算 —— 否则任何一个带空行的存储过程都会被切碎。</li>
 * </ul>
 *
 * <p>后台 SQL 文件任务要把它存进 {@code sql_file_execution.statement_delimiter}，分析和执行必须用
 * 同一套边界，所以这里同时给出持久化的编码。</p>
 */
public record SqlScriptDelimiter(Mode mode, String token) {
    public enum Mode { AUTO, CUSTOM, BLANK_LINE }

    public static final SqlScriptDelimiter AUTO = new SqlScriptDelimiter(Mode.AUTO, null);
    public static final SqlScriptDelimiter BLANK_LINE = new SqlScriptDelimiter(Mode.BLANK_LINE, null);
    private static final int MAX_TOKEN_LENGTH = 16;
    /** 自动识别只认这些符号组成的短行：它们不可能单独构成一行合法的 SQL。 */
    private static final String DETECTABLE_SYMBOLS = "!@#$%^&*~|\\/";
    private static final int DETECTION_LINE_LIMIT = 200_000;

    public SqlScriptDelimiter {
        if (mode == null) mode = Mode.AUTO;
        if (mode == Mode.CUSTOM) {
            token = token == null ? "" : token.trim();
            if (token.isEmpty() || token.length() > MAX_TOKEN_LENGTH || token.chars().anyMatch(Character::isWhitespace)) {
                throw new IllegalArgumentException("自定义分隔符需为 1–" + MAX_TOKEN_LENGTH + " 个非空白字符。");
            }
            if (token.equals(";")) throw new IllegalArgumentException("分号是默认分隔方式，请选择「自动」。");
            if (token.startsWith("--") || token.startsWith("/*")) throw new IllegalArgumentException("分隔符不能以注释符开头。");
            if (token.startsWith("'") || token.startsWith("\"") || token.startsWith("`")) throw new IllegalArgumentException("分隔符不能以引号开头。");
        } else {
            token = null;
        }
    }

    public static SqlScriptDelimiter custom(String token) {
        return new SqlScriptDelimiter(Mode.CUSTOM, token);
    }

    /** 请求参数：mode 为空或 AUTO 时忽略 token。 */
    public static SqlScriptDelimiter of(String mode, String token) {
        if (mode == null || mode.isBlank()) return AUTO;
        return switch (mode.trim().toUpperCase(Locale.ROOT)) {
            case "AUTO" -> AUTO;
            case "CUSTOM" -> custom(token);
            case "BLANK_LINE" -> BLANK_LINE;
            default -> throw new IllegalArgumentException("不支持的语句分隔方式：" + mode);
        };
    }

    public boolean isAuto() {
        return mode == Mode.AUTO;
    }

    /** 持久化编码：AUTO 存 null，与这一列加上之前的任务语义相同。 */
    public String encode() {
        return switch (mode) {
            case AUTO -> null;
            case BLANK_LINE -> "BLANK_LINE";
            case CUSTOM -> "CUSTOM:" + token;
        };
    }

    public static SqlScriptDelimiter decode(String value) {
        if (value == null || value.isBlank()) return AUTO;
        if (value.equals("BLANK_LINE")) return BLANK_LINE;
        if (value.startsWith("CUSTOM:")) return custom(value.substring("CUSTOM:".length()));
        throw new IllegalStateException("无法识别的语句分隔方式：" + value);
    }

    public String label() {
        return switch (mode) {
            case AUTO -> "自动（分号与块规则）";
            case BLANK_LINE -> "空行分隔";
            case CUSTOM -> "独占行 " + token;
        };
    }

    private static boolean isCandidate(String trimmed, boolean dollarQuoting) {
        if (trimmed.isEmpty() || trimmed.length() > 4 || trimmed.equals("/")) return false;
        if (trimmed.contains("/*") || trimmed.contains("*/")) return false;
        if (trimmed.chars().anyMatch(ch -> DETECTABLE_SYMBOLS.indexOf(ch) < 0)) return false;
        return !(dollarQuoting && trimmed.indexOf('$') >= 0);
    }

    /** 只跟踪块注释与单引号字符串：独占行的符号出现在它们里面时不是分隔符。MySQL 系的 # 与 -- 一样是行注释。 */
    private static void scan(String line, boolean[] state, boolean hashComments) {
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            char next = i + 1 < line.length() ? line.charAt(i + 1) : '\0';
            if (state[0]) {
                if (c == '*' && next == '/') { state[0] = false; i++; }
            } else if (state[1]) {
                if (c == '\'' && next == '\'') i++;
                else if (c == '\'') state[1] = false;
            } else if ((c == '-' && next == '-') || (hashComments && c == '#')) {
                return;
            } else if (c == '/' && next == '*') {
                state[0] = true;
                i++;
            } else if (c == '\'') {
                state[1] = true;
            }
        }
    }

    public static Optional<String> detect(String script, String dbType) {
        try {
            return detect(new StringReader(script == null ? "" : script), dbType);
        } catch (IOException impossible) {
            return Optional.empty();
        }
    }

    /**
     * 识别脚本是不是用独占一行的符号分段的（DBeaver 的 {@code !} 是最常见的一种）。
     *
     * <p>只认由 {@code !@#$%^&*~|\/} 组成、不超过 4 个字符的独占行 —— 这样的一行不可能是 SQL
     * 本身。单个 {@code /} 不算：它在 Oracle 系就是块结束符，AUTO 本来就认；PostgreSQL/H2 的
     * 函数体用 {@code $$} 包着，含 $ 的符号在这两个库上不认。出现次数最多的那个胜出。识别只是
     * 建议：工作台会先问用户，文件任务会在开始执行前把它写在任务说明里。</p>
     */
    public static Optional<String> detect(Reader source, String dbType) throws IOException {
        String family = DatabaseFamily.of(dbType);
        boolean dollarQuoting = family.equals("postgresql") || family.equals("h2");
        // MySQL 系：# 开头的一行是注释（mysqldump 与很多手写脚本的横幅就是 # 拼的）；DELIMITER $$ 声明过的
        // 符号是 AUTO 本来就认的分隔符，独占一行的 $$ 正是它在收尾，不能再被当成「自定义分隔符」。
        boolean mysql = DatabaseFamily.isMySqlCompatible(dbType);
        Map<String, Integer> counts = new LinkedHashMap<>();
        Set<String> declared = new HashSet<>();
        BufferedReader reader = source instanceof BufferedReader buffered ? buffered : new BufferedReader(source);
        String line;
        int lines = 0;
        boolean[] state = new boolean[2]; // [0] 块注释内，[1] 单引号字符串内
        while ((line = reader.readLine()) != null && lines++ < DETECTION_LINE_LIMIT) {
            String trimmed = line.strip();
            boolean clean = !state[0] && !state[1];
            if (mysql && clean) {
                if (trimmed.startsWith("#")) continue;
                if (trimmed.regionMatches(true, 0, "DELIMITER ", 0, "DELIMITER ".length())) {
                    declared.add(trimmed.substring("DELIMITER".length()).trim());
                    continue;
                }
            }
            if (clean && isCandidate(trimmed, dollarQuoting)) {
                counts.merge(trimmed, 1, Integer::sum);
                continue;
            }
            scan(line, state, mysql);
        }
        return counts.entrySet().stream()
                .filter(entry -> !declared.contains(entry.getKey()))
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey);
    }
}
