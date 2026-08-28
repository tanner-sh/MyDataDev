package com.example.dbadmin.core;

import com.example.dbadmin.dto.ApiDtos.DatabaseCapabilities;

import java.util.List;
import java.util.Locale;
import java.util.HexFormat;

public class SqlServerDialect extends DefaultDialect {
    @Override
    public boolean supports(String dbType, String jdbcUrl) {
        String type = dbType == null ? "" : dbType.toLowerCase(Locale.ROOT);
        String url = jdbcUrl == null ? "" : jdbcUrl.toLowerCase(Locale.ROOT);
        return type.equals("sqlserver") || url.startsWith("jdbc:sqlserver:");
    }

    @Override
    public DatabaseCapabilities capabilities() {
        return new DatabaseCapabilities(true, true, false, false, List.of(), List.of(), SchemaObjectCapabilities.sqlServer());
    }

    @Override
    public String pageQuery(String baseSql, int limit, int offset) {
        String ordered = hasTopLevelOrderBy(baseSql) ? baseSql : baseSql + " ORDER BY (SELECT NULL)";
        return ordered + " OFFSET " + offset + " ROWS FETCH NEXT " + limit + " ROWS ONLY";
    }

    @Override
    public String scriptLiteral(Object value) {
        if (value instanceof byte[] bytes) return scriptBinaryLiteral(bytes);
        if (value instanceof Boolean bool) return bool ? "1" : "0";
        if (value instanceof CharSequence text) return "N'" + text.toString().replace("'", "''") + "'";
        return super.scriptLiteral(value);
    }

    @Override
    public String scriptBinaryLiteral(byte[] value) {
        return "0x" + HexFormat.of().formatHex(value);
    }

    private boolean hasTopLevelOrderBy(String sql) {
        int depth = 0;
        String previousWord = null;
        for (int index = 0; index < sql.length();) {
            char current = sql.charAt(index);
            char next = index + 1 < sql.length() ? sql.charAt(index + 1) : '\0';
            if (current == '\'' || current == '"') {
                char quote = current;
                index++;
                while (index < sql.length()) {
                    if (sql.charAt(index) == quote) {
                        if (index + 1 < sql.length() && sql.charAt(index + 1) == quote) index += 2;
                        else { index++; break; }
                    } else index++;
                }
                previousWord = null;
                continue;
            }
            if (current == '[') {
                index++;
                while (index < sql.length()) {
                    if (sql.charAt(index) == ']') {
                        if (index + 1 < sql.length() && sql.charAt(index + 1) == ']') index += 2;
                        else { index++; break; }
                    } else index++;
                }
                previousWord = null;
                continue;
            }
            if (current == '-' && next == '-') {
                index += 2;
                while (index < sql.length() && sql.charAt(index) != '\n' && sql.charAt(index) != '\r') index++;
                continue;
            }
            if (current == '/' && next == '*') {
                index += 2;
                while (index + 1 < sql.length() && !(sql.charAt(index) == '*' && sql.charAt(index + 1) == '/')) index++;
                index = Math.min(sql.length(), index + 2);
                continue;
            }
            if (current == '(') {
                depth++;
                previousWord = null;
                index++;
                continue;
            }
            if (current == ')') {
                depth = Math.max(0, depth - 1);
                previousWord = null;
                index++;
                continue;
            }
            if (Character.isLetter(current) || current == '_') {
                int start = index++;
                while (index < sql.length() && (Character.isLetterOrDigit(sql.charAt(index)) || sql.charAt(index) == '_')) index++;
                if (depth == 0) {
                    String word = sql.substring(start, index).toLowerCase(Locale.ROOT);
                    if ("by".equals(word) && "order".equals(previousWord)) return true;
                    previousWord = word;
                }
                continue;
            }
            if (!Character.isWhitespace(current)) previousWord = null;
            index++;
        }
        return false;
    }

    @Override
    public String quoteIdentifier(String identifier) {
        return "[" + identifier.replace("]", "]]" ) + "]";
    }
}
