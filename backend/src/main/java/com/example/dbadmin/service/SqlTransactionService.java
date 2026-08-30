package com.example.dbadmin.service;

import com.example.dbadmin.api.ApiProblemException;
import com.example.dbadmin.auth.WebIdentity;
import com.example.dbadmin.auth.WebIdentityContext;
import com.example.dbadmin.core.DatabaseDialect;
import com.example.dbadmin.core.DialectRegistry;
import com.example.dbadmin.dto.ApiDtos.SqlResult;
import com.example.dbadmin.dto.ApiDtos.SqlStatementResult;
import com.example.dbadmin.dto.ApiDtos.SqlTransactionResponse;
import com.example.dbadmin.dto.ApiDtos.SqlTransactionScriptResponse;
import com.example.dbadmin.model.DbConnection;
import com.example.dbadmin.repo.AuditRepository;
import com.example.dbadmin.repo.SqlHistoryRepository;
import com.example.dbadmin.service.SqlScriptSplitter.StatementSegment;
import com.example.dbadmin.service.SqlTransactionRegistry.OpenTransaction;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * 手动事务：把一个标签页里的多次执行绑到同一条连接、同一个事务上，由用户决定提交还是回滚。
 *
 * <p>与自动提交的脚本执行相比，代价是一条被独占的池化连接（见 {@link SqlTransactionRegistry}），
 * 收益是「先看效果再决定」—— 尤其是生产上的 UPDATE/DELETE。</p>
 */
@Service
public class SqlTransactionService {
    private static final int MAX_STATEMENTS_PER_CALL = 200;

    private final ConnectionService connections;
    private final DialectRegistry dialectRegistry;
    private final SqlScriptSplitter splitter;
    private final SqlStatementClassifier classifier;
    private final ExecutionGuard executionGuard;
    private final SqlTransactionRegistry registry;
    private final SqlService sqlService;
    private final AuditRepository audit;
    private final SqlHistoryRepository history;
    private final MetadataService metadata;

    public SqlTransactionService(
            ConnectionService connections,
            DialectRegistry dialectRegistry,
            SqlScriptSplitter splitter,
            SqlStatementClassifier classifier,
            ExecutionGuard executionGuard,
            SqlTransactionRegistry registry,
            SqlService sqlService,
            AuditRepository audit,
            SqlHistoryRepository history,
            MetadataService metadata
    ) {
        this.connections = connections;
        this.dialectRegistry = dialectRegistry;
        this.splitter = splitter;
        this.classifier = classifier;
        this.executionGuard = executionGuard;
        this.registry = registry;
        this.sqlService = sqlService;
        this.audit = audit;
        this.history = history;
        this.metadata = metadata;
    }

    public SqlTransactionResponse begin(long connectionId, String schemaName, String actor, String productionConfirmation) throws Exception {
        DbConnection dbConnection = connections.require(connectionId);
        // 事务里几乎必然要写；这里就按写操作要求确认，而不是等到第一条 UPDATE 才拦。
        executionGuard.requireMutationAllowed(dbConnection, productionConfirmation);
        Connection connection = schemaName == null || schemaName.isBlank()
                ? connections.open(connectionId)
                : connections.open(connectionId, schemaName);
        try {
            connection.setAutoCommit(false);
        } catch (Exception error) {
            connection.close();
            throw error;
        }
        OpenTransaction transaction;
        try {
            // 归属取自服务端会话；actor 只是审计标签，客户端可以随便填。
            transaction = registry.open(connectionId, connection, schemaName, actor, currentUserId());
        } catch (RuntimeException error) {
            connection.close();
            throw error;
        }
        audit.onConnection(actor, "SQL_TRANSACTION_BEGIN", connectionId, "transaction:" + transaction.id());
        return describe(transaction);
    }

