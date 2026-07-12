package com.example.dbadmin.service;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
public class SqlStatementClassifier {
    public enum Kind {
        QUERY,
        MUTATION,
        DDL,
        UNKNOWN
    }

    private static final Set<String> QUERY = Set.of("SELECT", "SHOW", "DESCRIBE", "DESC", "EXPLAIN", "VALUES", "TABLE");
    private static final Set<String> MUTATION = Set.of(
            "INSERT", "UPDATE", "DELETE", "MERGE", "UPSERT", "REPLACE", "CALL", "EXEC", "EXECUTE", "COPY", "LOAD",
            "VACUUM", "ANALYZE", "BEGIN", "START", "COMMIT", "ROLLBACK", "SAVEPOINT", "RELEASE", "DO", "LOCK"
    );
    private static final Set<String> SESSION = Set.of("USE", "SET", "RESET", "PRAGMA", "ATTACH", "DETACH", "DISCARD");
    private static final Set<String> DDL = union(
            Set.of("CREATE", "ALTER", "DROP", "RENAME", "TRUNCATE", "COMMENT", "GRANT", "REVOKE", "REFRESH", "REINDEX", "CLUSTER"),
            SESSION
    );
    private static final Set<String> OPERATIONS = union(QUERY, MUTATION, DDL);
    private static final Set<String> SELECT_SIDE_EFFECT_TOKENS = Set.of("NEXTVAL", "SETVAL", "UPDLOCK", "XLOCK");

    public Kind classify(String sql) {
        if (sql == null || sql.isBlank()) return Kind.UNKNOWN;
        List<Token> tokens = tokens(sql);
        if (tokens.isEmpty()) return Kind.UNKNOWN;
        Operation operation = operation(tokens);
        if (operation == null) return Kind.UNKNOWN;

        if ("WITH".equals(tokens.get(0).word())) {
            Kind nestedWrite = nestedCteWrite(tokens);
            if (nestedWrite != null) return nestedWrite;
        }
        Kind kind = kind(operation.word());
        if (kind == Kind.QUERY && hasQuerySideEffect(tokens, operation)) return Kind.MUTATION;
        return kind;
    }

    public boolean isQuery(String sql) {
        return classify(sql) == Kind.QUERY;
    }

    public boolean changesSession(String sql) {
        List<Token> tokens = tokens(sql);
        Operation operation = tokens.isEmpty() ? null : operation(tokens);
        return operation != null && SESSION.contains(operation.word());
    }

    private Operation operation(List<Token> tokens) {
        Token first = tokens.get(0);
        if (!"WITH".equals(first.word())) return new Operation(first.word(), 0);
        for (int index = 1; index < tokens.size(); index++) {
            Token token = tokens.get(index);
            if (token.depth() == 0 && OPERATIONS.contains(token.word())) return new Operation(token.word(), index);
        }
        return null;
    }

    private Kind nestedCteWrite(List<Token> tokens) {
        for (Token token : tokens) {
            if (token.depth() <= 0) continue;
            if (MUTATION.contains(token.word())) return Kind.MUTATION;
            if (DDL.contains(token.word())) return Kind.DDL;
        }
        return null;
    }

    private boolean hasQuerySideEffect(List<Token> tokens, Operation operation) {
        if ("EXPLAIN".equals(operation.word())) {
            for (int index = operation.index() + 1; index < tokens.size(); index++) {
                String token = tokens.get(index).word();
                if (MUTATION.contains(token) || DDL.contains(token)) return true;
            }
        }
        for (int index = operation.index() + 1; index < tokens.size(); index++) {
            String token = tokens.get(index).word();
            if ("INTO".equals(token) || SELECT_SIDE_EFFECT_TOKENS.contains(token)) return true;
            if ("FOR".equals(token) && index + 1 < tokens.size()
                    && Set.of("UPDATE", "SHARE").contains(tokens.get(index + 1).word())) return true;
            if ("LOCK".equals(token) && index + 3 < tokens.size()
                    && "IN".equals(tokens.get(index + 1).word())
                    && "SHARE".equals(tokens.get(index + 2).word())
                    && "MODE".equals(tokens.get(index + 3).word())) return true;
        }
        return false;
    }

    private Kind kind(String keyword) {
        if (QUERY.contains(keyword)) return Kind.QUERY;
        if (MUTATION.contains(keyword)) return Kind.MUTATION;
        if (DDL.contains(keyword)) return Kind.DDL;
        return Kind.UNKNOWN;
    }

