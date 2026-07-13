package com.example.dbadmin.service;

import com.example.dbadmin.config.AppProperties;
import com.example.dbadmin.core.DatabaseDialect;
import com.example.dbadmin.core.DialectRegistry;
import com.example.dbadmin.dto.ApiDtos.DbObject;
import com.example.dbadmin.dto.ApiDtos.MetadataResponse;
import com.example.dbadmin.dto.ApiDtos.ResultColumn;
import com.example.dbadmin.dto.ApiDtos.SqlCompletionItem;
import com.example.dbadmin.dto.ApiDtos.SqlCompletionRequest;
import com.example.dbadmin.dto.ApiDtos.SqlHistoryResponse;
import com.example.dbadmin.dto.ApiDtos.SqlPageInfo;
import com.example.dbadmin.dto.ApiDtos.SqlResult;
import com.example.dbadmin.dto.ApiDtos.SqlScriptResponse;
import com.example.dbadmin.dto.ApiDtos.SqlStatementResult;
import com.example.dbadmin.model.DbConnection;
import com.example.dbadmin.repo.AuditRepository;
import com.example.dbadmin.repo.SqlHistoryRepository;
import com.example.dbadmin.service.SqlScriptSplitter.StatementSegment;
import org.springframework.stereotype.Service;

import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class SqlService {
    private static final int DEFAULT_MAX_ROWS = 500;
    private static final int MAX_SCRIPT_RESULT_ROWS = 10_000;
    private static final int MAX_RESULT_CELLS = 200_000;
    private static final long MAX_RESULT_TEXT_CHARS = 20_000_000;
    private static final int MAX_CELL_TEXT_CHARS = 100_000;
    private final ConnectionService connections;
    private final AppProperties properties;
    private final AuditRepository audit;
    private final DialectRegistry dialectRegistry;
    private final SqlHistoryRepository history;
    private final MetadataService metadata;
    private final SqlScriptSplitter scriptSplitter;
    private final SqlStatementClassifier classifier;
    private final ExecutionGuard executionGuard;
    private final SqlExecutionRegistry executions;

    public SqlService(
            ConnectionService connections,
            AppProperties properties,
            AuditRepository audit,
            DialectRegistry dialectRegistry,
            SqlHistoryRepository history,
            MetadataService metadata,
            SqlScriptSplitter scriptSplitter,
            SqlStatementClassifier classifier,
            ExecutionGuard executionGuard,
            SqlExecutionRegistry executions
    ) {
        this.connections = connections;
        this.properties = properties;
        this.audit = audit;
        this.dialectRegistry = dialectRegistry;
        this.history = history;
        this.metadata = metadata;
        this.scriptSplitter = scriptSplitter;
        this.classifier = classifier;
        this.executionGuard = executionGuard;
        this.executions = executions;
    }

    public SqlResult execute(long connectionId, String sql, Integer requestedMaxRows, String actor, String executionId, String productionConfirmation) throws Exception {
        String executionSql = singleStatement(sql, "单条执行");
        DbConnection dbConnection = connections.require(connectionId);
        executionGuard.requireQueryAllowed(dbConnection, classifier.classify(executionSql), productionConfirmation);
        boolean metadataMutation = changesMetadata(executionSql);
        boolean sessionMutation = classifier.changesSession(executionSql);
        int maxRows = normalizeMaxRows(requestedMaxRows);
        long started = System.nanoTime();
        try (Connection connection = connections.open(connectionId);
             ReadOnlyQueryScope ignored = ReadOnlyQueryScope.begin(connection, dbConnection.readonly());
             Statement statement = connection.createStatement()) {
            DatabaseDialect dialect = dialectRegistry.dialectFor(dbConnection);
            dialect.configureReadStatement(connection, statement, Math.min(maxRows + 1, 500), properties.getSql().getTimeoutSeconds());
            statement.setMaxRows(maxRows + 1);
            String registeredId = executions.register(executionId, connectionId, statement);
            try {
                boolean hasResult = statement.execute(executionSql);
                audit.log(actor, "SQL_EXECUTE", "connection:" + connectionId, abbreviate(sql));
                if (!hasResult) {
                    long elapsedMs = elapsed(started);
                    SqlResult result = emptyResult(statement.getUpdateCount(), elapsedMs, maxRows);
                    history.insert(connectionId, sql, "EXECUTE", "SUCCESS", elapsedMs, null, actor);
                    return result;
                }
                try (ResultSet rs = statement.getResultSet()) {
                    SqlResult result = readResult(rs, started, maxRows);
                    history.insert(connectionId, sql, "EXECUTE", "SUCCESS", result.elapsedMs(), null, actor);
                    return result;
                }
            } finally {
                executions.unregister(registeredId, statement);
            }
        } catch (Exception e) {
            long elapsedMs = elapsed(started);
            history.insert(connectionId, sql, "EXECUTE", "FAILED", elapsedMs, error(e), actor);
            throw e;
        } finally {
            // Some databases auto-commit part of a DDL statement before
            // reporting an error. Any attempted DDL makes cached metadata
            // unsafe, regardless of the JDBC outcome.
            if (metadataMutation) metadata.invalidateConnection(connectionId);
            if (sessionMutation) connections.resetRemoteSession(connectionId);
        }
    }

    public SqlResult execute(long connectionId, String sql, Integer requestedMaxRows, String actor) throws Exception {
        return execute(connectionId, sql, requestedMaxRows, actor, null, null);
    }

    public SqlScriptResponse executeScript(long connectionId, String sql, Integer requestedMaxRows, Integer requestedPageSize, String actor, String executionId, String productionConfirmation) throws Exception {
        List<StatementSegment> statements = scriptSplitter.split(sql);
        if (statements.isEmpty()) throw new IllegalArgumentException("请输入要执行的 SQL");
        if (statements.size() > 50) throw new IllegalArgumentException("一次最多执行 50 条 SQL");

        if (requestedPageSize != null && statements.size() == 1 && classifier.isAutomaticallyPageable(statements.get(0).sql())) {
            long started = System.nanoTime();
            try {
                SqlResult result = executePage(connectionId, statements.get(0).sql(), 0, requestedPageSize, actor, executionId, productionConfirmation);
                long elapsedMs = elapsed(started);
                history.insert(connectionId, sql, "EXECUTE_SCRIPT", "SUCCESS", elapsedMs, null, actor);
                SqlStatementResult statementResult = new SqlStatementResult(
                        1, statements.get(0).sql(), statements.get(0).startOffset(), statements.get(0).endOffset(), "SUCCESS", null, result
                );
                return new SqlScriptResponse("SUCCESS", elapsedMs, 1, List.of(statementResult), false);
            } catch (Exception e) {
                history.insert(connectionId, sql, "EXECUTE_SCRIPT", "FAILED", elapsed(started), error(e), actor);
                throw e;
            }
        }

        DbConnection dbConnection = connections.require(connectionId);
        for (StatementSegment statement : statements) {
            executionGuard.requireQueryAllowed(dbConnection, classifier.classify(statement.sql()), productionConfirmation);
        }
        int maxRows = normalizeMaxRows(requestedMaxRows);
        long scriptStarted = System.nanoTime();
        List<SqlStatementResult> results = new ArrayList<>();
        String status = "SUCCESS";
        String errorMessage = null;
        boolean metadataChanged = false;
        boolean sessionChanged = statements.stream().anyMatch(statement -> classifier.changesSession(statement.sql()));
        int returnedRows = 0;
        int returnedCells = 0;
        long returnedTextChars = 0;

        try (Connection connection = connections.open(connectionId);
             ReadOnlyQueryScope ignored = ReadOnlyQueryScope.begin(connection, dbConnection.readonly());
             Statement jdbc = connection.createStatement()) {
            DatabaseDialect dialect = dialectRegistry.dialectFor(dbConnection);
            dialect.configureReadStatement(connection, jdbc, Math.min(maxRows + 1, 500), properties.getSql().getTimeoutSeconds());
            String registeredId = executions.register(executionId, connectionId, jdbc);
            try {
                for (int index = 0; index < statements.size(); index++) {
                    StatementSegment statement = statements.get(index);
                    long statementStarted = System.nanoTime();
                    int remaining = Math.max(0, MAX_SCRIPT_RESULT_ROWS - returnedRows);
                    int statementLimit = Math.min(maxRows, remaining);
                    jdbc.setMaxRows(Math.max(1, statementLimit + 1));
                    boolean statementChangesMetadata = changesMetadata(statement.sql());
                    metadataChanged = metadataChanged || statementChangesMetadata;
                    try {
                        boolean hasResult = jdbc.execute(statement.sql());
                        SqlResult result;
                        if (hasResult) {
                            try (ResultSet rs = jdbc.getResultSet()) {
                                result = readResult(
                                        rs,
                                        statementStarted,
                                        statementLimit,
                                        Math.max(0, MAX_RESULT_CELLS - returnedCells),
                                        Math.max(0, MAX_RESULT_TEXT_CHARS - returnedTextChars)
                                );
                                returnedRows += result.rows().size();
                                returnedCells += result.rows().size() * result.columns().size();
                                returnedTextChars += textChars(result);
                            }
                        } else {
                            result = emptyResult(jdbc.getUpdateCount(), elapsed(statementStarted), maxRows);
                        }
                        results.add(new SqlStatementResult(index + 1, statement.sql(), statement.startOffset(), statement.endOffset(), "SUCCESS", null, result));
                    } catch (Exception e) {
                        long elapsedMs = elapsed(statementStarted);
                        errorMessage = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                        results.add(new SqlStatementResult(index + 1, statement.sql(), statement.startOffset(), statement.endOffset(), "FAILED", abbreviate(errorMessage), emptyResult(-1, elapsedMs, maxRows)));
                        status = "FAILED";
                        break;
                    }
                }
            } finally {
                executions.unregister(registeredId, jdbc);
            }
            long elapsedMs = elapsed(scriptStarted);
            audit.log(actor, "SQL_EXECUTE_SCRIPT", "connection:" + connectionId, abbreviate(sql));
            history.insert(connectionId, sql, "EXECUTE_SCRIPT", status, elapsedMs, errorMessage == null ? null : abbreviate(errorMessage), actor);
            return new SqlScriptResponse(status, elapsedMs, results.size(), results, metadataChanged);
        } catch (Exception e) {
            long elapsedMs = elapsed(scriptStarted);
            history.insert(connectionId, sql, "EXECUTE_SCRIPT", "FAILED", elapsedMs, error(e), actor);
            throw e;
        } finally {
            if (metadataChanged) metadata.invalidateConnection(connectionId);
            if (sessionChanged) connections.resetRemoteSession(connectionId);
        }
    }

    public SqlScriptResponse executeScript(long connectionId, String sql, Integer requestedMaxRows, String actor) throws Exception {
        return executeScript(connectionId, sql, requestedMaxRows, null, actor, null, null);
    }

    public SqlScriptResponse executeScript(long connectionId, String sql, Integer requestedMaxRows, String actor, String executionId, String productionConfirmation) throws Exception {
        return executeScript(connectionId, sql, requestedMaxRows, null, actor, executionId, productionConfirmation);
    }

    public SqlResult executePage(
            long connectionId,
            String sql,
            Integer requestedOffset,
            Integer requestedPageSize,
            String actor,
            String executionId,
            String productionConfirmation
    ) throws Exception {
        String executionSql = singleStatement(sql, "分页查询");
        if (!classifier.isAutomaticallyPageable(executionSql)) {
            throw new IllegalArgumentException("当前 SQL 不支持自动分页；仅支持未自带分页子句的单条 SELECT。");
        }
        int offset = requestedOffset == null ? 0 : requestedOffset;
        int maxOffset = Math.max(properties.getSql().getMaxPageOffset(), 0);
        if (offset < 0 || offset > maxOffset) {
            throw new IllegalArgumentException("查询偏移量必须在 0 到 " + maxOffset + " 之间。");
        }
        int rawPageSize = requestedPageSize == null ? DEFAULT_MAX_ROWS : Math.max(requestedPageSize, 1);
        int pageSize = Math.min(normalizeMaxRows(rawPageSize), MAX_RESULT_CELLS);
        DbConnection dbConnection = connections.require(connectionId);
        executionGuard.requireQueryAllowed(dbConnection, SqlStatementClassifier.Kind.QUERY, productionConfirmation);
        DatabaseDialect dialect = dialectRegistry.dialectFor(dbConnection);
        String pageSql = dialect.pageQuery(executionSql, pageSize + 1, offset);
        long started = System.nanoTime();
        try (Connection connection = connections.open(connectionId);
             ReadOnlyQueryScope ignored = ReadOnlyQueryScope.begin(connection, dbConnection.readonly());
             Statement statement = connection.createStatement()) {
            dialect.configureReadStatement(connection, statement, Math.min(pageSize + 1, 500), properties.getSql().getTimeoutSeconds());
            statement.setMaxRows(pageSize + 1);
            String registeredId = executions.register(executionId, connectionId, statement);
            try {
                try (ResultSet rs = statement.executeQuery(pageSql)) {
                    SqlResult result = readPageResult(rs, started, connectionId, offset, rawPageSize, pageSize, dialect.paginationHelperColumn());
                    audit.log(actor, "SQL_QUERY_PAGE", "connection:" + connectionId, "offset=" + offset + "; " + abbreviate(sql));
                    return result;
                }
            } finally {
                executions.unregister(registeredId, statement);
            }
        }
    }

    public SqlResult explain(long connectionId, String sql, String actor, String productionConfirmation) throws Exception {
        long started = System.nanoTime();
        String executionSql = singleStatement(sql, "执行计划");
        DbConnection dbConnection = connections.require(connectionId);
        DatabaseDialect dialect = dialectRegistry.dialectFor(dbConnection);
        if (!dialect.capabilities().explain()) {
            throw new IllegalStateException("当前数据库类型暂不支持执行计划。");
        }
        if (!classifier.isQuery(executionSql)) throw new IllegalArgumentException("执行计划只支持查询语句");
        executionGuard.requireQueryAllowed(dbConnection, SqlStatementClassifier.Kind.QUERY, productionConfirmation);
        try (Connection connection = connections.open(connectionId);
             ReadOnlyQueryScope ignored = ReadOnlyQueryScope.begin(connection, dbConnection.readonly())) {
            SqlResult result = dialect.explain(connection, executionSql, properties.getSql().getMaxRows(), properties.getSql().getTimeoutSeconds());
            audit.log(actor, "SQL_EXPLAIN", "connection:" + connectionId, abbreviate(sql));
            history.insert(connectionId, sql, "EXPLAIN", "SUCCESS", elapsed(started), null, actor);
            return result;
        } catch (Exception e) {
            history.insert(connectionId, sql, "EXPLAIN", "FAILED", elapsed(started), error(e), actor);
            throw e;
        }
    }

    public SqlResult explain(long connectionId, String sql, String actor) throws Exception {
        return explain(connectionId, sql, actor, null);
    }

    public boolean cancel(String executionId) throws Exception {
        return executions.cancel(executionId);
    }

    public List<SqlHistoryResponse> history(long connectionId, Integer limit) {
        return history.findRecent(connectionId, limit == null ? 50 : limit);
    }

    public List<SqlCompletionItem> completions(SqlCompletionRequest request) {
        List<SqlCompletionItem> items = new ArrayList<>();
        for (String keyword : sqlKeywords()) items.add(new SqlCompletionItem(keyword, "KEYWORD", keyword, "SQL 关键字"));
        try {
            MetadataResponse response = metadata.inspect(request.connectionId(), null, null, 0, 100, false);
            Set<String> schemas = new LinkedHashSet<>(response.schemas());
            for (String schema : schemas) items.add(new SqlCompletionItem(schema, "SCHEMA", schema, "数据库 Schema"));
            for (DbObject object : response.objects()) {
                String tableLabel = object.schemaName() == null || object.schemaName().isBlank() ? object.name() : object.schemaName() + "." + object.name();
                items.add(new SqlCompletionItem(tableLabel, "TABLE", tableLabel, "数据库" + objectTypeLabel(object.type())));
            }
        } catch (Exception ignored) {
            // Keyword completion remains available when metadata is unavailable.
        }
        return items.stream().limit(200).toList();
    }

    public String format(String sql) {
        if (sql == null || sql.isBlank()) return "";
        ProtectedSql protectedSql = protectFormatLiterals(sql);
        String formatted = protectedSql.masked().trim().replaceAll("\\s+", " ");
        // Multi-word clauses must run before their suffixes (for example JOIN),
        // otherwise formatting LEFT JOIN would split the phrase too early.
        String[] keywords = {
                "left outer join", "right outer join", "full outer join",
                "left join", "right join", "inner join", "full join", "cross join",
                "group by", "order by", "union all",
                "select", "from", "where", "join", "having", "limit", "offset",
                "union", "insert", "update", "delete", "merge", "values", "set"
        };
        Pattern clauses = Pattern.compile("(?i)\\b(?:" + String.join("|", keywords).replace(" ", "\\s+") + ")\\b");
        Matcher matcher = clauses.matcher(formatted);
        StringBuffer clauseFormatted = new StringBuffer(formatted.length() + 64);
        while (matcher.find()) {
            String clause = matcher.group().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
            matcher.appendReplacement(clauseFormatted, Matcher.quoteReplacement("\n" + clause));
        }
        matcher.appendTail(clauseFormatted);
        formatted = clauseFormatted.toString().replaceAll("[ \\t]*\n[ \\t]*", "\n");
        for (int index = 0; index < protectedSql.values().size(); index++) {
            formatted = formatted.replace(protectedSql.marker(index), protectedSql.values().get(index));
        }
        return formatted.trim();
    }

    private ProtectedSql protectFormatLiterals(String sql) {
        String markerPrefix = "\uE000";
        while (sql.contains(markerPrefix)) markerPrefix += "\uE000";
        List<String> values = new ArrayList<>();
        StringBuilder masked = new StringBuilder(sql.length());
        for (int index = 0; index < sql.length();) {
            int end = protectedFormatSegmentEnd(sql, index);
            if (end <= index) {
                masked.append(sql.charAt(index++));
                continue;
            }
            values.add(sql.substring(index, end));
            masked.append(markerPrefix).append(values.size() - 1).append('\uE001');
            index = end;
        }
        return new ProtectedSql(masked.toString(), markerPrefix, values);
    }

    private int protectedFormatSegmentEnd(String sql, int index) {
        char current = sql.charAt(index);
        char next = index + 1 < sql.length() ? sql.charAt(index + 1) : '\0';
        if (current == '-' && next == '-') return lineCommentEnd(sql, index + 2);
        if (current == '/' && next == '*') return blockCommentEnd(sql, index + 2);
        if ((current == 'q' || current == 'Q') && next == '\'' && index + 2 < sql.length()) {
            return oracleQuoteEnd(sql, index);
        }
        if (current == '$') {
            String delimiter = dollarQuoteDelimiter(sql, index);
            if (delimiter != null) {
                int closing = sql.indexOf(delimiter, index + delimiter.length());
                return closing < 0 ? sql.length() : closing + delimiter.length();
            }
        }
        return switch (current) {
            case '\'', '"', '`' -> quotedSegmentEnd(sql, index + 1, current);
            case '[' -> bracketSegmentEnd(sql, index + 1);
            default -> index;
        };
    }

    private int lineCommentEnd(String sql, int index) {
        while (index < sql.length() && sql.charAt(index) != '\n' && sql.charAt(index) != '\r') index++;
        if (index < sql.length() && sql.charAt(index) == '\r') index++;
        if (index < sql.length() && sql.charAt(index) == '\n') index++;
        return index;
    }

    private int blockCommentEnd(String sql, int index) {
        int depth = 1;
        while (index < sql.length()) {
            if (index + 1 < sql.length() && sql.charAt(index) == '/' && sql.charAt(index + 1) == '*') {
                depth++;
                index += 2;
            } else if (index + 1 < sql.length() && sql.charAt(index) == '*' && sql.charAt(index + 1) == '/') {
                depth--;
                index += 2;
                if (depth == 0) return index;
            } else {
                index++;
            }
        }
        return sql.length();
    }

    private int quotedSegmentEnd(String sql, int index, char quote) {
        while (index < sql.length()) {
            char current = sql.charAt(index);
            if (current == quote) {
                if (index + 1 < sql.length() && sql.charAt(index + 1) == quote) {
                    index += 2;
                    continue;
                }
                return index + 1;
            }
            if (current == '\\' && index + 1 < sql.length()) index += 2;
            else index++;
        }
        return sql.length();
    }

    private int bracketSegmentEnd(String sql, int index) {
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

    private int oracleQuoteEnd(String sql, int index) {
        char opening = sql.charAt(index + 2);
        char closing = switch (opening) {
            case '[' -> ']';
            case '(' -> ')';
            case '{' -> '}';
            case '<' -> '>';
            default -> opening;
        };
        int closingIndex = sql.indexOf(String.valueOf(closing) + '\'', index + 3);
        return closingIndex < 0 ? sql.length() : closingIndex + 2;
    }

    private String dollarQuoteDelimiter(String sql, int index) {
        int end = sql.indexOf('$', index + 1);
        if (end < 0) return null;
        String tag = sql.substring(index + 1, end);
        if (!tag.isEmpty() && !tag.matches("[A-Za-z_][A-Za-z0-9_]*")) return null;
        return sql.substring(index, end + 1);
    }

    private SqlResult readResult(ResultSet rs, long startedNanos, int maxRows) throws Exception {
        return readResult(rs, startedNanos, maxRows, MAX_RESULT_CELLS, MAX_RESULT_TEXT_CHARS);
    }

    private SqlResult readPageResult(
            ResultSet rs,
            long startedNanos,
            long connectionId,
            int offset,
            int requestedPageSize,
            int pageSize,
            String helperColumn
    ) throws Exception {
        ResultSetMetaData metadata = rs.getMetaData();
        int columnCount = metadata.getColumnCount();
        if (helperColumn != null && columnCount > 0 && helperColumn.equalsIgnoreCase(metadata.getColumnLabel(columnCount))) {
            columnCount--;
        }
        List<ResultColumn> columns = new ArrayList<>();
        for (int index = 1; index <= columnCount; index++) {
            columns.add(new ResultColumn("c" + index, metadata.getColumnLabel(index), metadata.getColumnTypeName(index)));
        }
        int effectivePageSize = Math.min(pageSize, MAX_RESULT_CELLS / Math.max(columnCount, 1));
        List<List<Object>> rows = new ArrayList<>();
        long textChars = 0;
        boolean payloadLimitReached = false;
        while (rows.size() < effectivePageSize && rs.next()) {
            List<Object> row = new ArrayList<>(columnCount);
            for (int index = 1; index <= columnCount; index++) {
                int remainingText = (int) Math.min(MAX_CELL_TEXT_CHARS, Math.max(0, MAX_RESULT_TEXT_CHARS - textChars));
                Object value = serializableValue(rs.getObject(index), remainingText);
                row.add(value);
                if (value instanceof CharSequence text) textChars += text.length();
            }
            rows.add(row);
            if (textChars >= MAX_RESULT_TEXT_CHARS) {
                payloadLimitReached = true;
                break;
            }
        }
        boolean hasMore = payloadLimitReached || rs.next();
        SqlPageInfo page = new SqlPageInfo(connectionId, offset, requestedPageSize, effectivePageSize, hasMore);
        return new SqlResult(columns, rows, -1, elapsed(startedNanos), true, effectivePageSize, payloadLimitReached, page);
    }

    private SqlResult readResult(ResultSet rs, long startedNanos, int maxRows, int cellBudget, long textBudget) throws Exception {
        ResultSetMetaData metadata = rs.getMetaData();
        int columnCount = metadata.getColumnCount();
        List<ResultColumn> columns = new ArrayList<>();
        for (int index = 1; index <= columnCount; index++) {
            columns.add(new ResultColumn("c" + index, metadata.getColumnLabel(index), metadata.getColumnTypeName(index)));
        }
        int effectiveMaxRows = textBudget <= 0
                ? 0
                : Math.min(Math.max(maxRows, 0), Math.max(cellBudget, 0) / Math.max(columnCount, 1));
        List<List<Object>> rows = new ArrayList<>();
        long textChars = 0;
        boolean payloadLimitReached = false;
        while (rows.size() < effectiveMaxRows && rs.next()) {
            List<Object> row = new ArrayList<>(columnCount);
            for (int index = 1; index <= columnCount; index++) {
                int remainingText = (int) Math.min(MAX_CELL_TEXT_CHARS, Math.max(0, textBudget - textChars));
                Object value = serializableValue(rs.getObject(index), remainingText);
                row.add(value);
                if (value instanceof CharSequence text) textChars += text.length();
            }
            rows.add(row);
            if (textChars >= textBudget) {
                payloadLimitReached = true;
                break;
            }
        }
        boolean truncated = payloadLimitReached || rs.next();
        return new SqlResult(columns, rows, -1, elapsed(startedNanos), true, effectiveMaxRows, truncated);
    }

    private SqlResult emptyResult(int affectedRows, long elapsedMs, int maxRows) {
        return new SqlResult(List.of(), List.of(), affectedRows, elapsedMs, false, maxRows, false);
    }

    private int normalizeMaxRows(Integer requestedMaxRows) {
        int configuredMaximum = Math.max(properties.getSql().getMaxRows(), 1);
        int requested = requestedMaxRows == null ? Math.min(DEFAULT_MAX_ROWS, configuredMaximum) : requestedMaxRows;
        return Math.min(Math.max(requested, 1), configuredMaximum);
    }

    private String singleStatement(String sql, String action) {
        List<StatementSegment> statements = scriptSplitter.split(sql);
        if (statements.size() != 1) {
            throw new IllegalArgumentException(action + "仅支持一条 SQL；多条语句请使用脚本执行接口。");
        }
        return statements.get(0).sql();
    }

    private boolean changesMetadata(String sql) {
        return classifier.classify(sql) == SqlStatementClassifier.Kind.DDL;
    }

    private Object serializableValue(Object value, int maxTextChars) throws Exception {
        if (value == null) return null;
        if (value instanceof Clob clob) {
            long length = clob.length();
            int visible = (int) Math.min(length, Math.min(10_000, Math.max(maxTextChars, 0)));
            String text = visible == 0 ? "" : clob.getSubString(1, visible);
            return length > visible ? truncateText(text, "… <CLOB 已截断，共 " + length + " 字符>", maxTextChars) : text;
        }
        if (value instanceof Blob blob) return truncateText("<BLOB " + blob.length() + " bytes>", "", maxTextChars);
        if (value instanceof byte[] bytes) return truncateText("<BINARY " + bytes.length + " bytes>", "", maxTextChars);
        // JSON numbers are parsed as IEEE-754 doubles by the browser. Preserve
        // BIGINT identity and DECIMAL scale by transferring them as strings.
        if (value instanceof Long || value instanceof BigInteger || value instanceof BigDecimal) {
            return truncateText(value.toString(), "", maxTextChars);
        }
        if (value instanceof CharSequence text) {
            String string = text.toString();
            return string.length() > maxTextChars
                    ? truncateText(string, "… <文本已截断，共 " + string.length() + " 字符>", maxTextChars)
                    : string;
        }
        if (value instanceof Float number && !Float.isFinite(number)) return number.toString();
        if (value instanceof Double number && !Double.isFinite(number)) return number.toString();
        if (value instanceof Number || value instanceof Boolean) return value;
        if (value instanceof java.util.Date || value instanceof java.time.temporal.TemporalAccessor || value instanceof UUID) {
            return truncateText(value.toString(), "", maxTextChars);
        }
        return truncateText(value.toString(), "", maxTextChars);
    }

    private String truncateText(String prefixSource, String marker, int maxChars) {
        if (maxChars <= 0) return "";
        if (prefixSource.length() <= maxChars && marker.isEmpty()) return prefixSource;
        if (marker.length() >= maxChars) return prefixSource.substring(0, Math.min(prefixSource.length(), maxChars));
        int prefixLength = Math.min(prefixSource.length(), maxChars - marker.length());
        return prefixSource.substring(0, prefixLength) + marker;
    }

    private long textChars(SqlResult result) {
        long count = 0;
        for (List<Object> row : result.rows()) {
            for (Object value : row) {
                if (value instanceof CharSequence text) count += text.length();
            }
        }
        return count;
    }

    private long elapsed(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    private String error(Exception e) {
        return abbreviate(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
    }

    private String abbreviate(String value) {
        if (value == null) return "";
        return value.length() <= 2_000 ? value : value.substring(0, 2_000);
    }

    private List<String> sqlKeywords() {
        return List.of(
                "SELECT", "FROM", "WHERE", "JOIN", "LEFT JOIN", "RIGHT JOIN", "INNER JOIN", "GROUP BY", "ORDER BY", "HAVING",
                "LIMIT", "OFFSET", "INSERT", "INTO", "VALUES", "UPDATE", "SET", "DELETE", "CREATE", "ALTER", "DROP", "TABLE",
                "VIEW", "INDEX", "PRIMARY KEY", "AND", "OR", "NOT", "NULL", "IS", "IN", "BETWEEN", "LIKE", "COUNT", "SUM", "AVG", "MIN", "MAX"
        );
    }

    private String objectTypeLabel(String type) {
        return type != null && type.toUpperCase(Locale.ROOT).contains("VIEW") ? "视图" : "表";
    }

    private record ProtectedSql(String masked, String markerPrefix, List<String> values) {
        private String marker(int index) {
            return markerPrefix + index + '\uE001';
        }
    }
}
