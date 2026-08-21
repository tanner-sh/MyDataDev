package com.example.dbadmin.service;

import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
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
    private static final Set<String> TOP_LEVEL_PAGING_TOKENS = Set.of("LIMIT", "OFFSET", "FETCH", "TOP");
    private static final Set<String> UNSCOPED_MUTATIONS = Set.of("UPDATE", "DELETE");
    // Words that may sit between EXPLAIN and the verb of the statement it explains.
    private static final Set<String> EXPLAIN_MODIFIERS = Set.of(
            "ANALYZE", "ANALYSE", "VERBOSE", "EXTENDED", "PARTITIONS", "FORMAT", "PLAN", "FOR", "QUERY"
    );

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

    public boolean isAutomaticallyPageable(String sql) {
        List<Token> tokens = tokens(sql);
        Operation operation = tokens.isEmpty() ? null : operation(tokens);
        if (operation == null || !"SELECT".equals(operation.word()) || classify(sql) != Kind.QUERY) return false;
        return tokens.stream().noneMatch(token -> token.depth() == 0 && TOP_LEVEL_PAGING_TOKENS.contains(token.word()));
    }

    public boolean changesSession(String sql) {
        List<Token> tokens = tokens(sql);
        Operation operation = tokens.isEmpty() ? null : operation(tokens);
        return operation != null && SESSION.contains(operation.word());
    }

    /**
     * Reports whether the statement contains an UPDATE or DELETE that has no
     * WHERE of its own, and therefore may rewrite an entire table.
     *
     * <p>The check runs per statement position rather than only on the leading
     * verb, because a data-modifying CTE hides the write inside a parenthesised
     * group: in {@code WITH removed AS (DELETE FROM users RETURNING *) SELECT
     * ... FROM removed} the leading verb is SELECT even though the statement
     * deletes every row. A WHERE only counts when it belongs to the same
     * parenthesised group as the write it is meant to scope, so neither a
     * subquery's WHERE nor a sibling CTE's WHERE can suppress the prompt.</p>
     */
    public boolean requiresUnscopedMutationConfirmation(String sql) {
        List<Token> tokens = tokens(sql);
        if (tokens.isEmpty()) return false;
        Operation operation = operation(tokens);
        // Nested statement positions are only considered for a WITH prefix, so
        // that this stays aligned with what nestedCteWrite() treats as a write.
        boolean withPrefix = "WITH".equals(tokens.get(0).word());
        for (int index = 0; index < tokens.size(); index++) {
            Token token = tokens.get(index);
            if (!UNSCOPED_MUTATIONS.contains(token.word())) continue;
            boolean statementPosition = (operation != null && index == operation.index())
                    || (withPrefix && token.groupStart() && token.depth() > 0);
            if (!statementPosition) continue;
            if (!hasScopingWhere(tokens, index, token.group())) return true;
        }
        return false;
    }

    private boolean hasScopingWhere(List<Token> tokens, int statementIndex, int group) {
        for (int index = statementIndex + 1; index < tokens.size(); index++) {
            Token token = tokens.get(index);
            if (token.group() == group && "WHERE".equals(token.word())) return true;
        }
        return false;
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

    /**
     * Detects a data-modifying CTE, e.g.
     * {@code WITH t AS (DELETE FROM a RETURNING *) ...}.
     *
     * <p>Only a keyword in statement position counts — the first token inside
     * its parenthesised group. Many of these keywords double as ordinary
     * function names ({@code REPLACE}, {@code TRUNCATE}, {@code LOAD},
     * {@code ANALYZE}), so matching anywhere inside the group would classify a
     * plain read such as
     * {@code WITH t AS (SELECT REPLACE(name,'a','b') FROM users) SELECT * FROM t}
     * as a write, and get it rejected on read-only connections.</p>
     */
    private Kind nestedCteWrite(List<Token> tokens) {
        for (Token token : tokens) {
            if (token.depth() <= 0 || !token.groupStart()) continue;
            if (MUTATION.contains(token.word())) return Kind.MUTATION;
            if (DDL.contains(token.word())) return Kind.DDL;
        }
        return null;
    }

    private boolean hasQuerySideEffect(List<Token> tokens, Operation operation) {
        if ("EXPLAIN".equals(operation.word()) && explainExecutesWrite(tokens, operation)) return true;
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

    /**
     * Reports whether an EXPLAIN would carry out a write.
     *
     * <p>Only the verb of the statement being explained decides this. Scanning
     * every following token instead would classify ordinary reads as writes,
     * because many keywords in {@link #MUTATION} and {@link #DDL} are also
     * perfectly ordinary column names: {@code EXPLAIN SELECT comment FROM posts}
     * and {@code EXPLAIN SELECT start FROM events} were both reported as
     * mutations, which made them fail on read-only connections and get rejected
     * by {@code SqlService.explain} as "not a query".</p>
     *
     * <p>Dialect modifiers may sit between EXPLAIN and that verb — PostgreSQL
     * {@code ANALYZE VERBOSE} and its parenthesised option list, MySQL
     * {@code FORMAT=JSON}, Oracle {@code PLAN FOR}, SQLite {@code QUERY PLAN} —
     * so they are skipped, along with anything nested inside parentheses.</p>
     */
    private boolean explainExecutesWrite(List<Token> tokens, Operation operation) {
        int statementDepth = tokens.get(operation.index()).depth();
        for (int index = operation.index() + 1; index < tokens.size(); index++) {
            Token token = tokens.get(index);
            if (token.depth() != statementDepth) continue;
            if (EXPLAIN_MODIFIERS.contains(token.word())) continue;
            // A WITH prefix can hide the write in a data-modifying CTE, which the
            // depth filter above steps over. Defer to the same check classify()
            // uses for a bare WITH statement.
            if ("WITH".equals(token.word())) return nestedCteWrite(tokens) != null;
            if (!OPERATIONS.contains(token.word())) continue;
            return MUTATION.contains(token.word()) || DDL.contains(token.word());
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
        boolean atGroupStart = true;
        // Depth alone cannot tell two sibling groups apart, so every '(' gets a
        // fresh id that is never reused. It lets a scan stay inside the one
        // parenthesised statement it started in, e.g. the DELETE branch of
        // "WITH a AS (DELETE FROM t1), b AS (SELECT ... WHERE ...) ...".
        int nextGroup = 0;
        Deque<Integer> groups = new ArrayDeque<>();
        groups.push(0);
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
                groups.push(++nextGroup);
                atGroupStart = true;
                index++;
                continue;
            }
            if (ch == ')') {
                depth = Math.max(0, depth - 1);
                if (groups.size() > 1) groups.pop();
                atGroupStart = false;
                index++;
                continue;
            }
            if (Character.isLetter(ch) || ch == '_') {
                int end = index + 1;
                while (end < sql.length() && (Character.isLetterOrDigit(sql.charAt(end)) || sql.charAt(end) == '_' || sql.charAt(end) == '$')) end++;
                tokens.add(new Token(sql.substring(index, end).toUpperCase(Locale.ROOT), depth, atGroupStart, groups.peek()));
                atGroupStart = false;
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

    /**
     * @param groupStart whether this token is the first one inside the
     *                   parenthesised group it belongs to, i.e. it sits in
     *                   statement position rather than being an operand or a
     *                   function name.
     * @param group      identifies the parenthesised group this token belongs
     *                   to. Ids are unique per '(' occurrence, so two sibling
     *                   groups at the same depth never share one.
     */
    private record Token(String word, int depth, boolean groupStart, int group) {
    }

    private record Operation(String word, int index) {
    }
}