    private List<Token> tokens(String sql) {
        List<Token> tokens = new ArrayList<>();
        int depth = 0;
        for (int index = 0; index < sql.length();) {
            char ch = sql.charAt(index);
            char next = index + 1 < sql.length() ? sql.charAt(index + 1) : '\0';
            if (ch == '-' && next == '-') {
                index = skipLineComment(sql, index + 2);
                continue;
            }
            if (ch == '/' && next == '*') {
                index = skipBlockComment(sql, index + 2);
                continue;
            }
            if (ch == '\'') {
                index = skipQuoted(sql, index + 1, '\'', true);
                continue;
            }
            if (ch == '"') {
                index = skipQuoted(sql, index + 1, '"', true);
                continue;
            }
            if (ch == '`') {
                index = skipQuoted(sql, index + 1, '`', true);
                continue;
            }
            if (ch == '[') {
                index = skipBracketIdentifier(sql, index + 1);
                continue;
            }
            if ((ch == 'q' || ch == 'Q') && next == '\'' && index + 2 < sql.length()) {
                int end = skipOracleQuoted(sql, index);
                if (end > index) {
                    index = end;
                    continue;
                }
            }
            if (ch == '$') {
                String delimiter = dollarDelimiter(sql, index);
                if (delimiter != null) {
                    int end = sql.indexOf(delimiter, index + delimiter.length());
                    index = end < 0 ? sql.length() : end + delimiter.length();
                    continue;
                }
            }
            if (ch == '(') {
                depth++;
                index++;
                continue;
            }
            if (ch == ')') {
                depth = Math.max(0, depth - 1);
                index++;
                continue;
            }
            if (Character.isLetter(ch) || ch == '_') {
                int end = index + 1;
                while (end < sql.length() && (Character.isLetterOrDigit(sql.charAt(end)) || sql.charAt(end) == '_' || sql.charAt(end) == '$')) end++;
                tokens.add(new Token(sql.substring(index, end).toUpperCase(Locale.ROOT), depth));
                index = end;
                continue;
            }
            index++;
        }
        return tokens;
    }

    private int skipLineComment(String sql, int index) {
        while (index < sql.length() && sql.charAt(index) != '\n' && sql.charAt(index) != '\r') index++;
        return index;
    }

    private int skipBlockComment(String sql, int index) {
        while (index + 1 < sql.length()) {
            if (sql.charAt(index) == '*' && sql.charAt(index + 1) == '/') return index + 2;
            index++;
        }
        return sql.length();
    }

    private int skipQuoted(String sql, int index, char quote, boolean doubledEscape) {
        while (index < sql.length()) {
            if (sql.charAt(index) == quote) {
                if (doubledEscape && index + 1 < sql.length() && sql.charAt(index + 1) == quote) {
                    index += 2;
                    continue;
                }
                return index + 1;
            }
            if (sql.charAt(index) == '\\' && index + 1 < sql.length()) index += 2;
            else index++;
        }
        return sql.length();
    }

    private int skipBracketIdentifier(String sql, int index) {
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

    private int skipOracleQuoted(String sql, int index) {
        char opening = sql.charAt(index + 2);
        char closing = switch (opening) {
            case '[' -> ']';
            case '{' -> '}';
            case '(' -> ')';
            case '<' -> '>';
            default -> opening;
        };
        for (int cursor = index + 3; cursor + 1 < sql.length(); cursor++) {
            if (sql.charAt(cursor) == closing && sql.charAt(cursor + 1) == '\'') return cursor + 2;
        }
        return sql.length();
    }

    private String dollarDelimiter(String sql, int index) {
        int end = index + 1;
        while (end < sql.length() && (Character.isLetterOrDigit(sql.charAt(end)) || sql.charAt(end) == '_')) end++;
        String tag = sql.substring(index + 1, end);
        boolean validTag = tag.isEmpty() || (Character.isLetter(tag.charAt(0)) || tag.charAt(0) == '_');
        if (validTag && end < sql.length() && sql.charAt(end) == '$') return sql.substring(index, end + 1);
        return null;
    }

    private static Set<String> union(Set<String> first, Set<String> second, Set<String> third) {
        java.util.HashSet<String> values = new java.util.HashSet<>(first);
        values.addAll(second);
        values.addAll(third);
        return Set.copyOf(values);
    }

    private static Set<String> union(Set<String> first, Set<String> second) {
        java.util.HashSet<String> values = new java.util.HashSet<>(first);
        values.addAll(second);
        return Set.copyOf(values);
    }

    private record Token(String word, int depth) {
    }

    private record Operation(String word, int index) {
    }
}