    /**
     * 该连接上属于当前用户的事务。别人的事务一律当作不存在 —— 这个响应里带着事务 id，
     * 泄露出去就等于把提交/回滚的能力交出去了。
     */
    public SqlTransactionResponse active(long connectionId) {
        OpenTransaction transaction = registry.activeFor(connectionId);
        if (transaction == null || !transaction.ownedBy(currentUserId())) return null;
        return describe(transaction);
    }

    private Long currentUserId() {
        return WebIdentityContext.current().map(WebIdentity::userId).orElse(null);
    }

    public SqlTransactionScriptResponse execute(
            String transactionId,
            String sql,
            Integer requestedMaxRows,
            String actor,
            boolean unscopedMutationConfirmed
    ) throws Exception {
        OpenTransaction transaction = registry.require(transactionId);
        DbConnection dbConnection = connections.require(transaction.connectionId());
        List<StatementSegment> statements = splitter.split(sql);
        if (statements.isEmpty()) throw new IllegalArgumentException("请输入要执行的 SQL");
        if (statements.size() > MAX_STATEMENTS_PER_CALL) {
            throw new IllegalArgumentException("手动事务里一次最多执行 " + MAX_STATEMENTS_PER_CALL + " 条 SQL。");
        }
        for (StatementSegment statement : statements) {
            if (classifier.changesSession(statement.sql())) {
                throw new IllegalArgumentException("手动事务里不允许执行会改变会话状态的语句（USE/SET 等）。");
            }
        }
        requireUnscopedConfirmation(statements, unscopedMutationConfirmed, transaction, actor);

        // 一条 JDBC 连接不是线程安全的，而前端完全可能在上一次还没返回时又点一次执行。
        if (!transaction.lock().tryLock()) {
            throw new ApiProblemException(
                    HttpStatus.CONFLICT, "TRANSACTION_BUSY", "该事务上已有语句正在执行，请等待完成。"
            );
        }
        try {
            // 上面 require 之后、这里拿到锁之前，事务可能已经被并发的提交/回滚结束掉了。
            registry.requireOpen(transaction);
            DatabaseDialect dialect = dialectRegistry.dialectFor(dbConnection);
            long started = System.nanoTime();
            List<SqlStatementResult> results = new ArrayList<>();
            String status = "SUCCESS";
            String errorMessage = null;
            boolean metadataChanged = false;
            int maxRows = sqlService.normalizeMaxRows(requestedMaxRows);

            try (Statement jdbc = transaction.connection().createStatement()) {
                dialect.configureReadStatement(transaction.connection(), jdbc, Math.min(maxRows + 1, 500), sqlService.sqlTimeoutSeconds());
                for (int index = 0; index < statements.size(); index++) {
                    StatementSegment statement = statements.get(index);
                    long statementStarted = System.nanoTime();
                    jdbc.setMaxRows(maxRows + 1);
                    metadataChanged = metadataChanged || sqlService.changesMetadata(statement.sql());
                    try {
                        boolean hasResult = jdbc.execute(statement.sql());
                        SqlResult result = hasResult
                                ? readOne(jdbc, statementStarted, maxRows, dialect)
                                : sqlService.emptyResult(jdbc.getUpdateCount(), elapsedMs(statementStarted), maxRows);
                        results.add(new SqlStatementResult(
                                index + 1, statement.sql(), statement.startOffset(), statement.endOffset(), "SUCCESS", null, result
                        ));
                    } catch (Exception error) {
                        errorMessage = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
                        results.add(new SqlStatementResult(
                                index + 1, statement.sql(), statement.startOffset(), statement.endOffset(),
                                "FAILED", errorMessage, sqlService.emptyResult(-1, elapsedMs(statementStarted), maxRows)
                        ));
                        status = "FAILED";
                        break;
                    }
                }
            }
            transaction.recordUse(results.size());
            // 事务里 DDL 往往会隐式提交，缓存无论成败都不能再信。
            if (metadataChanged) metadata.invalidateConnection(transaction.connectionId());
            history.insert(transaction.connectionId(), sql, "TRANSACTION_EXECUTE", status, elapsedMs(started), errorMessage, actor);
            return new SqlTransactionScriptResponse(
                    describe(transaction), status, elapsedMs(started), results.size(), results, metadataChanged
            );
        } finally {
            transaction.lock().unlock();
        }
    }

