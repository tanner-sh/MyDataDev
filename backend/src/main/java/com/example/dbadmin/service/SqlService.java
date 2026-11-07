package com.example.dbadmin.service;

import com.example.dbadmin.config.AppProperties;
import com.example.dbadmin.api.ApiProblemException;
import com.example.dbadmin.core.DatabaseDialect;
import com.example.dbadmin.core.DialectRegistry;
import com.example.dbadmin.dto.ApiDtos.ResultColumn;
import com.example.dbadmin.dto.ApiDtos.ResultSourceTable;
import com.example.dbadmin.dto.ApiDtos.SqlHistoryResponse;
import com.example.dbadmin.dto.ApiDtos.SqlPageInfo;
import com.example.dbadmin.dto.ApiDtos.SqlResultFilter;
import com.example.dbadmin.dto.ApiDtos.SqlResult;
import com.example.dbadmin.dto.ApiDtos.SqlScriptResponse;
import com.example.dbadmin.dto.ApiDtos.SqlStatementResult;
import com.example.dbadmin.model.DbConnection;
import com.example.dbadmin.repo.AuditRepository;
import com.example.dbadmin.repo.SqlHistoryRepository;
import com.example.dbadmin.service.SqlScriptSplitter.StatementSegment;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class SqlService {
    private static final int DEFAULT_MAX_ROWS = 500;

    private static final int MAX_SCRIPT_RESULT_ROWS = 10_000;

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

    private final ResultEditability editability;

    private final SqlExecutionMetrics metrics;

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
            DataEditService dataEdit,
            SqlExecutionMetrics metrics
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
        this.editability = new ResultEditability(dialectRegistry, dataEdit);
        this.metrics = metrics;
    }

    public SqlResult execute(long connectionId, String sql, Integer requestedMaxRows, String actor, String executionId, String productionConfirmation, String schemaName) throws Exception {
        return execute(connectionId, sql, requestedMaxRows, actor, executionId, productionConfirmation, schemaName, false);
    }

    public SqlResult execute(long connectionId, String sql, Integer requestedMaxRows, String actor, String executionId, String productionConfirmation, String schemaName, boolean unscopedMutationConfirmed) throws Exception {
        return executeBound(connectionId, sql, requestedMaxRows, actor, executionId, productionConfirmation, schemaName, unscopedMutationConfirmed, null, null);
    }

    /** MCP 的 db_execute 走这里：超时由 MCP 设置决定，其余（生产确认、审计、历史、缓存失效）与界面同一条路。 */
    public SqlResult execute(long connectionId, String sql, Integer requestedMaxRows, String actor, String executionId, String productionConfirmation,
                             String schemaName, boolean unscopedMutationConfirmed, int timeoutSeconds) throws Exception {
        return executeBound(connectionId, sql, requestedMaxRows, actor, executionId, productionConfirmation, schemaName, unscopedMutationConfirmed,
                null, Math.max(1, timeoutSeconds));
    }

    /**
     * 刚执行完的那条语句如果建/重编译了例程类对象，去字典表里取它的编译错误。
     *
     * <p>换一条连接查也拿得到：DDL 已经自动提交，字典表对所有会话可见。没写 schema 的对象落在
     * {@code schemaName}（执行时激活的那个）或登录用户名下，这里按同样的顺序解析。</p>
     */
    public List<com.example.dbadmin.dto.ApiDtos.CompilationError> compilationErrors(long connectionId, String schemaName, String sql) {
        try {
            DatabaseDialect dialect = dialectRegistry.dialectFor(connections.require(connectionId));
            if (dialect.compilationErrorsSql() == null || CompilationDiagnostics.target(sql).isEmpty()) return List.of();
            try (Connection connection = openConnection(connectionId, schemaName)) {
                return CompilationDiagnostics.read(connection, dialect, sql, schemaName, properties.getSql().getTimeoutSeconds());
            }
        } catch (Exception ignored) {
            return List.of();
        }
    }

    public SqlResult executeParameterized(com.example.dbadmin.dto.ApiDtos.SqlParameterizedRequest request, String actor, String productionConfirmation) throws Exception {
        SqlParameters.Parsed parsed = SqlParameters.parse(request.sql(), connections.require(request.connectionId()).dbType());
        SqlParameters.validateValues(parsed, request.parameters());
        boolean wrappingSupported = !classifier.isCommonTableExpression(parsed.sql())
                || dialectRegistry.dialectFor(connections.require(request.connectionId())).supportsCteResultPushdown();
        if (request.pageSize() != null && wrappingSupported && classifier.isAutomaticallyPageable(parsed.sql())) {
            long started = System.nanoTime();
            try {
                var result = executePage(request.connectionId(), request.sql(), 0, request.pageSize(), actor, request.executionId(),
                        productionConfirmation, request.schemaName(), null, null, List.of(), request.parameters());
                history.insert(request.connectionId(), request.sql(), "EXECUTE", "SUCCESS", result.elapsedMs(), null, actor);
                return result;
            } catch (Exception error) {
                history.insert(request.connectionId(), request.sql(), "EXECUTE", "FAILED", elapsed(started), "参数查询执行失败（参数值不记录）", actor);
                throw error;
            }
        }
        return executeBound(request.connectionId(), request.sql(), request.maxRows(), actor, request.executionId(), productionConfirmation,
                request.schemaName(), false, request.parameters(), null);
    }

    private SqlResult executeBound(long connectionId, String sql, Integer requestedMaxRows, String actor, String executionId,
            String productionConfirmation, String schemaName, boolean unscopedMutationConfirmed,
            Map<String, com.example.dbadmin.dto.ApiDtos.SqlParameter> parameters, Integer timeoutSeconds) throws Exception {
        SqlParameters.Parsed parsed = parameters == null ? null : SqlParameters.parse(sql, connections.require(connectionId).dbType());
        String executionSql = singleStatement(connectionId, parsed == null ? sql : parsed.sql(), "单条执行");
        if (parsed != null && !classifier.isParameterizedSelectQuery(executionSql)) throw new IllegalArgumentException("参数化执行只支持单条 SELECT 查询。");

        DbConnection dbConnection = connections.require(connectionId);
        executionGuard.requireQueryAllowed(dbConnection, classifier.classify(executionSql), productionConfirmation);
        requireUnscopedMutationConfirmation(
                connectionId,
                actor,
                List.of(new StatementSegment(executionSql, 0, executionSql.length())),
                unscopedMutationConfirmed
        );
        boolean metadataMutation = changesMetadata(executionSql);
        boolean sessionMutation = classifier.changesSession(executionSql) || SqlScriptSyntax.opaqueBlock(executionSql);
        int maxRows = normalizeMaxRows(requestedMaxRows);
        long started = System.nanoTime();
        try (Connection connection = openConnection(connectionId, schemaName);
             ReadOnlyQueryScope ignored = ReadOnlyQueryScope.begin(connection, parsed != null || dbConnection.readonly());
             Statement statement = parsed == null ? connection.createStatement() : connection.prepareStatement(executionSql);
             var cancellation = RequestCancellation.watch(statement)) {
            if (sessionMutation) connections.discardOnClose(connection);
            DatabaseDialect dialect = dialectRegistry.dialectFor(dbConnection);
            dialect.configureReadStatement(connection, statement, Math.min(maxRows + 1, 500),
                    timeoutSeconds == null ? properties.getSql().getTimeoutSeconds() : timeoutSeconds);
            statement.setMaxRows(maxRows + 1);
            String registeredId = executions.register(executionId, connectionId, statement);
            try {
                if (parsed != null) SqlParameters.bind((java.sql.PreparedStatement) statement, parsed, parameters);
                RequestCancellation.check();
                boolean hasResult = parsed == null ? statement.execute(executionSql) : ((java.sql.PreparedStatement) statement).execute();
                audit.onConnection(actor, "SQL_EXECUTE", connectionId, abbreviate(sql));
                if (!hasResult) {
                    long elapsedMs = elapsed(started);
                    SqlResult result = emptyResult(statement.getUpdateCount(), elapsedMs, maxRows);
                    history.insert(connectionId, sql, "EXECUTE", "SUCCESS", elapsedMs, null, actor);
                    metrics.success(SqlExecutionMetrics.KIND_QUERY, started);
                    return result;
                }
                try (ResultSet rs = statement.getResultSet()) {
                    SqlResult result = parsed != null ? SqlResultReader.readResult(rs, started, maxRows, dialect) : SqlResultReader.withColumnSources(SqlResultReader.readResult(rs, started, maxRows, dialect), rs, connection,
                            dbConnection, connectionId, schemaName, executionSql, dialect);
                    history.insert(connectionId, sql, "EXECUTE", "SUCCESS", result.elapsedMs(), null, actor);
                    metrics.success(SqlExecutionMetrics.KIND_QUERY, started);
                    return result;
                }
            } catch (Exception error) {
                throw executions.translateCancel(registeredId, statement, error);
            } finally {
                executions.unregister(registeredId, statement);
            }
        } catch (Exception e) {
            long elapsedMs = elapsed(started);
            history.insert(connectionId, sql, "EXECUTE", "FAILED", elapsedMs, parsed == null ? error(e) : "参数查询执行失败（参数值不记录）", actor);
            metrics.failure(SqlExecutionMetrics.KIND_QUERY, started, e);
            throw e;
        } finally {
            // Some databases auto-commit part of a DDL statement before
            // reporting an error. Any attempted DDL makes cached metadata
            // unsafe, regardless of the JDBC outcome.
            if (metadataMutation) metadata.invalidateConnection(connectionId);
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
        return executeReadOnly(connectionId, sql, schemaName, requestedMaxRows, null, actor, limits);
    }

    /**
     * 同上，带 {@code offset} 时用方言的分页包装跳过前面的行。
     *
     * <p>机器调用方拿到截断的结果时只有两条路：调大 maxRows（受上限约束）或改写 SQL。前者走不通时它往往
     * 会把截断后的那一部分当成全量，得出「缺了几列」这种错误结论；给它一个能接着取的偏移量，比让它猜好。</p>
     */
    public SqlResult executeReadOnly(
            long connectionId,
            String sql,
            String schemaName,
            Integer requestedMaxRows,
            Integer requestedOffset,
            String actor,
            SqlQueryLimits limits
    ) throws Exception {
        String executionSql = singleStatement(connectionId, sql, "MCP 查询");
        if (classifier.classify(executionSql) != SqlStatementClassifier.Kind.QUERY) {
            throw new IllegalArgumentException("MCP 只允许执行单条只读查询");
        }
        DbConnection dbConnection = connections.require(connectionId);
        DatabaseDialect pagingDialect = dialectRegistry.dialectFor(dbConnection);
        int maxRows = limits.normalizeRows(requestedMaxRows);
        int offset = requestedOffset == null ? 0 : requestedOffset;
        int maxOffset = Math.max(properties.getSql().getMaxPageOffset(), 0);
        if (offset < 0 || offset > maxOffset) throw new IllegalArgumentException("offset 必须在 0 到 " + maxOffset + " 之间。");
        String runSql = executionSql;
        String helperColumn = null;
        if (offset > 0) {
            if (!classifier.isAutomaticallyPageable(executionSql)
                    || (classifier.isCommonTableExpression(executionSql) && !pagingDialect.supportsCteResultPushdown())) {
                throw new IllegalArgumentException("offset 只支持未自带分页子句的单条 SELECT；请在 SQL 里自行分页，或去掉 offset。");
            }
            // 与结果分页同一条包装路径：先过 EmbeddableSql，原文末尾停在行注释里时分页子句才不会落进注释。
            runSql = pagingDialect.pageQuery(
                    SqlResultPushdown.apply(EmbeddableSql.of(executionSql), List.of(), null, null, pagingDialect).sql(), maxRows + 1, offset);
            helperColumn = pagingDialect.paginationHelperColumn();
        }
        long started = System.nanoTime();
        try (Connection connection = openConnection(connectionId, schemaName);
             ReadOnlyQueryScope ignored = ReadOnlyQueryScope.begin(connection, true);
             Statement statement = connection.createStatement();
             var cancellation = RequestCancellation.watch(statement)) {
            DatabaseDialect dialect = pagingDialect;
            dialect.configureReadStatement(connection, statement, Math.min(maxRows + 1, 200), limits.timeoutSeconds());
            statement.setMaxRows(maxRows + 1);
            RequestCancellation.check();
            boolean hasResult = statement.execute(runSql);
            if (!hasResult) {
                throw new IllegalArgumentException("MCP 查询没有返回结果集，已拒绝该语句");
            }
            try (ResultSet rs = statement.getResultSet()) {
                SqlResult result = SqlResultReader.readResult(
                        rs,
                        started,
                        maxRows,
                        limits.maxCells(),
                        limits.maxTextChars(),
                        limits.maxCellTextChars(),
                        dialect
                );
                audit.onConnection(actor, "MCP_SQL_QUERY", connectionId, offset > 0 ? "read-only query; offset=" + offset : "read-only query");
                result = SqlResultReader.withoutHelperColumn(SqlResultReader.withColumnSources(result, rs, connection, dbConnection, connectionId, schemaName, executionSql, dialect), helperColumn);
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
        return executeScript(connectionId, sql, requestedMaxRows, requestedPageSize, actor, executionId, productionConfirmation, schemaName, unscopedMutationConfirmed, null, null);
    }

    public SqlScriptResponse executeScript(long connectionId, String sql, Integer requestedMaxRows, Integer requestedPageSize, String actor, String executionId, String productionConfirmation, String schemaName, boolean unscopedMutationConfirmed, String transactionMode, String endOfFileAction) throws Exception {
        // 老入口：分隔方式固定为自动，不做分隔符识别也不问大脚本 —— 行为与这两项加入之前完全一致。
        return executeScript(connectionId, sql, requestedMaxRows, requestedPageSize, actor, executionId, productionConfirmation, schemaName,
                unscopedMutationConfirmed, transactionMode, endOfFileAction, SqlScriptDelimiter.AUTO, true, true);
    }

    /** 交互入口在要权限之前先过分隔符识别：DBeaver 那种脚本按默认规则根本切不开，权限检查会先报错。 */
    public void requireDelimiterDecision(long connectionId, String sql, SqlScriptDelimiter delimiter, boolean delimiterChecked) {
        scriptSplitter.requireDelimiterDecision(sql, connections.require(connectionId).dbType(), delimiter, delimiterChecked);
    }

    /**
     * 脚本入口的闸门切分：按连接的真实库类型切一次，同一份结果交给权限检查与无 WHERE 写操作确认。
     * 脚本里的 SQL*Plus 指令（含 WHENEVER SQLERROR CONTINUE）在这里只是被跳过，允不允许由执行那一步判定。
     */
    public List<StatementSegment> gateUnits(long connectionId, String sql, SqlScriptDelimiter delimiter) {
        return scriptSplitter.gateUnits(sql, connections.require(connectionId).dbType(), delimiter);
    }

    public SqlScriptResponse executeScript(long connectionId, String sql, Integer requestedMaxRows, Integer requestedPageSize, String actor, String executionId, String productionConfirmation, String schemaName, boolean unscopedMutationConfirmed, String transactionMode, String endOfFileAction,
                                           SqlScriptDelimiter delimiter, boolean delimiterChecked, boolean largeScriptConfirmed) throws Exception {
        boolean scriptMode = "SCRIPT".equals(transactionMode);
        if (transactionMode != null && !java.util.Set.of("SCRIPT", "AUTO").contains(transactionMode)) throw new IllegalArgumentException("不支持的窗口事务模式。");
        String ending = endOfFileAction == null ? "ROLLBACK" : endOfFileAction;
        if (!java.util.Set.of("COMMIT", "ROLLBACK").contains(ending)) throw new IllegalArgumentException("不支持的脚本结束策略。");
        if (scriptMode) {
            connections.requireNoManualTransaction(connectionId);
            executionGuard.requireMutationAllowed(connections.require(connectionId), productionConfirmation);
        }
        if (scriptMode && !SqlScriptSyntax.scriptTransactionsSupported(connections.require(connectionId).dbType())) {
            throw new IllegalArgumentException("脚本事务模式目前支持 Oracle 和 OceanBase Oracle。");
        }
        SqlScriptSplitter.Split split = scriptSplitter.parseInteractive(sql, connections.require(connectionId).dbType(), delimiter, delimiterChecked);
        List<StatementSegment> statements = split.segments();
        if (statements.isEmpty()) throw new IllegalArgumentException("请输入要执行的 SQL");
        int maxStatements = Math.max(1, properties.getSql().getMaxStatements());
        if (statements.size() > maxStatements) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, "SCRIPT_TOO_LARGE",
                    "脚本共 " + statements.size() + " 条，工作台一次最多执行 " + maxStatements + " 条；请改用后台执行。",
                    java.util.Map.of("statementCount", statements.size(), "limit", maxStatements));
        }
        int largeThreshold = Math.max(1, properties.getSql().getLargeScriptThreshold());
        if (!largeScriptConfirmed && statements.size() > largeThreshold) {
            // 同步执行期间界面没有进度，几百条语句跑完之前一直是「执行中」；后台执行有进度、能取消。
            throw new ApiProblemException(HttpStatus.CONFLICT, "LARGE_SCRIPT_CONFIRMATION_REQUIRED",
                    "脚本共 " + statements.size() + " 条语句，建议改用后台执行：有进度、可取消，界面不会被占住。",
                    java.util.Map.of("statementCount", statements.size(), "threshold", largeThreshold));
        }

        DbConnection dbConnection = connections.require(connectionId);
        List<StatementSegment> checkedStatements = java.util.Set.of("sqlserver", "sql-server", "mssql").contains(dbConnection.dbType().toLowerCase(java.util.Locale.ROOT))
                ? statements.stream().flatMap(unit -> scriptSplitter.split(unit.sql()).stream()).toList()
                : scriptSplitter.classificationUnits(statements, dbConnection.dbType(), delimiter);
        for (StatementSegment statement : checkedStatements) {
            if (scriptMode) SqlScriptSyntax.validateScriptControl(statement.sql());
            else if (SqlScriptSyntax.transaction(statement.sql()) != SqlScriptSyntax.Transaction.NONE) {
                throw new IllegalArgumentException("脚本含事务控制语句，请选择脚本控制事务模式，或使用手动事务的提交/回滚按钮。");
            }
            executionGuard.requireQueryAllowed(dbConnection, classifier.classify(statement.sql()), productionConfirmation);
        }
        requireUnscopedMutationConfirmation(connectionId, actor, checkedStatements, unscopedMutationConfirmed);

        if (!scriptMode && requestedPageSize != null && statements.size() == 1 && checkedStatements.size() == 1 && classifier.isAutomaticallyPageable(statements.get(0).sql())) {
            long started = System.nanoTime();
            try {
                SqlResult result = executePage(connectionId, statements.get(0).sql(), 0, requestedPageSize, actor, executionId, productionConfirmation, schemaName);
                long elapsedMs = elapsed(started);
                history.insert(connectionId, sql, "EXECUTE_SCRIPT", "SUCCESS", elapsedMs, null, actor);
                SqlStatementResult statementResult = new SqlStatementResult(
                        1, statements.get(0).sql(), statements.get(0).startOffset(), statements.get(0).endOffset(), "SUCCESS", null, result
                );
                return new SqlScriptResponse("SUCCESS", elapsedMs, 1, List.of(statementResult), false).withNotices(split.notices());
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
        boolean metadataChanged = checkedStatements.stream().anyMatch(statement -> changesMetadata(statement.sql()));
        boolean sessionChanged = checkedStatements.stream().anyMatch(statement -> classifier.changesSession(statement.sql()) || SqlScriptSyntax.opaqueBlock(statement.sql()));
        int returnedRows = 0;
        int returnedCells = 0;
        long returnedTextChars = 0;

        try (Connection connection = openConnection(connectionId, schemaName);
             ReadOnlyQueryScope ignored = ReadOnlyQueryScope.begin(connection, dbConnection.readonly());
             SqlScriptTransaction tx = scriptMode ? new SqlScriptTransaction(connection, () -> { }) : null;
             Statement jdbc = connection.createStatement()) {
            if (sessionChanged) connections.discardOnClose(connection);
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
                        boolean hasResult = tx == null ? jdbc.execute(statement.sql()) : tx.execute(jdbc, statement.sql(), index + 1);
                        SqlResult result;
                        if (hasResult) {
                            try (ResultSet rs = jdbc.getResultSet()) {
                                result = SqlResultReader.readResult(
                                        rs,
                                        statementStarted,
                                        statementLimit,
                                        Math.max(0, SqlResultReader.MAX_RESULT_CELLS - returnedCells),
                                        Math.max(0, SqlResultReader.MAX_RESULT_TEXT_CHARS - returnedTextChars),
                                        dialect
                                );
                                result = SqlResultReader.withColumnSources(result, rs, connection, dbConnection, connectionId, schemaName, statement.sql(), dialect);
                                returnedRows += result.rows().size();
                                returnedCells += result.rows().size() * result.columns().size();
                                returnedTextChars += SqlResultReader.textChars(result);
                            }
                        } else {
                            result = emptyResult(jdbc.getUpdateCount(), elapsed(statementStarted), maxRows);
                        }
                        results.add(new SqlStatementResult(results.size() + 1, statement.sql(), statement.startOffset(), statement.endOffset(), "SUCCESS", null, result));
                        // SQL Server GO preserves a batch; consume all its results instead of losing later SELECTs.
                        if (java.util.Set.of("sqlserver", "sql-server", "mssql").contains(dbConnection.dbType().toLowerCase(java.util.Locale.ROOT))) {
                            while (true) {
                                boolean more = jdbc.getMoreResults(Statement.CLOSE_CURRENT_RESULT);
                                if (!more && jdbc.getUpdateCount() == -1) break;
                                if (results.size() >= maxStatements) throw new IllegalArgumentException("批次返回结果超过窗口上限，请使用 SQL 文件任务。");
                                SqlResult nextResult;
                                if (more) {
                                    try (ResultSet rs = jdbc.getResultSet()) {
                                        nextResult = SqlResultReader.readResult(rs, statementStarted, Math.min(maxRows, Math.max(0, MAX_SCRIPT_RESULT_ROWS - returnedRows)),
                                                Math.max(0, SqlResultReader.MAX_RESULT_CELLS - returnedCells), Math.max(0, SqlResultReader.MAX_RESULT_TEXT_CHARS - returnedTextChars), dialect);
                                        returnedRows += nextResult.rows().size();
                                        returnedCells += nextResult.rows().size() * nextResult.columns().size();
                                        returnedTextChars += SqlResultReader.textChars(nextResult);
                                    }
                                } else nextResult = emptyResult(jdbc.getUpdateCount(), elapsed(statementStarted), maxRows);
                                results.add(new SqlStatementResult(results.size() + 1, statement.sql(), statement.startOffset(), statement.endOffset(), "SUCCESS", null, nextResult));
                            }
                        }
                    } catch (Exception e) {
                        if (tx != null) tx.fail(e);
                        long elapsedMs = elapsed(statementStarted);
                        errorMessage = (executions.cancelRequested(registeredId, jdbc) ? "执行已取消，数据库已中止这条语句。"
                                : e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()) + (tx == null ? "" : "；" + tx.summary());
                        results.add(new SqlStatementResult(results.size() + 1, statement.sql(), statement.startOffset(), statement.endOffset(), "FAILED", abbreviate(errorMessage), emptyResult(-1, elapsedMs, maxRows)));
                        status = "FAILED";
                        break;
                    }
                }
            } finally {
                executions.unregister(registeredId, jdbc);
            }
            if (tx != null && "SUCCESS".equals(status)) {
                try { tx.finish("COMMIT".equals(ending), statements.size()); }
                catch (Exception error) { tx.fail(error); throw new IllegalStateException("脚本收尾失败；" + tx.summary(), error); }
            }
            long elapsedMs = elapsed(scriptStarted);
            audit.onConnection(actor, "SQL_EXECUTE_SCRIPT", connectionId, abbreviate(sql));
            history.insert(connectionId, sql, "EXECUTE_SCRIPT", status, elapsedMs, errorMessage == null ? null : abbreviate(errorMessage), actor);
            // 脚本跑完了但中间某条语句失败，同样按失败计 —— 用户眼里那就是一次没跑成的执行。
            if ("SUCCESS".equals(status)) metrics.success(SqlExecutionMetrics.KIND_SCRIPT, scriptStarted);
            else metrics.failure(SqlExecutionMetrics.KIND_SCRIPT, scriptStarted, null);
            return new SqlScriptResponse(status, elapsedMs, results.size(), results, metadataChanged,
                    tx == null ? null : tx.summary()).withNotices(split.notices());
        } catch (Exception e) {
            long elapsedMs = elapsed(scriptStarted);
            history.insert(connectionId, sql, "EXECUTE_SCRIPT", "FAILED", elapsedMs, error(e), actor);
            metrics.failure(SqlExecutionMetrics.KIND_SCRIPT, scriptStarted, e);
            throw e;
        } finally {
            if (metadataChanged) metadata.invalidateConnection(connectionId);
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

    public void requireUnscopedMutationConfirmation(
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
        return executePage(connectionId, sql, requestedOffset, requestedPageSize, actor, executionId,
                productionConfirmation, schemaName, null, null, List.of());
    }

    public SqlResult executePage(
            long connectionId,
            String sql,
            Integer requestedOffset,
            Integer requestedPageSize,
            String actor,
            String executionId,
            String productionConfirmation,
            String schemaName,
            String sortColumn,
            String sortDirection
    ) throws Exception {
        return executePage(connectionId, sql, requestedOffset, requestedPageSize, actor, executionId,
                productionConfirmation, schemaName, sortColumn, sortDirection, List.of());
    }

    /**
     * 分页取一批结果，可选按某一列排序。
     *
     * <p>排序下推进 SQL，而不是让界面排当前这一页：结果本来就是分页的，只排一页会让用户以为
     * 整个结果集有序。排序变化时由调用方把 offset 归零 —— 换了顺序，第 3 页就不是原来那批行了。</p>
     */
    public SqlResult executePage(
            long connectionId,
            String sql,
            Integer requestedOffset,
            Integer requestedPageSize,
            String actor,
            String executionId,
            String productionConfirmation,
            String schemaName,
            String sortColumn,
            String sortDirection,
            List<SqlResultFilter> filters
    ) throws Exception {
        return executePage(connectionId, sql, requestedOffset, requestedPageSize, actor, executionId,
                productionConfirmation, schemaName, sortColumn, sortDirection, filters, null);
    }

    public SqlResult executePage(long connectionId, String sql, Integer requestedOffset, Integer requestedPageSize,
            String actor, String executionId, String productionConfirmation, String schemaName, String sortColumn,
            String sortDirection, List<SqlResultFilter> filters, Map<String, com.example.dbadmin.dto.ApiDtos.SqlParameter> parameters) throws Exception {
        SqlParameters.Parsed parsed = parameters == null ? null : SqlParameters.parse(sql, connections.require(connectionId).dbType());
        if (parsed != null) SqlParameters.validateValues(parsed, parameters);
        String executionSql = singleStatement(connectionId, parsed == null ? sql : parsed.sql(), "分页查询");
        if (parsed != null && !classifier.isParameterizedSelectQuery(executionSql)) throw new IllegalArgumentException("参数化分页只支持 SELECT 查询。");
        if (!classifier.isAutomaticallyPageable(executionSql)) {
            throw new IllegalArgumentException("当前 SQL 不支持自动分页；仅支持未自带分页子句的单条 SELECT。");
        }
        int offset = requestedOffset == null ? 0 : requestedOffset;
        int maxOffset = Math.max(properties.getSql().getMaxPageOffset(), 0);
        if (offset < 0 || offset > maxOffset) {
            throw new IllegalArgumentException("查询偏移量必须在 0 到 " + maxOffset + " 之间。");
        }
        int rawPageSize = requestedPageSize == null ? DEFAULT_MAX_ROWS : Math.max(requestedPageSize, 1);
        int pageSize = Math.min(normalizeMaxRows(rawPageSize), SqlResultReader.MAX_RESULT_CELLS);
        DbConnection dbConnection = connections.require(connectionId);
        executionGuard.requireQueryAllowed(dbConnection, SqlStatementClassifier.Kind.QUERY, productionConfirmation);
        DatabaseDialect dialect = dialectRegistry.dialectFor(dbConnection);
        String normalizedSortColumn = sortColumn == null || sortColumn.isBlank() ? null : sortColumn.trim();
        String normalizedSortDirection = normalizedSortColumn == null
                ? null : SqlResultPushdown.normalizeDirection(sortDirection);
        List<SqlResultFilter> normalizedFilters = filters == null ? List.of() : filters;
        // 筛选和排序包在里面、分页包在外面：方言只需要照常处理一条没有分页子句的 SELECT。
        // 先过 EmbeddableSql：没有筛选也没有排序时 apply 会原样返回，那条 SQL 会被方言直接
        // 追加分页子句 —— 原文末尾停在一行注释里的话，LIMIT/OFFSET 会整段落进注释，翻页于是
        // 每一页都返回第一页且不报错。
        SqlResultPushdown.Shaped shaped = SqlResultPushdown.apply(
                EmbeddableSql.of(executionSql), normalizedFilters, normalizedSortColumn, normalizedSortDirection, dialect);
        String pageSql = dialect.pageQuery(shaped.sql(), pageSize + 1, offset);
        long started = System.nanoTime();
        try (Connection connection = openConnection(connectionId, schemaName);
             ReadOnlyQueryScope ignored = ReadOnlyQueryScope.begin(connection, parsed != null || dbConnection.readonly());
             // 筛选值一律绑定，绝不拼进 SQL：列名是标识符可以引用转义，值来自输入框，拼进去就是注入点。
             Statement statement = parsed != null || shaped.hasParameters()
                     ? connection.prepareStatement(pageSql) : connection.createStatement()) {
            dialect.configureReadStatement(connection, statement, Math.min(pageSize + 1, 500), properties.getSql().getTimeoutSeconds());
            statement.setMaxRows(pageSize + 1);
            if (parsed != null) SqlParameters.bind((java.sql.PreparedStatement) statement, parsed, parameters);
            int parameterOffset = parsed == null ? 0 : parsed.occurrences().size();
            for (int index = 0; index < shaped.parameters().size(); index++) {
                ((java.sql.PreparedStatement) statement).setObject(parameterOffset + index + 1, shaped.parameters().get(index));
            }
            String registeredId = executions.register(executionId, connectionId, statement);
            try {
                try (ResultSet rs = parsed != null || shaped.hasParameters()
                        ? ((java.sql.PreparedStatement) statement).executeQuery()
                        : statement.executeQuery(pageSql)) {
                    SqlResult result = readPageResult(
                            rs, started, connectionId, offset, rawPageSize, pageSize,
                            dialect.paginationHelperColumn(), schemaName, dialect, connection, dbConnection,
                            // 就地编辑的判定要看用户写的那条 SQL，不是排序包装之后的。
                            executionSql, normalizedSortColumn, normalizedSortDirection, normalizedFilters, parsed != null
                    );
                    audit.onConnection(actor, "SQL_QUERY_PAGE", connectionId, "offset=" + offset + "; " + abbreviate(sql));
                    metrics.success(SqlExecutionMetrics.KIND_PAGE, started);
                    return result;
                }
            } catch (Exception error) {
                throw executions.translateCancel(registeredId, statement, error);
            } finally {
                executions.unregister(registeredId, statement);
            }
        } catch (Exception e) {
            metrics.failure(SqlExecutionMetrics.KIND_PAGE, started, e);
            throw e;
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
        String executionSql = singleStatement(connectionId, sql, "执行计划");
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
            metrics.success(SqlExecutionMetrics.KIND_EXPLAIN, started);
            return result;
        } catch (Exception e) {
            history.insert(connectionId, sql, "EXPLAIN", "FAILED", elapsed(started), error(e), actor);
            metrics.failure(SqlExecutionMetrics.KIND_EXPLAIN, started, e);
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
        String executionSql = singleStatement(connectionId, sql, "MCP 执行计划");
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

    /** 执行统计：同一批历史数据的另一个视角，取数与范围判定都交给仓储层。 */
    public com.example.dbadmin.dto.ApiDtos.SqlHistoryStats historyStats(long connectionId, int days, Long actorUserId) {
        return history.stats(connectionId, days, actorUserId, 10);
    }

    public List<SqlHistoryResponse> history(long connectionId, Integer limit) {
        return history(connectionId, null, limit);
    }

    public List<SqlHistoryResponse> history(long connectionId, String keyword, Integer limit) {
        return history(connectionId, keyword, limit, null);
    }

    public List<SqlHistoryResponse> history(long connectionId, String keyword, Integer limit, Long actorUserId) {
        return history.findRecent(connectionId, keyword, limit == null ? 50 : limit, actorUserId);
    }

    private static final SqlFormatter FORMATTER = new SqlFormatter();

    public String format(String sql) {
        return FORMATTER.format(sql);
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
            String executionSql,
            String sortColumn,
            String sortDirection,
            List<SqlResultFilter> filters,
            boolean parameterized
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
        int effectivePageSize = Math.min(pageSize, SqlResultReader.MAX_RESULT_CELLS / Math.max(columnCount, 1));
        ResultSourceTable sourceTable = ResultSetSourceResolver.resolve(metadata, dialect);
        ResultEditability.EditableResult editable = parameterized ? null : editability.probe(connection, dbConnection, sourceTable, metadata, columnCount, executionSql, schemaName);
        List<List<Object>> rows = new ArrayList<>();
        List<String> rowKeyTokens = new ArrayList<>();
        long textChars = 0;
        boolean payloadLimitReached = false;
        while (rows.size() < effectivePageSize && rs.next()) {
            List<Object> row = new ArrayList<>(columnCount);
            for (int index = 1; index <= columnCount; index++) {
                int remainingText = (int) Math.min(SqlResultReader.MAX_CELL_TEXT_CHARS, Math.max(0, SqlResultReader.MAX_RESULT_TEXT_CHARS - textChars));
                Object value = SqlResultReader.serializableValue(rs, metadata, index, remainingText);
                row.add(value);
                if (value instanceof CharSequence text) textChars += text.length();
            }
            rows.add(row);
            // editable 非空但 locator 为空表示「不可编辑且有原因」，这时不发令牌。
            if (editable != null && editable.locator() != null) rowKeyTokens.add(editable.locator().encode(rs));
            if (textChars >= SqlResultReader.MAX_RESULT_TEXT_CHARS) {
                payloadLimitReached = true;
                break;
            }
        }
        boolean hasMore = payloadLimitReached || rs.next();
        SqlPageInfo page = new SqlPageInfo(connectionId, offset, requestedPageSize, effectivePageSize, hasMore,
                schemaName, sortColumn, sortDirection, filters);
        return SqlResultReader.withColumnSources(new SqlResult(
                columns,
                rows,
                -1,
                elapsed(startedNanos),
                true,
                effectivePageSize,
                payloadLimitReached,
                page,
                sourceTable,
                parameterized ? null : editability.editInfo(editable, rowKeyTokens)
        ), rs, connection, dbConnection, connectionId, schemaName, executionSql, dialect);
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

    private String singleStatement(long connectionId, String sql, String action) {
        String dbType = connections.require(connectionId).dbType();
        List<StatementSegment> statements = scriptSplitter.split(sql, dbType);
        // A GO execution unit is a batch, not permission to send several writes through a single-statement API.
        if (java.util.Set.of("sqlserver", "sql-server", "mssql").contains(dbType.toLowerCase(java.util.Locale.ROOT))
                && classifier.classify(sql) != SqlStatementClassifier.Kind.DDL && classifier.classify(sql) != SqlStatementClassifier.Kind.UNKNOWN
                && scriptSplitter.split(sql).size() != 1) {
            throw new IllegalArgumentException(action + "仅支持一条 SQL；多条语句请使用脚本执行接口。");
        }
        if (statements.size() != 1) {
            throw new IllegalArgumentException(action + "仅支持一条 SQL；多条语句请使用脚本执行接口。");
        }
        return statements.get(0).sql();
    }

    boolean changesMetadata(String sql) {
        return classifier.classify(sql) == SqlStatementClassifier.Kind.DDL || SqlScriptSyntax.opaqueBlock(sql);
    }

    private long elapsed(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    private String error(Exception e) {
        return abbreviate(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
    }

    static String abbreviate(String value) {
        if (value == null) return "";
        return value.length() <= 2_000 ? value : value.substring(0, 2_000);
    }
}
