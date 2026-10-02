package com.example.dbadmin.service;

import com.example.dbadmin.dto.ApiDtos.SqlParameter;
import com.example.dbadmin.dto.ApiDtos.SqlParameterDefinition;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** Named value parameters. Quoted SQL and comments are never substituted. */
public final class SqlParameters {
    private SqlParameters() { }
    public record Parsed(String sql, List<String> occurrences, int positionalParameters) {
        public List<String> names() { return new ArrayList<>(new LinkedHashSet<>(occurrences)); }
    }
    public static Parsed parse(String sql, String dbType) {
        String type = dbType == null ? "" : dbType.toLowerCase(Locale.ROOT);
        boolean mysql = Set.of("mysql", "mariadb", "oceanbase-mysql").contains(type);
        boolean generic = type.isBlank() || type.equals("generic");
        boolean oracle = generic || Set.of("oracle", "oceanbase-oracle", "dm", "dameng").contains(type);
        boolean postgres = generic || type.equals("postgresql") || type.equals("postgres") || type.equals("h2");
        boolean sqlServer = generic || Set.of("sqlserver", "sql-server", "mssql", "sqlite").contains(type);
        int positional = 0;
        boolean backslash = mysql || type.equals("clickhouse");
        StringBuilder out = new StringBuilder();
        List<String> names = new ArrayList<>();
        for (int i = 0; i < sql.length();) {
            int start = i;
            char c = sql.charAt(i);
            if (sql.startsWith("--", i) || mysql && c == '#') {
                while (i < sql.length() && sql.charAt(i) != '\n' && sql.charAt(i) != '\r') i++;
            } else if (sql.startsWith("/*", i)) {
                if (sql.startsWith("/*!", i) || sql.startsWith("/*M!", i))
                    throw new IllegalArgumentException("参数查询不支持可执行注释。");
                int depth = 1; i += 2;
                while (i < sql.length() && depth > 0) {
                    if (sql.startsWith("/*", i)) { depth++; i += 2; }
                    else if (sql.startsWith("*/", i)) { depth--; i += 2; }
                    else i++;
                }
                if (depth != 0) throw new IllegalArgumentException("SQL 注释未闭合。");
            } else if (oracle && (c == 'q' || c == 'Q') && i + 2 < sql.length() && sql.charAt(i + 1) == '\'') {
                char open = sql.charAt(i + 2);
                char close = switch (open) { case '[' -> ']'; case '(' -> ')'; case '{' -> '}'; case '<' -> '>'; default -> open; };
                int end = sql.indexOf("" + close + '\'', i + 3);
                if (end < 0) throw new IllegalArgumentException("SQL 字符串未闭合。");
                i = end + 2;
            } else if (postgres && c == '$' && (i == 0 || !Character.isJavaIdentifierPart(sql.charAt(i - 1)))) {
                var matcher = java.util.regex.Pattern.compile("\\$(?:[A-Za-z_][A-Za-z0-9_]*)?\\$").matcher(sql).region(i, sql.length());
                if (matcher.lookingAt()) {
                    String tag = matcher.group();
                    int end = sql.indexOf(tag, i + tag.length());
                    if (end < 0) throw new IllegalArgumentException("SQL dollar 字符串未闭合。");
                    i = end + tag.length();
                } else i++;
            } else if (c == '\'' || c == '"' || c == '`' || sqlServer && c == '[') {
                char close = c == '[' ? ']' : c;
                boolean escaped = backslash || c == '\'' && i > 0 && (sql.charAt(i - 1) == 'E' || sql.charAt(i - 1) == 'e');
                boolean closed = false; i++;
                while (i < sql.length()) {
                    char next = sql.charAt(i++);
                    if (escaped && next == '\\' && i < sql.length()) i++;
                    else if (next == close) {
                        if (i < sql.length() && sql.charAt(i) == close) i++;
                        else { closed = true; break; }
                    }
                }
                if (!closed) throw new IllegalArgumentException("SQL 引号未闭合。");
            } else if (c == ':' && i + 1 < sql.length() && sql.charAt(i + 1) == ':') {
                i += 2;
            } else if (c == ':' && i + 1 < sql.length() && nameStart(sql.charAt(i + 1))) {
                i += 2;
                while (i < sql.length() && namePart(sql.charAt(i))) i++;
                String name = sql.substring(start + 1, i);
                if (name.length() > 64 || names.size() >= 1000) throw new IllegalArgumentException("SQL 参数过多或名称过长。");
                names.add(name); out.append('?'); continue;
            } else { if (c == '?') positional++; i++; }
            out.append(sql, start, i);
        }
        Parsed result = new Parsed(out.toString(), List.copyOf(names), positional);
        if (result.names().size() > 100) throw new IllegalArgumentException("最多支持 100 个参数。");
        return result;
    }
    private static boolean nameStart(char c) { return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c == '_'; }
    private static boolean namePart(char c) { return nameStart(c) || c >= '0' && c <= '9'; }
    private record Value(int jdbcType, Object value) { }
    private static Value value(SqlParameter parameter) {
        if (parameter == null || parameter.type() == null) throw new IllegalArgumentException("参数类型不能为空。");
        String input = parameter.value();
        if (input != null && input.length() > 100_000) throw new IllegalArgumentException("参数值过长。");
        try {
            return switch (parameter.type()) {
                case "TEXT" -> new Value(Types.VARCHAR, input);
                case "INTEGER" -> new Value(Types.BIGINT, input == null ? null : Long.valueOf(input));
                case "DECIMAL" -> {
                    BigDecimal decimal = input == null ? null : new BigDecimal(input);
                    // A short exponent can otherwise expand into gigabytes inside a JDBC driver.
                    if (decimal != null && (decimal.precision() > 1000 || decimal.scale() < -1000 || decimal.scale() > 1000))
                        throw new IllegalArgumentException();
                    yield new Value(Types.DECIMAL, decimal);
                }
                case "BOOLEAN" -> {
                    if (input != null && !input.equals("true") && !input.equals("false")) throw new IllegalArgumentException();
                    yield new Value(Types.BOOLEAN, input == null ? null : Boolean.valueOf(input));
                }
                case "DATE" -> new Value(Types.DATE, input == null ? null : java.sql.Date.valueOf(LocalDate.parse(input)));
                case "TIMESTAMP" -> new Value(Types.TIMESTAMP, input == null ? null : java.sql.Timestamp.valueOf(LocalDateTime.parse(input.replace(' ', 'T'))));
                default -> throw new IllegalArgumentException();
            };
        } catch (RuntimeException error) { throw new IllegalArgumentException("参数值不符合类型 " + parameter.type() + " 的格式。"); }
    }
    public static void validateValues(Parsed parsed, Map<String, SqlParameter> parameters) {
        if (parsed.positionalParameters() > 0) throw new IllegalArgumentException("参数查询不支持混用 ? 占位符或问号运算符，请使用命名参数或函数形式。");
        if (parameters == null || !parameters.keySet().equals(new HashSet<>(parsed.names())))
            throw new IllegalArgumentException("参数名称必须与 SQL 中的命名参数完全一致。");
        parameters.values().forEach(SqlParameters::value);
    }
    public static void bind(PreparedStatement statement, Parsed parsed, Map<String, SqlParameter> parameters) throws SQLException {
        for (int index = 0; index < parsed.occurrences().size(); index++) {
            Value value = value(parameters.get(parsed.occurrences().get(index)));
            if (value.value() == null) statement.setNull(index + 1, value.jdbcType());
            else statement.setObject(index + 1, value.value(), value.jdbcType());
        }
    }
    public static void validateDefinitions(String sql, String dbType, List<SqlParameterDefinition> definitions) {
        if (definitions == null || definitions.isEmpty()) return;
        if (definitions.size() > 100) throw new IllegalArgumentException("最多支持 100 个参数。");
        Parsed parsed = parse(sql, dbType);
        if (parsed.positionalParameters() > 0) throw new IllegalArgumentException("查询模板不支持混用 ? 占位符或问号运算符。");
        Set<String> names = new HashSet<>();
        for (SqlParameterDefinition definition : definitions) {
            if (definition == null || definition.name() == null || !names.add(definition.name()))
                throw new IllegalArgumentException("参数名称不能为空或重复。");
            value(new SqlParameter(definition.type(), definition.defaultValue()));
        }
        if (!names.equals(new HashSet<>(parsed.names()))) throw new IllegalArgumentException("参数定义必须与 SQL 中的命名参数完全一致，请重新识别参数。");
        if (!new SqlStatementClassifier().isParameterizedSelectQuery(parsed.sql()) || new SqlScriptSplitter().split(parsed.sql(), dbType == null ? "generic" : dbType).size() != 1)
            throw new IllegalArgumentException("查询模板只支持单条 SELECT 查询。");
    }
}