    public SqlTransactionResponse finish(String transactionId, boolean commit, String actor) throws Exception {
        OpenTransaction transaction = registry.require(transactionId);
        if (!transaction.lock().tryLock()) {
            throw new ApiProblemException(
                    HttpStatus.CONFLICT, "TRANSACTION_BUSY", "该事务上还有语句正在执行，请等待完成。"
            );
        }
        SqlTransactionResponse snapshot;
        try {
            registry.requireOpen(transaction);
            snapshot = describe(transaction);
            if (commit) transaction.connection().commit();
            else transaction.connection().rollback();
        } finally {
            // 摘除必须在放锁之前完成：反过来的话，从放锁到摘除之间另一个请求能取到这个事务、
            // 拿到锁并开始执行，而连接随即被这里关掉 —— 连接是 autoCommit=false，close 会隐式
            // 回滚，那批语句会静默丢失而不是报错。
            registry.close(transactionId);
            transaction.lock().unlock();
        }
        audit.onConnection(
                actor,
                commit ? "SQL_TRANSACTION_COMMIT" : "SQL_TRANSACTION_ROLLBACK",
                transaction.connectionId(),
                "transaction:" + transactionId + " statements=" + transaction.statementCount()
        );
        // 事务结束后缓存里的表结构/行数可能已经变了。
        metadata.invalidateConnection(transaction.connectionId());
        return snapshot;
    }

    /** 空闲太久的事务会占死连接池，定期回收。 */
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void reclaimIdleTransactions() {
        for (SqlTransactionRegistry.ReclaimedTransaction reclaimed : registry.sweepIdle()) {
            audit.onConnection("system", "SQL_TRANSACTION_TIMEOUT", reclaimed.connectionId(),
                    "transaction:" + reclaimed.id(), "空闲超时自动回滚");
        }
    }

    private void requireUnscopedConfirmation(
            List<StatementSegment> statements, boolean confirmed, OpenTransaction transaction, String actor
    ) {
        List<java.util.Map<String, Object>> unsafe = new ArrayList<>();
        for (int index = 0; index < statements.size(); index++) {
            if (!classifier.requiresUnscopedMutationConfirmation(statements.get(index).sql())) continue;
            unsafe.add(java.util.Map.of("index", index + 1, "sql", statements.get(index).sql()));
        }
        if (unsafe.isEmpty()) return;
        if (!confirmed) {
            throw new ApiProblemException(
                    HttpStatus.CONFLICT,
                    "UNSCOPED_MUTATION_CONFIRMATION_REQUIRED",
                    "检测到未包含顶层 WHERE 条件的 UPDATE/DELETE，可能影响整张表。",
                    java.util.Map.of("statements", unsafe)
            );
        }
        audit.onConnection(actor, "SQL_UNSCOPED_MUTATION_CONFIRMED", transaction.connectionId(),
                "transaction:" + transaction.id() + " statements=" + unsafe.size());
    }

    private SqlResult readOne(Statement jdbc, long startedNanos, int maxRows, DatabaseDialect dialect) throws Exception {
        try (ResultSet rs = jdbc.getResultSet()) {
            return sqlService.readResult(rs, startedNanos, maxRows, dialect);
        }
    }

    private SqlTransactionResponse describe(OpenTransaction transaction) {
        return new SqlTransactionResponse(
                transaction.id(),
                transaction.connectionId(),
                transaction.schemaName(),
                transaction.startedAt().toString(),
                transaction.lastUsedAt().toString(),
                transaction.statementCount(),
                (int) registry.idleTimeout().toSeconds()
        );
    }

    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }
}
