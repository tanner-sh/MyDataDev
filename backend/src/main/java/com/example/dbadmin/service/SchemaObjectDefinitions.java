package com.example.dbadmin.service;

import com.example.dbadmin.core.SchemaObjectKind;
import com.example.dbadmin.core.SchemaObjectOperation;
import com.example.dbadmin.core.SchemaObjectSql;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 新建 / 修改可编程对象时对用户源码的校验：只能是一条定义语句、动作与操作一致（新建不许 OR REPLACE）、
 * 定义的对象类型、名字与命名空间必须正是当前目标，且不能夹带客户端批处理分隔符。
 *
 * <p>这是「源码框里写什么都会被执行」那道口子上的闸门：名字对不上就可能改掉别的对象。从
 * {@link SchemaObjectService} 拆出来是因为它是纯文本规则，不碰连接。</p>
 */
final class SchemaObjectDefinitions {
    private static final String DEFINITION_IDENTIFIER = "(?:\"(?:\"\"|[^\"])+\"|`(?:``|[^`])+`|\\[(?:]]|[^]])+]|[A-Za-z_][A-Za-z0-9_$#]*)";
    private static final Pattern DEFINITION_ACTION = Pattern.compile("(?is)^(CREATE(?:\\s+OR\\s+(?:REPLACE|ALTER))?|ALTER)\\s+");
    private static final Pattern DEFINITION_MODIFIER = Pattern.compile("(?is)^(?:(?:FORCE|EDITIONABLE|NONEDITIONABLE)\\s+|ALGORITHM\\s*=\\s*[A-Z_]+\\s+|SQL\\s+SECURITY\\s+(?:DEFINER|INVOKER)\\s+|DEFINER\\s*=\\s*(?:`(?:``|[^`])+`|\"(?:\"\"|[^\"])+\"|\\[(?:]]|[^]])+]|[^\\s]+)(?:\\s*@\\s*(?:`(?:``|[^`])+`|\"(?:\"\"|[^\"])+\"|\\[(?:]]|[^]])+]|[^\\s]+))?\\s+)");
    private static final Pattern DEFINITION_NAME = Pattern.compile("(?is)^\\s*(" + DEFINITION_IDENTIFIER + ")(?:\\s*\\.\\s*(" + DEFINITION_IDENTIFIER + "))?");
    private static final Pattern CLIENT_BATCH_SEPARATOR = Pattern.compile("(?im)^\\s*(?:DELIMITER\\b.*|GO\\s*|/\\s*)$");

    private final SqlScriptSplitter scriptSplitter;

    SchemaObjectDefinitions(SqlScriptSplitter scriptSplitter) {
        this.scriptSplitter = scriptSplitter;
    }

    String requireSource(String source, SchemaObjectOperation operation, SchemaObjectKind kind, String schemaName, String objectName, SchemaObjectSql objects) {
        if (source == null || source.isBlank()) throw new IllegalArgumentException("对象源码不能为空。");
        if (source.length() > 2_000_000) throw new IllegalArgumentException("对象源码不能超过 200 万字符。");
        if (CLIENT_BATCH_SEPARATOR.matcher(source).find()) {
            throw new IllegalArgumentException("源码不能包含 DELIMITER、GO 或单独的 / 等客户端批处理分隔符。");
        }
        requireSingleDefinition(source, kind, objects);
        validateDefinitionTarget(source, operation, kind, schemaName, objectName, objects);
        return source.trim();
    }

