package com.example.dbadmin.service;

import com.example.dbadmin.config.AppProperties;
import com.example.dbadmin.api.ApiProblemException;
import com.example.dbadmin.core.DatabaseDialect;
import com.example.dbadmin.core.DialectRegistry;
import com.example.dbadmin.dto.ApiDtos.DbObject;
import com.example.dbadmin.dto.ApiDtos.MetadataResponse;
import com.example.dbadmin.dto.ApiDtos.ResultColumn;
import com.example.dbadmin.dto.ApiDtos.ResultEditInfo;
import com.example.dbadmin.dto.ApiDtos.ResultSourceTable;
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
import org.springframework.http.HttpStatus;

import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.sql.Types;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class SqlService {
    private static final int DEFAULT_MAX_ROWS = 500;
    private static final int MAX_SCRIPT_RESULT_ROWS = 10_000;
    private static final int MAX_RESULT_CELLS = 200_000;
    private static final long MAX_RESULT_TEXT_CHARS = 20_000_000;
    private static final long MAX_BINARY_LENGTH_PROBE_BYTES = 1L << 20;
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
    private final DataEditService dataEdit;

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
            SqlExecutionRegistry executions,
            DataEditService dataEdit
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
        this.dataEdit = dataEdit;
    }

    public SqlResult execute(long connectionId, String sql, Integer requestedMaxRows, String actor, String executionId, String productionConfirmation, String schemaName) throws Exception {
        return execute(connectionId, sql, requestedMaxRows, actor, executionId, productionConfirmation, schemaName, false);
    }

    public SqlResult execute(long connectionId, String sql, Integer requestedMaxRows, String actor, String executionId, String productionConfirmation, String schemaName, boolean unscopedMutationConfirmed) throws Exception {
        String executionSql = singleStatement(sql, "单条执行");
        DbConnection dbConnection = connections.require(connectionId);
        executionGuard.requireQueryAllowed(dbConnection, classifier.classify(executionSql), productionConfirmation);
        requireUnscopedMutationConfirmation(
                connectionId,
                actor,
                List.of(new StatementSegment(executionSql, 0, executionSql.length())),
                unscopedMutationConfirmed
        );
        boolean metadataMutation = changesMetadata(executionSql);
        boolean sessionMutation = classifier.changesSession(executionSql);
        int maxRows = normalizeMaxRows(requestedMaxRows);
        long started = System.nanoTime();
        try (Connection connection = openConnection(connectionId, schemaName);
             ReadOnlyQueryScope ignored = ReadOnlyQueryScope.begin(connection, dbConnection.readonly());
             Statement statement = connection.createStatement()) {
            DatabaseDialect dialect = dialectRegistry.dialectFor(dbConnection);
            dialect.configureReadStatement(connection, statement, Math.min(maxRows + 1, 500), properties.getSql().getTimeoutSeconds());
            statement.setMaxRows(maxRows + 1);
            String registeredId = executions.register(executionId, connectionId, statement);
            try {
                boolean hasResult = statement.execute(executionSql);
                audit.onConnection(actor, "SQL_EXECUTE", connectionId, abbreviate(sql));
                if (!hasResult) {
                    long elapsedMs = elapsed(started);
                    SqlResult result = emptyResult(statement.getUpdateCount(), elapsedMs, maxRows);
                    history.insert(connectionId, sql, "EXECUTE", "SUCCESS", elapsedMs, null, actor);
                    return result;
                }
                try (ResultSet rs = statement.getResultSet()) {
                    SqlResult result = readResult(rs, started, maxRows, dialect);
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

    /**
     * Executes one query for a machine caller under a mandatory rollback-only,
     * read-only scope and tighter result limits. Production access is decided
     * by the caller's authorization policy rather than an interactive name
     * confirmation.
     */
    public SqlResult executeReadOnly(
            long connectionId,
            String sql,
            String schemaName,
            Integer requestedMaxRows,
            String actor,
            SqlQueryLimits limits
    ) throws Exception {
        String executionSql = singleStatement(sql, "MCP 查询");
        if (classifier.classify(executionSql) != SqlStatementClassifier.Kind.QUERY) {
            throw new IllegalArgumentException("MCP 只允许执行单条只读查询");
        }
        DbConnection dbConnection = connections.require(connectionId);
        int maxRows = limits.normalizeRows(requestedMaxRows);
        long started = System.nanoTime();
        try (Connection connection = openConnection(connectionId, schemaName);
             ReadOnlyQueryScope ignored = ReadOnlyQueryScope.begin(connection, true);
             Statement statement = connection.createStatement()) {
            DatabaseDialect dialect = dialectRegistry.dialectFor(dbConnection);
            dialect.configureReadStatement(connection, statement, Math.min(maxRows + 1, 200), limits.timeoutSeconds());
            statement.setMaxRows(maxRows + 1);
            boolean hasResult = statement.execute(executionSql);
            if (!hasResult) {
                throw new IllegalArgumentException("MCP 查询没有返回结果集，已拒绝该语句");
            }
            try (ResultSet rs = statement.getResultSet()) {
                SqlResult result = readResult(
                        rs,
                        started,
                        maxRows,
                        limits.maxCells(),
                        limits.maxTextChars(),
                        limits.maxCellTextChars(),
                        dialect
                );
                audit.onConnection(actor, "MCP_SQL_QUERY", connectionId, "read-only query");
                history.insert(connectionId, sql, "MCP_QUERY", "SUCCESS", result.elapsedMs(), null, actor);
                return result;
            }
        } catch (Exception e) {
            history.insert(connectionId, sql, "MCP_QUERY", "FAILED", elapsed(started), error(e), actor);
            throw e;
        }
    }

    public SqlResult execute(long connectionId, String sql, Integer requestedMaxRows, String actor) throws Exception {
        return execute(connectionId, sql, requestedMaxRows, actor, null, null, null);
    }

    public SqlResult execute(long connectionId, String sql, Integer requestedMaxRows, String actor, String executionId, String productionConfirmation) throws Exception {
        return execute(connectionId, sql, requestedMaxRows, actor, executionId, productionConfirmation, null);
    }

    public SqlScriptResponse executeScript(long connectionId, String sql, Integer requestedMaxRows, Integer requestedPageSize, String actor, String executionId, String productionConfirmation, String schemaName) throws Exception {
        return executeScript(connectionId, sql, requestedMaxRows, requestedPageSize, actor, executionId, productionConfirmation, schemaName, false);
    }

    public SqlScriptResponse executeScript(long connectionId, String sql, Integer requestedMaxRows, Integer requestedPageSize, String actor, String executionId, String productionConfirmation, String schemaName, boolean unscopedMutationConfirmed) throws Exception {
        List<StatementSegment> statements = scriptSplitter.split(sql);
        if (statements.isEmpty()) throw new IllegalArgumentException("请输入要执行的 SQL");
        int maxStatements = Math.max(1, properties.getSql().getMaxStatements());
        if (statements.size() > maxStatements) {
            throw new IllegalArgumentException("一次最多执行 " + maxStatements + " 条 SQL；更大的脚本请使用“执行本地 SQL 文件”。");
        }

        DbConnection dbConnection = connections.require(connectionId);
        for (StatementSegment statement : statements) {
            executionGuard.requireQueryAllowed(dbConnection, classifier.classify(statement.sql()), productionConfirmation);
        }
        requireUnscopedMutationConfirmation(connectionId, actor, statements, unscopedMutationConfirmed);

        if (requestedPageSize != null && statements.size() == 1 && classifier.isAutomaticallyPageable(statements.get(0).sql())) {
            long started = System.nanoTime();
            try {
                SqlResult result = executePage(connectionId, statements.get(0).sql(), 0, requestedPageSize, actor, executionId, productionConfirmation, schemaName);
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

        try (Connection connection = openConnection(connectionId, schemaName);
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
                                        Math.max(0, MAX_RESULT_TEXT_CHARS - returnedTextChars),
                                        dialect
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
            audit.onConnection(actor, "SQL_EXECUTE_SCRIPT", connectionId, abbreviate(sql));
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
        return executeScript(connectionId, sql, requestedMaxRows, null, actor, null, null, null);
    }

    public SqlScriptResponse executeScript(long connectionId, String sql, Integer requestedMaxRows, String actor, String executionId, String productionConfirmation) throws Exception {
        return executeScript(connectionId, sql, requestedMaxRows, null, actor, executionId, productionConfirmation, null);
    }

    public SqlScriptResponse executeScript(long connectionId, String sql, Integer requestedMaxRows, Integer requestedPageSize, String actor, String executionId, String productionConfirmation) throws Exception {
        return executeScript(connectionId, sql, requestedMaxRows, requestedPageSize, actor, executionId, productionConfirmation, null);
    }

    private void requireUnscopedMutationConfirmation(
            long connectionId,
            String actor,
            List<StatementSegment> statements,
            boolean confirmed
    ) {
        List<Map<String, Object>> unsafeStatements = new ArrayList<>();
        for (int index = 0; index < statements.size(); index++) {
            StatementSegment statement = statements.get(index);
            if (!classifier.requiresUnscopedMutationConfirmation(statement.sql())) continue;
            unsafeStatements.add(Map.of(
                    "index", index + 1,
                    "sql", abbreviate(statement.sql())
            ));
        }
        if (unsafeStatements.isEmpty()) return;
        if (!confirmed) {
            throw new ApiProblemException(
                    HttpStatus.CONFLICT,
                    "UNSCOPED_MUTATION_CONFIRMATION_REQUIRED",
                    "检测到未包含顶层 WHERE 条件的 UPDATE/DELETE，可能影响整张表。",
                    Map.of("statements", unsafeStatements)
            );
        }
        audit.onConnection(
                actor,
                "SQL_UNSCOPED_MUTATION_CONFIRMED",
                connectionId,
                "statements=" + unsafeStatements.size()
        );
    }

    public SqlResult executePage(
            long connectionId,
            String sql,
            Integer requestedOffset,
            Integer requestedPageSize,
            String actor,
            String executionId,
            String productionConfirmation,
            String schemaName
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
        try (Connection connection = openConnection(connectionId, schemaName);
             ReadOnlyQueryScope ignored = ReadOnlyQueryScope.begin(connection, dbConnection.readonly());
             Statement statement = connection.createStatement()) {
            dialect.configureReadStatement(connection, statement, Math.min(pageSize + 1, 500), properties.getSql().getTimeoutSeconds());
            statement.setMaxRows(pageSize + 1);
            String registeredId = executions.register(executionId, connectionId, statement);
            try {
                try (ResultSet rs = statement.executeQuery(pageSql)) {
                    SqlResult result = readPageResult(
                            rs, started, connectionId, offset, rawPageSize, pageSize,
                            dialect.paginationHelperColumn(), schemaName, dialect, connection, dbConnection,
                            executionSql
                    );
                    audit.onConnection(actor, "SQL_QUERY_PAGE", connectionId, "offset=" + offset + "; " + abbreviate(sql));
                    return result;
                }
            } finally {
                executions.unregister(registeredId, statement);
            }
        }
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
        return executePage(connectionId, sql, requestedOffset, requestedPageSize, actor, executionId, productionConfirmation, null);
    }

    public SqlResult explain(long connectionId, String sql, String actor, String productionConfirmation, String schemaName) throws Exception {
        long started = System.nanoTime();
        String executionSql = singleStatement(sql, "执行计划");
        DbConnection dbConnection = connections.require(connectionId);
        DatabaseDialect dialect = dialectRegistry.dialectFor(dbConnection);
        if (!dialect.capabilities().explain()) {
            throw new IllegalStateException("当前数据库类型暂不支持执行计划。");
        }
        if (!classifier.isQuery(executionSql)) throw new IllegalArgumentException("执行计划只支持查询语句");
        executionGuard.requireQueryAllowed(dbConnection, SqlStatementClassifier.Kind.QUERY, productionConfirmation);
        try (Connection connection = openConnection(connectionId, schemaName);
             ReadOnlyQueryScope ignored = ReadOnlyQueryScope.begin(connection, dbConnection.readonly())) {
            SqlResult result = dialect.explain(connection, executionSql, properties.getSql().getMaxRows(), properties.getSql().getTimeoutSeconds());
            audit.onConnection(actor, "SQL_EXPLAIN", connectionId, abbreviate(sql));
            history.insert(connectionId, sql, "EXPLAIN", "SUCCESS", elapsed(started), null, actor);
            return result;
        } catch (Exception e) {
            history.insert(connectionId, sql, "EXPLAIN", "FAILED", elapsed(started), error(e), actor);
            throw e;
        }
    }

    public SqlResult explainReadOnly(
            long connectionId,
            String sql,
            String schemaName,
            String actor,
            SqlQueryLimits limits
    ) throws Exception {
        long started = System.nanoTime();
        String executionSql = singleStatement(sql, "MCP 执行计划");
        DbConnection dbConnection = connections.require(connectionId);
        DatabaseDialect dialect = dialectRegistry.dialectFor(dbConnection);
        if (!dialect.capabilities().explain()) {
            throw new IllegalStateException("当前数据库类型不支持执行计划");
        }
        if (!classifier.isQuery(executionSql)) {
            throw new IllegalArgumentException("执行计划只支持查询语句");
        }
        try (Connection connection = openConnection(connectionId, schemaName);
             ReadOnlyQueryScope ignored = ReadOnlyQueryScope.begin(connection, true)) {
            SqlResult result = dialect.explain(
                    connection,
                    executionSql,
                    limits.normalizeRows(null),
                    limits.timeoutSeconds()
            );
            audit.onConnection(actor, "MCP_SQL_EXPLAIN", connectionId, "read-only explain");
            history.insert(connectionId, sql, "MCP_EXPLAIN", "SUCCESS", result.elapsedMs(), null, actor);
            return result;
        } catch (Exception e) {
            history.insert(connectionId, sql, "MCP_EXPLAIN", "FAILED", elapsed(started), error(e), actor);
            throw e;
        }
    }

    public SqlResult explain(long connectionId, String sql, String actor) throws Exception {
        return explain(connectionId, sql, actor, null, null);
    }

    public SqlResult explain(long connectionId, String sql, String actor, String productionConfirmation) throws Exception {
        return explain(connectionId, sql, actor, productionConfirmation, null);
    }

    public boolean cancel(String executionId) throws Exception {
        return executions.cancel(executionId);
    }

    public List<SqlHistoryResponse> history(long connectionId, Integer limit) {
        return history(connectionId, null, limit);
    }

    public List<SqlHistoryResponse> history(long connectionId, String keyword, Integer limit) {
        return history.findRecent(connectionId, keyword, limit == null ? 50 : limit);
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

    private static final SqlFormatter FORMATTER = new SqlFormatter();

    public String format(String sql) {
        return FORMATTER.format(sql);
    }

    /** 手动事务复用同一套结果读取与限额，见 SqlTransactionService。 */
    SqlResult readResult(ResultSet rs, long startedNanos, int maxRows, DatabaseDialect dialect) throws Exception {
        return readResult(rs, startedNanos, maxRows, MAX_RESULT_CELLS, MAX_RESULT_TEXT_CHARS, dialect);
    }

    private SqlResult readPageResult(
            ResultSet rs,
            long startedNanos,
            long connectionId,
            int offset,
            int requestedPageSize,
            int pageSize,
            String helperColumn,
            String schemaName,
            DatabaseDialect dialect,
            Connection connection,
            DbConnection dbConnection,
            String executionSql
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
        ResultSourceTable sourceTable = ResultSetSourceResolver.resolve(metadata, dialect);
        EditableResult editable = editableResult(connection, dbConnection, sourceTable, metadata, columnCount, executionSql);
        List<List<Object>> rows = new ArrayList<>();
        List<String> rowKeyTokens = new ArrayList<>();
        long textChars = 0;
        boolean payloadLimitReached = false;
        while (rows.size() < effectivePageSize && rs.next()) {
            List<Object> row = new ArrayList<>(columnCount);
            for (int index = 1; index <= columnCount; index++) {
                int remainingText = (int) Math.min(MAX_CELL_TEXT_CHARS, Math.max(0, MAX_RESULT_TEXT_CHARS - textChars));
                Object value = serializableValue(rs, metadata, index, remainingText);
                row.add(value);
                if (value instanceof CharSequence text) textChars += text.length();
            }
            rows.add(row);
            // editable 非空但 locator 为空表示「不可编辑且有原因」，这时不发令牌。
            if (editable != null && editable.locator() != null) rowKeyTokens.add(editable.locator().encode(rs));
            if (textChars >= MAX_RESULT_TEXT_CHARS) {
                payloadLimitReached = true;
                break;
            }
        }
        boolean hasMore = payloadLimitReached || rs.next();
        SqlPageInfo page = new SqlPageInfo(connectionId, offset, requestedPageSize, effectivePageSize, hasMore, schemaName);
        return new SqlResult(
                columns,
                rows,
                -1,
                elapsed(startedNanos),
                true,
                effectivePageSize,
                payloadLimitReached,
                page,
                sourceTable,
                editInfo(editable, rowKeyTokens, sourceTable)
        );
    }

    /**
     * 判断这段结果能不能就地编辑，并在可以时准备好行定位令牌生成器。
     *
     * <p>任何一步失败都只是「不可编辑」，绝不能让一次成功的查询因为编辑能力探测而报错 ——
     * 权限不足读不到主键元数据是很常见的情况。</p>
     */
    private EditableResult editableResult(
            Connection connection,
            DbConnection dbConnection,
            ResultSourceTable sourceTable,
            ResultSetMetaData metadata,
            int visibleColumnCount,
            String executionSql
    ) {
        if (sourceTable == null || sourceTable.nameParts().isEmpty()) return null;
        // 别名会让「界面上的这一列」与「表里的哪个字段」对不上，而 JDBC 元数据分辨不出别名，
        // 详见 SelectProjection。分辨不了就不给编辑，否则可能定位到另一行、写错另一个字段。
        if (!SelectProjection.isDirectColumnProjection(executionSql)) {
            return new EditableResult(null, "查询结果使用了别名或表达式，无法对应到表字段");
        }
        List<String> parts = sourceTable.nameParts();
        String table = parts.get(parts.size() - 1);
        String schema = parts.size() > 1 ? parts.get(parts.size() - 2) : null;
        // ResultSetSourceResolver 会跳过没有报告表名的列（表达式、别名、部分驱动），这对推断
        // 导出目标表是合适的宽松度，但用来决定「能不能改这张表」就太松了：一次 JOIN 里只要有
        // 一侧的列没报表名，就会被当成单表来源。编辑路径要求每一个可见列都明确属于同一张表。
        if (!everyColumnBelongsTo(metadata, visibleColumnCount, table)) {
            return new EditableResult(null, "查询结果不是来自单张表，无法定位行");
        }
        try {
            DataEditService.ResultRowLocator locator = dataEdit.resultRowLocator(
                    connection, dbConnection, schema, table, metadata, visibleColumnCount
            );
            if (locator == null || !locator.editable()) {
                return new EditableResult(null, locator == null ? "无法确认行定位字段" : locator.reason());
            }
            return new EditableResult(locator, null);
        } catch (Exception error) {
            return new EditableResult(null, "无法确认行定位字段：" + abbreviate(error.getMessage()));
        }
    }

    private boolean everyColumnBelongsTo(ResultSetMetaData metadata, int visibleColumnCount, String table) {
        try {
            for (int index = 1; index <= visibleColumnCount; index++) {
                String candidate = metadata.getTableName(index);
                if (candidate == null || candidate.isBlank() || !candidate.equalsIgnoreCase(table)) return false;
            }
            return true;
        } catch (Exception error) {
            return false;
        }
    }

    private ResultEditInfo editInfo(EditableResult editable, List<String> rowKeyTokens, ResultSourceTable sourceTable) {
        if (editable == null) {
            return ResultEditInfo.notEditable(sourceTable == null ? "查询结果不是来自单张表" : "无法确定结果来源表");
        }
        if (editable.locator() == null) return ResultEditInfo.notEditable(editable.reason());
        return new ResultEditInfo(
                true,
                editable.locator().schemaName(),
                editable.locator().tableName(),
                editable.locator().keyColumns(),
                rowKeyTokens,
                null
        );
    }

    private record EditableResult(DataEditService.ResultRowLocator locator, String reason) {
    }

    private SqlResult readResult(ResultSet rs, long startedNanos, int maxRows, int cellBudget, long textBudget, DatabaseDialect dialect) throws Exception {
        return readResult(rs, startedNanos, maxRows, cellBudget, textBudget, MAX_CELL_TEXT_CHARS, dialect);
    }

    private SqlResult readResult(
            ResultSet rs,
            long startedNanos,
            int maxRows,
            int cellBudget,
            long textBudget,
            int cellTextBudget,
            DatabaseDialect dialect
    ) throws Exception {
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
                int remainingText = (int) Math.min(cellTextBudget, Math.max(0, textBudget - textChars));
                Object value = serializableValue(rs, metadata, index, remainingText);
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
        return new SqlResult(
                columns,
                rows,
                -1,
                elapsed(startedNanos),
                true,
                effectiveMaxRows,
                truncated,
                null,
                ResultSetSourceResolver.resolve(metadata, dialect)
        );
    }

    int sqlTimeoutSeconds() {
        return properties.getSql().getTimeoutSeconds();
    }

    SqlResult emptyResult(int affectedRows, long elapsedMs, int maxRows) {
        return new SqlResult(List.of(), List.of(), affectedRows, elapsedMs, false, maxRows, false);
    }

    private Connection openConnection(long connectionId, String schemaName) throws Exception {
        return schemaName == null || schemaName.isBlank()
                ? connections.open(connectionId)
                : connections.open(connectionId, schemaName);
    }

    int normalizeMaxRows(Integer requestedMaxRows) {
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

    boolean changesMetadata(String sql) {
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
            return truncateText(CellValues.text(value), "", maxTextChars);
        }
        return truncateText(CellValues.text(value), "", maxTextChars);
    }

    private Object serializableValue(ResultSet rs, ResultSetMetaData metadata, int index, int maxTextChars) throws Exception {
        int jdbcType = metadata.getColumnType(index);
        if (Set.of(Types.BLOB, Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY).contains(jdbcType)) {
            String description = binaryDescription(rs, index);
            return description == null ? null : truncateText(description, "", maxTextChars);
        }
        return serializableValue(rs.getObject(index), maxTextChars);
    }

    private String binaryDescription(ResultSet rs, int index) throws Exception {
        try {
            Blob blob = rs.getBlob(index);
            if (blob != null) return "<BINARY " + blob.length() + " bytes>";
            if (rs.wasNull()) return null;
        } catch (Exception ignored) {
            // Some drivers expose VARBINARY only through a binary stream.
        }
        try (InputStream input = rs.getBinaryStream(index)) {
            if (input == null) return null;
            return describeBinaryStream(input);
        }
    }

    static String describeBinaryStream(InputStream input) throws Exception {
        byte[] buffer = new byte[16 * 1024];
        long total = 0;
        int read;
        while (total <= MAX_BINARY_LENGTH_PROBE_BYTES && (read = input.read(buffer)) >= 0) total += read;
        return total > MAX_BINARY_LENGTH_PROBE_BYTES
                ? "<BINARY > 1 MB>"
                : "<BINARY " + total + " bytes>";
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

}
