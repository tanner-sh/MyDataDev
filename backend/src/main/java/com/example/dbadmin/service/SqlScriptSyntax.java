package com.example.dbadmin.service;

import java.util.Locale;
import java.util.Set;

/** Classification only examines the start of a complete execution unit, never a procedure body. */
public final class SqlScriptSyntax {
    private SqlScriptSyntax() { }
    public enum Transaction { NONE, COMMIT, ROLLBACK, SAVEPOINT, ROLLBACK_TO, OTHER }
    public static String leadingSql(String sql) {
        int i = 0;
        while (i < sql.length()) {
            if (Character.isWhitespace(sql.charAt(i)) || sql.charAt(i) == '\ufeff') { i++; continue; }
            if (sql.startsWith("--", i)) {
                i += 2;
                while (i < sql.length() && sql.charAt(i) != '\n' && sql.charAt(i) != '\r') i++;
                continue;
            }
            if (sql.startsWith("/*", i)) { int end = sql.indexOf("*/", i + 2); i = end < 0 ? sql.length() : end + 2; continue; }
            break;
        }
        return sql.substring(i);
    }
    public static Transaction transaction(String sql) {
        String s = leadingSql(sql).replaceAll("(?s)/\\*.*?\\*/|--[^\\r\\n]*", " ").toUpperCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
        if (s.matches("COMMIT\\b.*")) return Transaction.COMMIT;
        if (s.matches("ROLLBACK(?: WORK)? TO\\b.*")) return Transaction.ROLLBACK_TO;
        if (s.matches("ROLLBACK\\b.*")) return Transaction.ROLLBACK;
        if (s.matches("SAVEPOINT\\b.*")) return Transaction.SAVEPOINT;
        if (s.matches("(?:START TRANSACTION|BEGIN(?: WORK| TRAN(?:SACTION)?)?|RELEASE SAVEPOINT|SET (?:SESSION )?AUTOCOMMIT)\\b.*")
                && !opaqueBlock(sql)) return Transaction.OTHER;
        return Transaction.NONE;
    }
    public static boolean opaqueBlock(String sql) {
        String s = leadingSql(sql).toUpperCase(Locale.ROOT);
        return s.matches("(?s)DECLARE\\b.*") || s.matches("(?s)BEGIN\\b.*;.*")
                || s.matches("(?s)(?:CALL|EXEC|EXECUTE|DO)\\b.*");
    }
    public static boolean scriptTransactionsSupported(String dbType) {
        return dbType != null && Set.of("oracle", "oceanbase-oracle").contains(dbType.toLowerCase(Locale.ROOT));
    }
    public static void validateScriptControl(String sql) {
        // Preserve valid dialect-specific modifiers by sending the original SQL to JDBC.
        if (transaction(sql) == Transaction.OTHER) {
            throw new IllegalArgumentException("脚本事务模式暂不支持该事务或自动提交指令：" + leadingSql(sql));
        }
    }
}