    private static void validateDefinitionTarget(String source, SchemaObjectOperation operation, SchemaObjectKind kind, String schemaName, String objectName, SchemaObjectSql objects) {
        String remainder = stripLeadingComments(source);
        var actionMatcher = DEFINITION_ACTION.matcher(remainder);
        if (!actionMatcher.find()) {
            throw new IllegalArgumentException(operation == SchemaObjectOperation.CREATE
                    ? "新建源码必须以 CREATE 开头。"
                    : "修改源码必须使用 CREATE OR REPLACE、CREATE OR ALTER 或安全 ALTER。");
        }
        String action = actionMatcher.group(1).replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
        if (operation == SchemaObjectOperation.CREATE && !action.equals("CREATE")) {
            throw new IllegalArgumentException("新建源码不能使用 OR REPLACE、OR ALTER 或 ALTER，以免覆盖已有对象。");
        }
        if (operation == SchemaObjectOperation.REPLACE && action.equals("CREATE")) {
            throw new IllegalArgumentException("修改源码必须使用 CREATE OR REPLACE、CREATE OR ALTER 或安全 ALTER。");
        }
        remainder = remainder.substring(actionMatcher.end()).stripLeading();
        while (true) {
            var modifier = DEFINITION_MODIFIER.matcher(remainder);
            if (!modifier.find()) break;
            remainder = remainder.substring(modifier.end()).stripLeading();
        }

        String expectedKind = kind == SchemaObjectKind.MATERIALIZED_VIEW ? "MATERIALIZED VIEW" : kind.name();
        String declarationKind = kind == SchemaObjectKind.FUNCTION && objects.functionsMayBeAliases() && startsWithWord(remainder, "ALIAS")
                ? "ALIAS" : expectedKind;
        if (!startsWithWord(remainder, declarationKind)) {
            throw new IllegalArgumentException("源码定义的对象类型与当前目标不匹配。");
        }
        remainder = remainder.substring(declarationKind.length()).stripLeading();
        if (remainder.toUpperCase(Locale.ROOT).startsWith("IF NOT EXISTS ")) {
            remainder = remainder.substring("IF NOT EXISTS ".length()).stripLeading();
        }
        var nameMatcher = DEFINITION_NAME.matcher(remainder);
        if (!nameMatcher.find()) throw new IllegalArgumentException("无法识别源码定义的对象名。");
        String declaredName = nameMatcher.group(2) == null ? nameMatcher.group(1) : nameMatcher.group(2);
        if (!sameDefinitionIdentifier(declaredName, objectName)) {
            throw new IllegalArgumentException("源码定义的对象名与当前目标不匹配。");
        }
        String declaredSchema = nameMatcher.group(2) == null ? null : nameMatcher.group(1);
        if (declaredSchema != null && (schemaName == null || schemaName.isBlank() || !sameDefinitionIdentifier(declaredSchema, schemaName))) {
            throw new IllegalArgumentException("源码定义的命名空间与当前目标不匹配。");
        }
        boolean globalObject = objects.functionsAreGlobal() && kind == SchemaObjectKind.FUNCTION;
        if (declaredSchema == null && schemaName != null && !schemaName.isBlank()
                && !objects.definitionsResolveInCurrentNamespace() && !globalObject) {
            throw new IllegalArgumentException("对象源码必须使用当前命名空间限定对象名：" + schemaName + "." + objectName);
        }
    }

    private static boolean startsWithWord(String source, String expected) {
        if (!source.regionMatches(true, 0, expected, 0, expected.length())) return false;
        return source.length() == expected.length() || Character.isWhitespace(source.charAt(expected.length()));
    }

    private static boolean sameDefinitionIdentifier(String declared, String expected) {
        boolean quoted = declared.startsWith("\"") || declared.startsWith("`") || declared.startsWith("[");
        String value = declared;
        if (declared.startsWith("\"") && declared.endsWith("\"")) value = declared.substring(1, declared.length() - 1).replace("\"\"", "\"");
        else if (declared.startsWith("`") && declared.endsWith("`")) value = declared.substring(1, declared.length() - 1).replace("``", "`");
        else if (declared.startsWith("[") && declared.endsWith("]")) value = declared.substring(1, declared.length() - 1).replace("]]", "]");
        return quoted ? value.equals(expected) : value.equalsIgnoreCase(expected);
    }

    private void requireSingleDefinition(String source, SchemaObjectKind kind, SchemaObjectSql objects) {
        List<SqlScriptSplitter.StatementSegment> statements = scriptSplitter.split(source);
        if (statements.size() <= 1) return;
        boolean procedural = kind == SchemaObjectKind.TRIGGER || kind == SchemaObjectKind.PROCEDURE || kind == SchemaObjectKind.FUNCTION;
        if (!procedural) throw new IllegalArgumentException("对象源码只能包含一个顶层定义语句。");

        String effective = stripTrailingComments(source).stripTrailing().toUpperCase(Locale.ROOT);
        // Procedural definitions contain internal semicolons. For dialects
        // that use BEGIN/END bodies, accept them only when the complete input
        // ends at the native END marker. Appended statements remain outside
        // that marker and are rejected. PostgreSQL/H2 dollar-quoted bodies are
        // already treated as one statement by SqlScriptSplitter.
        if (!objects.proceduralBodiesEndWithEnd()
                || !effective.matches("(?s).*\\bEND\\s*;?\\s*$")) {
            throw new IllegalArgumentException("对象源码只能包含一个顶层定义语句。");
        }
    }

    private static String stripTrailingComments(String source) {
        String value = source;
        while (true) {
            String trimmed = value.stripTrailing();
            if (trimmed.endsWith("*/")) {
                int start = trimmed.lastIndexOf("/*");
                if (start >= 0) {
                    value = trimmed.substring(0, start);
                    continue;
                }
            }
            int lineStart = Math.max(trimmed.lastIndexOf('\n'), trimmed.lastIndexOf('\r')) + 1;
            if (trimmed.substring(lineStart).stripLeading().startsWith("--")) {
                value = trimmed.substring(0, lineStart);
                continue;
            }
            return trimmed;
        }
    }

    private static String stripLeadingComments(String source) {
        String value = source.stripLeading();
        while (true) {
            if (value.startsWith("--")) {
                int newline = value.indexOf('\n');
                value = newline < 0 ? "" : value.substring(newline + 1).stripLeading();
                continue;
            }
            if (value.startsWith("/*")) {
                int end = value.indexOf("*/", 2);
                value = end < 0 ? "" : value.substring(end + 2).stripLeading();
                continue;
            }
            return value;
        }
    }

}
