package com.example.dbadmin.service;

import com.example.dbadmin.core.SchemaObjectSql;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 对象定义导出脚本的文本处理：每条定义怎么收尾、源码里提到了哪些对象。纯逻辑，不碰数据库。
 *
 * <p>收尾规则必须和本应用自己的脚本解析器（{@link SqlScriptParser}）对得上 —— 导出的脚本要能
 * 原样放进 SQL 工作台或「执行 SQL 文件」跑，也要能交给各家的命令行工具：</p>
 * <ul>
 *   <li>Oracle 系：存储过程、函数、触发器是 PL/SQL 块，正文里本来就有分号，只能靠独占一行的
 *   {@code /} 结束；DBMS_METADATA 给触发器的定义后面还跟着一句 {@code ALTER TRIGGER … ENABLE}，
 *   要拆成块之后的一条普通语句，否则它会被并进 PL/SQL 块里。</li>
 *   <li>MySQL 系：例程和触发器的 BEGIN…END 里有分号，用 {@code DELIMITER ;;} 包起来，与 mysqldump
 *   的写法一致，mysql 命令行也认。</li>
 *   <li>SQL Server：CREATE PROCEDURE/VIEW/TRIGGER 必须是批里的第一条，每条后面跟 {@code GO}。</li>
 *   <li>其余：分号结尾。</li>
 * </ul>
 */
public final class SchemaExportScript {
    private static final Pattern TRAILING_TERMINATORS = Pattern.compile("(?s)(?:\\s*(?:;|\\n\\s*/))+\\s*$");
    private static final Pattern ORACLE_TRIGGER_TAIL = Pattern.compile("(?is)^(.*\\bEND\\b[^;]*;)\\s*(ALTER\\s+TRIGGER\\s+.+?)\\s*;?\\s*$");
    private static final Pattern MYSQL_DEFINER = Pattern.compile(
            "(?i)\\s+DEFINER\\s*=\\s*(?:`(?:``|[^`])*`|'(?:''|[^'])*'|\"(?:\"\"|[^\"])*\"|[^\\s@]+)"
                    + "(?:\\s*@\\s*(?:`(?:``|[^`])*`|'(?:''|[^'])*'|\"(?:\"\"|[^\"])*\"|[^\\s]+))?");
    private static final Pattern IDENTIFIER = Pattern.compile("\"((?:\"\"|[^\"])+)\"|`((?:``|[^`])+)`|\\[((?:]]|[^]])+)]|([A-Za-z_][A-Za-z0-9_$#]*)");

    private SchemaExportScript() {
    }

    public static boolean isRoutineBlock(String kind) {
        return switch (kind == null ? "" : kind.toUpperCase(Locale.ROOT)) {
            case "PROCEDURE", "FUNCTION", "TRIGGER" -> true;
            default -> false;
        };
    }

    /** 一条定义在脚本里的完整写法，带好分隔符。收尾写法由方言给出（{@code SchemaObjectSql.scriptBlockStyle}）。 */
    public static String statement(SchemaObjectSql.ScriptBlockStyle style, String kind, String definition) {
        String body = stripTrailingTerminators(definition);
        boolean routine = isRoutineBlock(kind);
        return switch (style == null ? SchemaObjectSql.ScriptBlockStyle.PLAIN : style) {
            case ORACLE -> routine ? oracleBlock(kind, body) : body + ";";
            case MYSQL -> routine ? "DELIMITER ;;\n" + body + ";;\nDELIMITER ;" : body + ";";
            case SQL_SERVER -> body + "\nGO";
            case PLAIN -> body + ";";
        };
    }

    private static String oracleBlock(String kind, String body) {
        // PL/SQL 块必须带着自己的 END …; 收尾，再由独占行的 / 结束。
        if ("TRIGGER".equalsIgnoreCase(kind)) {
            Matcher tail = ORACLE_TRIGGER_TAIL.matcher(body + ";");
            if (tail.matches()) return tail.group(1).trim() + "\n/\n" + tail.group(2).trim() + ";";
        }
        return body + ";\n/";
    }

    static String stripTrailingTerminators(String definition) {
        String trimmed = definition == null ? "" : definition.strip();
        return TRAILING_TERMINATORS.matcher(trimmed).replaceFirst("").strip();
    }

    /**
     * 去掉 MySQL 系定义里的 {@code DEFINER=…}。
     *
     * <p>SHOW CREATE 给出的定义带着原库的账号（{@code DEFINER=`app`@`10.0.0.%`}），换一个环境执行时
     * 这个账号多半不存在，MySQL 直接报错。去掉之后定义者就是执行脚本的账号 —— 跨环境迁移时这几乎
     * 总是想要的效果，所以默认去掉，界面上可以关。只处理 CREATE 与对象关键字之间那一段，不碰正文。</p>
     */
    public static String stripDefiner(String definition) {
        if (definition == null) return null;
        Matcher head = Pattern.compile("(?is)^(\\s*CREATE\\b.*?)\\b(VIEW|PROCEDURE|FUNCTION|TRIGGER|EVENT)\\b").matcher(definition);
        if (!head.find()) return definition;
        String prefix = MYSQL_DEFINER.matcher(head.group(1)).replaceAll("");
        return prefix + definition.substring(head.start(2));
    }

    /**
     * 源码里出现过的标识符（折成大写），已去掉注释和字符串字面量。
     *
     * <p>用于推断视图、例程之间的先后：大多数库没有可靠的依赖字典（MySQL、H2），PostgreSQL 的
     * pg_depend 对视图又只记在重写规则上。拿本次导出范围内的对象名去比对，多认出一条边的代价只是
     * 顺序多一个约束；少认一条，脚本就可能跑到一半报「对象不存在」。</p>
     */
    public static Set<String> referencedIdentifiers(String source) {
        Set<String> names = new LinkedHashSet<>();
        if (source == null) return names;
        Matcher matcher = IDENTIFIER.matcher(stripCommentsAndStrings(source));
        while (matcher.find()) {
            for (int group = 1; group <= 4; group++) {
                if (matcher.group(group) != null) {
                    names.add(matcher.group(group).toUpperCase(Locale.ROOT));
                    break;
                }
            }
        }
        return names;
    }

    static String stripCommentsAndStrings(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        int n = source.length();
        while (i < n) {
            char c = source.charAt(i);
            char next = i + 1 < n ? source.charAt(i + 1) : '\0';
            if (c == '-' && next == '-') {
                while (i < n && source.charAt(i) != '\n') i++;
                out.append(' ');
            } else if (c == '/' && next == '*') {
                int end = source.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
                out.append(' ');
            } else if (c == '\'') {
                i++;
                while (i < n) {
                    if (source.charAt(i) == '\'') {
                        if (i + 1 < n && source.charAt(i + 1) == '\'') { i += 2; continue; }
                        break;
                    }
                    i++;
                }
                i++;
                out.append(" '' ");
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    public static List<String> header(String connectionName, String dbType, String schemaName, int count, String generatedAt) {
        return List.of(
                "-- MyDataDev 对象定义导出",
                "-- 连接：" + connectionName + "（" + dbType + "）",
                "-- Schema：" + (schemaName == null || schemaName.isBlank() ? "（连接默认）" : schemaName),
                "-- 对象数：" + count + "，生成于 " + generatedAt,
                "-- 顺序已按依赖关系排好：被依赖的对象在前。",
                "");
    }
}
