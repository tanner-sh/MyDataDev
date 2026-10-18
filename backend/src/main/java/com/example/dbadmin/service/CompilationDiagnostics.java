package com.example.dbadmin.service;

import com.example.dbadmin.core.DatabaseDialect;
import com.example.dbadmin.dto.ApiDtos.CompilationError;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 「语句没抛异常」不等于「对象是好的」：Oracle 系的 CREATE OR REPLACE / ALTER … COMPILE 编译失败时照样返回成功，
 * 对象以 INVALID 状态留在库里。这里从语句头认出它建的是哪个对象，再去 {@link DatabaseDialect#compilationErrorsSql()}
 * 那张字典表里把错误取回来。例程编辑器、单条执行与后台脚本共用这一份判定。
 *
 * <p>读不回来（没权限、驱动不支持）一律当作没有错误：这是补充诊断，不该把一次已经执行完的 DDL 改判成失败。</p>
 */
public final class CompilationDiagnostics {
    public static final int MAX_ERRORS_PER_OBJECT = 100;

    private static final String IDENTIFIER = "(?:\"(?:[^\"]|\"\")+\"|[\\p{L}_][\\p{L}\\p{N}_$#]*)";
    private static final Pattern CREATE = Pattern.compile(
            "CREATE\\s+(?:OR\\s+REPLACE\\s+)?(?:(?:NON)?EDITIONABLE\\s+)?"
                    + "(PACKAGE\\s+BODY|TYPE\\s+BODY|PACKAGE|TYPE|PROCEDURE|FUNCTION|TRIGGER)\\s+"
                    + "(?:IF\\s+NOT\\s+EXISTS\\s+)?(" + IDENTIFIER + ")(?:\\s*\\.\\s*(" + IDENTIFIER + "))?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ALTER_COMPILE = Pattern.compile(
            "ALTER\\s+(PACKAGE|TYPE|PROCEDURE|FUNCTION|TRIGGER|VIEW)\\s+(" + IDENTIFIER + ")(?:\\s*\\.\\s*(" + IDENTIFIER + "))?"
                    + "\\s+COMPILE\\b(\\s+BODY\\b)?",
            Pattern.CASE_INSENSITIVE);

    private CompilationDiagnostics() { }

    /** @param owner 语句里写明的 schema；null 表示落在当前 schema */
    public record Target(String owner, String name, String type) {
        public String label() {
            return (owner == null ? "" : owner + ".") + name + "（" + type + "）";
        }
    }

    /** 只看语句开头，正文里出现的 CREATE（EXECUTE IMMEDIATE 的字符串）不算。 */
    public static Optional<Target> target(String sql) {
        if (sql == null) return Optional.empty();
        String head = SqlScriptSyntax.leadingSql(sql).replaceAll("(?s)/\\*.*?\\*/|--[^\\r\\n]*", " ");
        Matcher create = CREATE.matcher(head);
        if (create.lookingAt()) return Optional.of(target(create.group(1), create.group(2), create.group(3)));
        Matcher alter = ALTER_COMPILE.matcher(head);
        if (alter.lookingAt()) {
            String type = alter.group(4) == null ? alter.group(1) : alter.group(1) + " BODY";
            return Optional.of(target(type, alter.group(2), alter.group(3)));
        }
        return Optional.empty();
    }

    private static Target target(String type, String first, String second) {
        String normalizedType = type.toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
        return second == null
                ? new Target(null, identifier(first), normalizedType)
                : new Target(identifier(first), identifier(second), normalizedType);
    }

    /** 带引号的标识符原样保留大小写；不带引号的交给字典查询按大小写不敏感比对。 */
    private static String identifier(String value) {
        return value.startsWith("\"") ? value.substring(1, value.length() - 1).replace("\"\"", "\"") : value;
    }

    /** 语句建的不是例程类对象、或方言不需要这一步时返回空列表，不发任何查询。 */
    public static List<CompilationError> read(Connection connection, DatabaseDialect dialect, String sql,
                                              String defaultOwner, int timeoutSeconds) {
        if (dialect.compilationErrorsSql() == null) return List.of();
        return target(sql).map(found -> query(connection, dialect,
                found.owner() == null ? blankToNull(defaultOwner) : found.owner(), found.name(), found.type(), timeoutSeconds))
                .orElse(List.of());
    }

    public static List<CompilationError> query(Connection connection, DatabaseDialect dialect, String owner, String name,
                                               String type, int timeoutSeconds) {
        String sql = dialect.compilationErrorsSql();
        if (sql == null) return List.of();
        List<CompilationError> errors = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(Math.max(timeoutSeconds, 1));
            statement.setString(1, blankToNull(owner));
            statement.setString(2, name);
            statement.setString(3, type);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next() && errors.size() < MAX_ERRORS_PER_OBJECT) {
                    errors.add(new CompilationError(intOrNull(rs.getObject("line")), intOrNull(rs.getObject("position")), rs.getString("text")));
                }
            }
        } catch (Exception ignored) {
            return List.of();
        }
        return List.copyOf(errors);
    }

    /** 写进任务结果与 SQL 历史的一行：对象、位置、错误原文。 */
    public static String describe(Target target, CompilationError error) {
        String where = error.line() == null ? "" : "第 " + error.line() + " 行"
                + (error.position() == null ? "" : "第 " + error.position() + " 列");
        return target.label() + (where.isEmpty() ? "" : " " + where) + "：" + (error.text() == null ? "" : error.text().strip());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static Integer intOrNull(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }
}
