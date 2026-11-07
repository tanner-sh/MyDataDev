package com.example.dbadmin.service;

import com.example.dbadmin.service.SqlFileExecutionService.CancelledException;
import com.example.dbadmin.service.SqlFileExecutionService.CommandUnitContext;
import com.example.dbadmin.service.SqlFileExecutionService.CommandUnitResult;
import com.example.dbadmin.service.SqlFileExecutionService.CommandUnitRunner;

import com.example.dbadmin.config.AppProperties;
import com.example.dbadmin.core.DatabaseDialect;
import com.example.dbadmin.core.DialectRegistry;
import com.example.dbadmin.model.DbConnection;
import com.example.dbadmin.model.SqlFileExecution;
import com.example.dbadmin.repo.AuditRepository;
import com.example.dbadmin.repo.SqlFileExecutionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.Charset;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * SQL 文件任务真正执行的那一半：文件的分批提交（{@link #execute}）、Oracle 系脚本控制事务、
 * 命令窗口的逐条执行与滚动日志，以及执行中编译错误（对象 INVALID）的收集。
 *
 * <p>从 {@link SqlFileExecutionService} 拆出来。上传、登记、分析、启动、取消与清理留在那边；
 * 这里只在协调器的工作线程上被调用，任务状态、运行中的语句（取消要用）与文件校验仍归服务管，
 * 经 {@code files} 取用，两边看到的是同一份。</p>
 */
final class SqlFileJobExecutor {
    /** 沿用服务的日志名：现有的日志配置与排障习惯都按它来。 */
    private static final Logger log = LoggerFactory.getLogger(SqlFileExecutionService.class);

    private final SqlFileExecutionService files;
    private final SqlFileExecutionRepository jobs;
    private final ConnectionService connections;
    private final DialectRegistry dialects;
    private final MetadataService metadata;
    private final AuditRepository audit;
    private final AppProperties properties;
    private final SqlStatementClassifier classifier;

    SqlFileJobExecutor(SqlFileExecutionService files, SqlFileExecutionRepository jobs, ConnectionService connections,
                       DialectRegistry dialects, MetadataService metadata, AuditRepository audit, AppProperties properties,
                       SqlStatementClassifier classifier) {
        this.files = files;
        this.jobs = jobs;
        this.connections = connections;
        this.dialects = dialects;
        this.metadata = metadata;
        this.audit = audit;
        this.properties = properties;
        this.classifier = classifier;
    }

    void execute(long id) {
        if (files.isCommand(id)) { executeCommandJob(id); return; }
        if ("SCRIPT".equals(files.require(id).transaction().mode())) { executeScriptJob(id); return; }
        SqlFileExecution job = files.require(id);
        long[] current = {0};
        long[] success = {0};
        long[] queryRows = {0};
        CompilationCollector[] compiled = {null};
        try {
            jobs.markRunning(id);
            files.ensureNotCancelled(id);
            files.verifyUnchanged(job);
            DbConnection target = connections.require(job.connectionId());
            DatabaseDialect dialect = dialects.dialectFor(target);
            CompilationCollector compilation = new CompilationCollector(id, dialect);
            compiled[0] = compilation;
            boolean executionCompleted = false;
            String cleanupWarning = "";
            try (Connection connection = connections.open(job.connectionId())) {
                if (job.sessionChanged()) connections.discardOnClose(connection);
                connection.setAutoCommit(false);
                long[] pending = {0};
                long[] lastProgressNanos = {0};
                int commitBatchSize = Math.max(1, properties.getSqlFile().getCommitBatchSize());
                try (Statement statement = connection.createStatement()) {
                    files.runningStatements.put(id, statement);
                    dialect.configureStreamingStatement(connection, statement, 500, properties.getSqlFile().getStatementTimeoutSeconds());
                    int[] batched = {0};
                    SqlFileStatementReader.read(files.checkedPath(job), Charset.forName(job.detectedCharset()), job.targetDbType(), SqlFileExecutionService.delimiterOf(job),
                            properties.getSqlFile().getMaxStatementChars(), (index, sql) -> {
                        files.ensureNotCancelled(id);
                        current[0] = index;
                        try {
                            SqlStatementClassifier.Kind kind = classifier.classify(sql);
                            if (kind == SqlStatementClassifier.Kind.MUTATION && batched[0] < commitBatchSize) {
                                statement.addBatch(sql);
                                batched[0]++;
                                pending[0]++;
                                if (batched[0] >= commitBatchSize) {
                                    statement.executeBatch();
                                    batched[0] = 0;
                                    connection.commit();
                                    success[0] += pending[0];
                                    pending[0] = 0;
                                }
                            } else {
                                if (batched[0] > 0) {
                                    statement.executeBatch();
                                    batched[0] = 0;
                                    connection.commit();
                                    success[0] += pending[0];
                                    pending[0] = 0;
                                }
                                boolean result = statement.execute(sql);
                                while (true) {
                                    if (result) {
                                        try (ResultSet rows = statement.getResultSet()) {
                                            while (rows.next()) {
                                                queryRows[0]++;
                                                if ((queryRows[0] & 4095) == 0) files.ensureNotCancelled(id);
                                            }
                                        }
                                    } else if (statement.getUpdateCount() == -1) {
                                        break;
                                    }
                                    result = statement.getMoreResults();
                                }
                                compilation.collect(connection, sql);
                                pending[0]++;
                                if (pending[0] >= commitBatchSize) {
                                    connection.commit();
                                    success[0] += pending[0];
                                    pending[0] = 0;
                                }
                            }
                            if (files.progressDue(lastProgressNanos)) {
                                long executed = success[0] + pending[0];
                                jobs.updateExecutionProgress(id, index, executed, queryRows[0], "已执行第 " + index + " / " + job.statementTotal() + " 条语句。");
                            }
                        } catch (CancelledException error) {
                            try { connection.rollback(); } catch (Exception ignored) { }
                            throw error;
                        } catch (Exception error) {
                            try { connection.rollback(); } catch (Exception ignored) { }
                            throw new StatementFailure(index, sql, error);
                        }
                    }, ignored -> files.ensureNotCancelled(id));
                    if (batched[0] > 0) {
                        statement.executeBatch();
                        batched[0] = 0;
                    }
                    connection.commit();
                    success[0] += pending[0];
                    pending[0] = 0;
                    jobs.updateExecutionProgress(id, current[0], success[0], queryRows[0],
                            "已执行全部 " + current[0] + " 条语句，正在收尾。");
                    executionCompleted = true;
                } catch (Exception error) {
                    // Includes file reads, cancellation between units, final batch flush and final commit.
                    if (!executionCompleted) {
                        try { connection.rollback(); }
                        catch (Exception rollbackError) {
                            error.addSuppressed(rollbackError);
                            try { connection.abort(Runnable::run); } catch (Exception abortError) { error.addSuppressed(abortError); }
                        }
                    }
                    throw error;
                } finally {
                    files.runningStatements.remove(id);
                }
            } catch (Exception error) {
                if (!executionCompleted) throw error;
                cleanupWarning = cleanupWarning(job, error);
            }
            jobs.markTerminal(id, "SUCCESS", "COMPLETED", current[0], success[0], queryRows[0], null, null,
                    "SQL 文件执行完成，共成功 " + success[0] + " 条语句。" + compiled[0].summary() + cleanupWarning);
            audit.onConnection(job.actor(), "SQL_FILE_SUCCESS", job.connectionId(), "job=" + id + "; statements=" + success[0]);
        } catch (StatementFailure failure) {
            if (files.cancellationRequested(id)) {
                jobs.markTerminal(id, "CANCELLED", "CANCELLED", failure.index, success[0], queryRows[0], null, null, "SQL 文件执行已取消。");
            } else {
                jobs.markTerminal(id, "FAILED", "EXECUTION_FAILED", failure.index, success[0], queryRows[0], failure.index,
                        files.preview(failure.sql), files.safeMessage(failure.getCause()));
                audit.onConnection(job.actor(), "SQL_FILE_FAILED", job.connectionId(), "job=" + id + "; statement=" + failure.index + "; " + files.safeMessage(failure.getCause()));
            }
        } catch (CancelledException ignored) {
            jobs.markTerminal(id, "CANCELLED", "CANCELLED", current[0], success[0], queryRows[0], null, null, "SQL 文件执行已取消。");
        } catch (Exception error) {
            if (files.cancellationRequested(id)) {
                jobs.markTerminal(id, "CANCELLED", "CANCELLED", current[0], success[0], queryRows[0], null, null, "SQL 文件执行已取消。");
            } else {
                jobs.markTerminal(id, "FAILED", "EXECUTION_FAILED", current[0], success[0], queryRows[0], null, null, files.safeMessage(error));
                audit.onConnection(job.actor(), "SQL_FILE_FAILED", job.connectionId(), "job=" + id + "; " + files.safeMessage(error));
            }
        } finally {
            files.runningStatements.remove(id);
            files.analyzedSources.remove(id);
            if (job.metadataChanged()) metadata.invalidateConnection(job.connectionId());

            files.deleteFileQuietly(job);
        }
    }

    private void executeScriptJob(long id) {
        SqlFileExecution job = files.require(id);
        long[] current = {0}, success = {0}, queryRows = {0}, lastProgress = {0};
        CompilationCollector[] compiled = {null};
        SqlScriptParser.Unit[] currentUnit = {null};
        SqlScriptTransaction[] scope = {null};
        Runnable persist = () -> {
            var tx = scope[0];
            if (tx != null) jobs.updateTransactionProgress(id, tx.commits, tx.rollbacks, tx.lastCommit, tx.outcome,
                    currentUnit[0] == null ? null : currentUnit[0].startLine(),
                    currentUnit[0] == null ? null : currentUnit[0].endLine());
        };
        try {
            jobs.markRunning(id);
            files.ensureNotCancelled(id);
            files.verifyUnchanged(job);
            DbConnection target = connections.require(job.connectionId());
            connections.requireNoManualTransaction(job.connectionId());
            boolean executionCompleted = false;
            String cleanupWarning = "";
            try (Connection connection = connections.open(job.connectionId());
                 SqlScriptTransaction tx = new SqlScriptTransaction(connection, persist)) {
                if (job.sessionChanged()) connections.discardOnClose(connection);
                scope[0] = tx;
                try (Statement statement = connection.createStatement()) {
                    files.runningStatements.put(id, statement);
                    dialects.dialectFor(target).configureStreamingStatement(connection, statement, 500, properties.getSqlFile().getStatementTimeoutSeconds());
                    compiled[0] = new CompilationCollector(id, dialects.dialectFor(target));
                    SqlFileStatementReader.readUnits(files.checkedPath(job), Charset.forName(job.detectedCharset()), job.targetDbType(), SqlFileExecutionService.delimiterOf(job),
                            properties.getSqlFile().getMaxStatementChars(), unit -> {
                        files.ensureNotCancelled(id);
                        currentUnit[0] = unit;
                        current[0] = unit.index();
                        boolean result = tx.execute(statement, unit.sql(), unit.index());
                        while (true) {
                            if (result) {
                                try (ResultSet rows = statement.getResultSet()) {
                                    while (rows.next()) {
                                        queryRows[0]++;
                                        if ((queryRows[0] & 4095) == 0) files.ensureNotCancelled(id);
                                    }
                                }
                            } else if (statement.getUpdateCount() == -1) break;
                            result = statement.getMoreResults();
                        }
                        compiled[0].collect(connection, unit.sql());
                        success[0]++;
                        if (files.progressDue(lastProgress)) jobs.updateExecutionProgress(id, current[0], success[0], queryRows[0],
                                "已执行第 " + current[0] + " 个单元（不代表已提交）。");
                    }, ignored -> files.ensureNotCancelled(id));
                    files.ensureNotCancelled(id);
                    currentUnit[0] = null;
                    tx.finish("COMMIT".equals(job.transaction().endOfFileAction()), current[0]);
                    // Only resource closing remains; its errors cannot undo this confirmed outcome.
                    executionCompleted = true;
                } catch (Exception error) {
                    tx.fail(error);
                    throw error;
                } finally { files.runningStatements.remove(id); }
            } catch (Exception error) {
                if (!executionCompleted) throw error;
                cleanupWarning = cleanupWarning(job, error);
            }
            jobs.markTerminal(id, "SUCCESS", "COMPLETED", current[0], success[0], queryRows[0], null, null,
                    "SQL 文件执行完成；" + ("COMMIT".equals(job.transaction().endOfFileAction()) ? "剩余事务已提交。" : "剩余未提交事务已回滚，仅保留此前已提交内容。")
                            + (compiled[0] == null ? "" : compiled[0].summary()) + cleanupWarning);
            audit.onConnection(job.actor(), "SQL_FILE_SUCCESS", job.connectionId(), "job=" + id + "; mode=SCRIPT");
        } catch (Exception error) {
            boolean cancelled = error instanceof CancelledException || jobs.isCancelRequested(id);
            var unit = currentUnit[0];
            String outcome = scope[0] == null ? "NOT_STARTED" : scope[0].outcome;
            log.warn("SQL script failed: taskId={}, connectionId={}, executedCount={}, transactionOutcome={}",
                    id, job.connectionId(), success[0], outcome, error);
            String detail = (unit == null ? "" : "第 " + unit.startLine() + "–" + unit.endLine() + " 行：") + files.safeMessage(error)
                    + ("UNKNOWN".equals(outcome) ? "；事务结果未知，请核对数据库，不会自动重跑。" : "；已尝试回滚当前事务，此前提交及数据库内部提交不会撤销。")
                    + suppressedErrors(error);
            jobs.markTerminal(id, cancelled ? "CANCELLED" : "FAILED", "UNKNOWN".equals(outcome) ? "TRANSACTION_UNKNOWN" : cancelled ? "CANCELLED" : "EXECUTION_FAILED",
                    current[0], success[0], queryRows[0], unit == null ? null : unit.index(), unit == null ? null : files.preview(unit.sql()), detail);
            audit.onConnection(job.actor(), cancelled ? "SQL_FILE_CANCELLED" : "SQL_FILE_FAILED", job.connectionId(), "job=" + id + "; " + detail);
        } finally {
            files.runningStatements.remove(id);
            if (job.metadataChanged()) metadata.invalidateConnection(job.connectionId());

            files.deleteFileQuietly(job);
        }
    }

    private static final int COMMAND_PREVIEW_ROWS = 20;

    private static final int COMMAND_MAX_COUNTED_ROWS = 100_000;

    /** 逐条自动提交且驱动不能流式读取时（PostgreSQL 的游标要求关掉 autocommit），结果集整批进内存，所以少数一些。 */
    private static final int COMMAND_MAX_COUNTED_ROWS_BUFFERED = 10_000;

    private static final int COMMAND_LOG_LIMIT = 20_000;

    private static final int COMMAND_ERROR_LIMIT = 5_000;

    private static final int COMMAND_OUTPUT_LINES = 500;

    /**
     * 命令窗口的执行：逐条执行、逐条记日志，遇错按策略停或继续。
     *
     * <p>与 {@link #execute} 的分批提交不同，这里不用 JDBC batch —— 批里哪一条失败、每条影响几行，
     * 驱动给得并不可靠，而这两件事正是命令窗口要显示的。事务两种：逐条提交（autocommit，每条成功即
     * 生效，失败的那条不生效，遇错继续时语义最清楚），或 Oracle 系的脚本控制事务（沿用
     * {@link SqlScriptTransaction}，与 SQL*Plus 一致：按脚本里的 COMMIT/ROLLBACK 走，失败的那条由数据库
     * 做语句级回滚，事务本身还在，所以遇错继续也成立）。连接断了不管什么策略都停：后面每条都会失败。</p>
     */
    private void executeCommandJob(long id) {
        SqlFileExecution job = files.require(id);
        var settings = files.commands.find(id).orElseThrow();
        CommandUnitRunner runner = files.commandRunners.get(id);
        boolean[] continueOnError = {"CONTINUE".equals(settings.onError())};
        int[] maxCounted = {COMMAND_MAX_COUNTED_ROWS};
        boolean scriptTx = "SCRIPT".equals(job.transaction().mode());
        boolean commitAtEnd = "COMMIT".equals(job.transaction().endOfFileAction());
        CommandLog out = new CommandLog(id);
        long[] current = {0}, success = {0}, failed = {0}, queryRows = {0}, lastProgress = {0};
        Long[] firstFailed = {null};
        String[] firstFailedSql = {null};
        CompilationCollector[] compiled = {null};
        SqlScriptTransaction[] scope = {null};
        SqlScriptParser.Unit[] currentUnit = {null};
        Runnable persist = () -> {
            var tx = scope[0];
            if (tx != null) jobs.updateTransactionProgress(id, tx.commits, tx.rollbacks, tx.lastCommit, tx.outcome,
                    currentUnit[0] == null ? null : currentUnit[0].startLine(),
                    currentUnit[0] == null ? null : currentUnit[0].endLine());
        };
        try {
            jobs.markRunning(id);
            files.ensureNotCancelled(id);
            files.verifyUnchanged(job);
            DbConnection target = connections.require(job.connectionId());
            DatabaseDialect dialect = dialects.dialectFor(target);
            if (scriptTx) connections.requireNoManualTransaction(job.connectionId());
            out.info("开始执行：共 " + job.statementTotal() + " 条；"
                    + (scriptTx ? "脚本控制事务（结束时" + (commitAtEnd ? "提交" : "回滚") + "剩余事务）" : "逐条自动提交")
                    + "；遇错" + (continueOnError[0] ? "继续" : "停止") + "。");
            boolean executionCompleted = false;
            String cleanupWarning = "";
            try (Connection connection = connections.open(job.connectionId());
                 SqlScriptTransaction tx = scriptTx ? new SqlScriptTransaction(connection, persist) : null) {
                if (job.sessionChanged()) connections.discardOnClose(connection);
                if (!scriptTx) connection.setAutoCommit(true);
                scope[0] = tx;
                try (Statement statement = connection.createStatement()) {
                    files.runningStatements.put(id, statement);
                    dialect.configureStreamingStatement(connection, statement, 500, properties.getSqlFile().getStatementTimeoutSeconds());
                    if (!scriptTx && !connection.getAutoCommit()) {
                        // 方言为了流式读取关掉了 autocommit（PostgreSQL）。「逐条自动提交」必须是真的 autocommit：
                        // 否则每条写入都悬在一个从不提交的事务里，连接归还连接池时被整批回滚，日志却报「成功」；
                        // 出错之后的每一条也都会报 current transaction is aborted，「遇错继续」形同虚设。
                        // 也不能改成逐条手动提交 —— VACUUM、CREATE INDEX CONCURRENTLY 只能在事务块之外执行。
                        // 代价是结果集不再流式读取，用 maxRows 给它封顶。
                        connection.setAutoCommit(true);
                        statement.setMaxRows(COMMAND_MAX_COUNTED_ROWS_BUFFERED + 1);
                        maxCounted[0] = COMMAND_MAX_COUNTED_ROWS_BUFFERED;
                    }
                    compiled[0] = new CompilationCollector(id, dialect);
                    RoutineOutput.enable(connection, dialect);
                    SqlFileStatementReader.readUnits(files.checkedPath(job), Charset.forName(job.detectedCharset()), job.targetDbType(), SqlFileExecutionService.delimiterOf(job),
                            properties.getSqlFile().getMaxStatementChars(), unit -> {
                        files.ensureNotCancelled(id);
                        currentUnit[0] = unit;
                        current[0] = unit.index();
                        out.add("STATEMENT", unit, null, SqlCommandOutput.summary(unit.sql()));
                        long started = System.nanoTime();
                        try {
                            statement.clearWarnings();
                            try { connection.clearWarnings(); } catch (Exception ignored) { }
                            List<String> outcome = new ArrayList<>();
                            List<String> printed;
                            if (runner != null) {
                                if (unit.index() != 1) throw new IllegalStateException("后台调用只能有一条语句。");
                                CommandUnitResult produced;
                                try {
                                    produced = runner.run(connection, new CommandUnitContext() {
                                        @Override public void register(Statement running) { files.runningStatements.put(id, running); }
                                        @Override public int timeoutSeconds() { return properties.getSqlFile().getStatementTimeoutSeconds(); }
                                        @Override public boolean cancelRequested() { return files.cancellationRequested(id); }
                                    });
                                } finally {
                                    files.runningStatements.put(id, statement);
                                }
                                queryRows[0] += produced.queryRows();
                                outcome.add(produced.text());
                                printed = produced.output();
                            } else {
                                boolean result = tx != null ? tx.execute(statement, unit.sql(), unit.index()) : statement.execute(unit.sql());
                                while (true) {
                                    if (result) {
                                        try (ResultSet rows = statement.getResultSet()) {
                                            var rendered = SqlCommandOutput.render(rows, COMMAND_PREVIEW_ROWS, maxCounted[0], () -> files.ensureNotCancelled(id));
                                            queryRows[0] += rendered.rows();
                                            outcome.add(rendered.text());
                                        }
                                    } else {
                                        int count = statement.getUpdateCount();
                                        if (count == -1) break;
                                        outcome.add(SqlCommandOutput.updateText(unit.sql(), count));
                                    }
                                    result = statement.getMoreResults();
                                }
                                printed = RoutineOutput.read(connection, dialect, statement, COMMAND_OUTPUT_LINES);
                            }
                            out.add("RESULT", unit, elapsedMillis(started), outcome.isEmpty() ? "执行成功。" : String.join("\n", outcome));
                            for (String line : printed) out.add("OUTPUT", unit, null, line);
                            List<String> compileErrors = compiled[0].collectNew(connection, unit.sql());
                            if (!compileErrors.isEmpty()) out.add("COMPILE", unit, null, "对象已创建但编译失败：\n" + String.join("\n", compileErrors));
                            success[0]++;
                        } catch (CancelledException cancelled) {
                            throw cancelled;
                        } catch (Exception error) {
                            failed[0]++;
                            if (firstFailed[0] == null) { firstFailed[0] = unit.index(); firstFailedSql[0] = unit.sql(); }
                            try { files.commands.recordFailure(id, unit.index()); } catch (Exception ignored) { }
                            // 出错之前 PUT_LINE 的内容往往正是定位问题的线索，先打出来。
                            for (String line : RoutineOutput.read(connection, dialect, statement, COMMAND_OUTPUT_LINES)) out.add("OUTPUT", unit, null, line);
                            out.add("ERROR", unit, elapsedMillis(started), files.safeMessage(error));
                            if (!continueOnError[0] || connectionLost(error)) throw new StatementFailure(unit.index(), unit.sql(), error);
                        }
                        if (files.progressDue(lastProgress)) {
                            out.flush();
                            jobs.updateExecutionProgress(id, current[0], success[0], queryRows[0], "已执行第 " + current[0] + " / " + job.statementTotal() + " 条"
                                    + (failed[0] > 0 ? "，失败 " + failed[0] + " 条" : "") + "。");
                        }
                    }, ignored -> files.ensureNotCancelled(id), directive -> {
                        switch (directive.kind()) {
                            case PROMPT -> out.add("PROMPT", directive.line(), directive.text());
                            case NOTICE -> out.add("INFO", directive.line(), directive.text());
                            case ON_ERROR_CONTINUE -> {
                                continueOnError[0] = true;
                                out.add("INFO", directive.line(), directive.text() + "：之后遇错继续。");
                            }
                            case ON_ERROR_EXIT -> {
                                continueOnError[0] = false;
                                out.add("INFO", directive.line(), directive.text() + "：之后遇错停止。");
                            }
                        }
                    });
                    files.ensureNotCancelled(id);
                    currentUnit[0] = null;
                    if (tx != null) tx.finish(commitAtEnd, current[0]);
                    executionCompleted = true;
                } catch (Exception error) {
                    if (tx != null) tx.fail(error);
                    throw error;
                } finally {
                    files.runningStatements.remove(id);
                }
            } catch (Exception error) {
                if (!executionCompleted) throw error;
                cleanupWarning = cleanupWarning(job, error);
            }
            String summary = "执行完成：成功 " + success[0] + " 条" + (failed[0] > 0 ? "，失败 " + failed[0] + " 条" : "") + "。"
                    + (scriptTx && scope[0] != null ? scope[0].summary() : "")
                    + (compiled[0] == null ? "" : compiled[0].summary()) + cleanupWarning;
            out.info(summary);
            out.flush();
            jobs.markTerminal(id, "SUCCESS", failed[0] > 0 ? "COMPLETED_WITH_ERRORS" : "COMPLETED", current[0], success[0], queryRows[0],
                    firstFailed[0], firstFailedSql[0] == null ? null : files.preview(firstFailedSql[0]), summary);
            audit.onConnection(job.actor(), "SQL_FILE_SUCCESS", job.connectionId(), "job=" + id + "; mode=COMMAND; statements=" + success[0] + "; failed=" + failed[0]);
        } catch (Exception error) {
            boolean cancelled = error instanceof CancelledException || files.cancellationRequested(id);
            var unit = currentUnit[0];
            String transaction = scriptTx && scope[0] != null ? "；" + scope[0].summary() : "";
            String detail = cancelled ? "已取消：成功 " + success[0] + " 条" + (failed[0] > 0 ? "，失败 " + failed[0] + " 条" : "") + "。" + transaction
                    : (error instanceof StatementFailure failure
                        ? "已停止：第 " + failure.index + " 条失败" + (unit == null ? "" : "（第 " + unit.startLine() + "–" + unit.endLine() + " 行）")
                            + "，此前成功 " + success[0] + " 条。" + transaction
                        : "执行中断：" + files.safeMessage(error) + transaction);
            out.info(detail);
            out.flush();
            Throwable cause = error instanceof StatementFailure failure ? failure.getCause() : error;
            jobs.markTerminal(id, cancelled ? "CANCELLED" : "FAILED", cancelled ? "CANCELLED" : "EXECUTION_FAILED", current[0], success[0], queryRows[0],
                    unit == null ? firstFailed[0] : Long.valueOf(unit.index()), unit == null ? null : files.preview(unit.sql()),
                    cancelled ? detail : detail + " " + files.safeMessage(cause));
            audit.onConnection(job.actor(), cancelled ? "SQL_FILE_CANCELLED" : "SQL_FILE_FAILED", job.connectionId(), "job=" + id + "; mode=COMMAND; " + detail);
        } finally {
            out.flush();
            files.runningStatements.remove(id);
            files.commandRunners.remove(id);
            files.analyzedSources.remove(id);
            if (job.metadataChanged()) metadata.invalidateConnection(job.connectionId());
            files.deleteFileQuietly(job);
        }
    }

    private static long elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    private static boolean connectionLost(Throwable error) {
        return error instanceof java.sql.SQLRecoverableException || error instanceof java.sql.SQLNonTransientConnectionException
                || (error instanceof java.sql.SQLException sqlError && sqlError.getSQLState() != null && sqlError.getSQLState().startsWith("08"));
    }

    /**
     * 命令窗口日志的缓冲写入：攒一批或隔 300ms 落一次库，不在每条语句后各写一次。
     *
     * <p>日志有上限：超过 {@link #COMMAND_LOG_LIMIT} 行后只再记错误（最多 {@link #COMMAND_ERROR_LIMIT} 条），
     * 并在截断处写一行说明。几万条 INSERT 的脚本，成功的那几万行谁也不会去看，失败的那几条一条都不能丢。
     * 写日志本身失败不影响执行：日志是旁路信息。</p>
     */
    private final class CommandLog {
        private final long id;
        private final List<com.example.dbadmin.repo.SqlCommandRepository.LogEntry> buffer = new ArrayList<>();
        private long seq;
        private long accepted;
        private int errorsAfterLimit;
        private boolean truncated;
        private boolean pendingTruncation;
        private long lastFlush = System.nanoTime();

        CommandLog(long id) {
            this.id = id;
        }

        void info(String text) {
            add("INFO", null, null, null, null, text);
        }

        void add(String kind, SqlScriptParser.Unit unit, Long elapsed, String text) {
            add(kind, unit.index(), unit.startLine(), unit.endLine(), elapsed, text);
        }

        void add(String kind, int line, String text) {
            add(kind, null, line, line, null, text);
        }

        void add(String kind, Long statementIndex, Integer startLine, Integer endLine, Long elapsed, String text) {
            boolean important = "ERROR".equals(kind) || "INFO".equals(kind) && statementIndex == null && startLine == null;
            if (accepted >= COMMAND_LOG_LIMIT) {
                if (!truncated) {
                    truncated = true;
                    pendingTruncation = true;
                    buffer.add(new com.example.dbadmin.repo.SqlCommandRepository.LogEntry(++seq, "INFO", null, null, null, null,
                            "日志已超过 " + COMMAND_LOG_LIMIT + " 行，之后只记录错误与结束信息。"));
                }
                if (!important || errorsAfterLimit >= COMMAND_ERROR_LIMIT) return;
                errorsAfterLimit++;
            }
            accepted++;
            String value = text == null ? "" : text.length() > SqlCommandOutput.MAX_TEXT ? text.substring(0, SqlCommandOutput.MAX_TEXT) + "…" : text;
            buffer.add(new com.example.dbadmin.repo.SqlCommandRepository.LogEntry(++seq, kind,
                    statementIndex, startLine, endLine, elapsed, value));
            if (buffer.size() >= 100 || System.nanoTime() - lastFlush > 300_000_000L) flush();
        }

        void flush() {
            lastFlush = System.nanoTime();
            if (buffer.isEmpty() && !pendingTruncation) return;
            try {
                files.commands.append(id, List.copyOf(buffer), pendingTruncation);
            } catch (Exception error) {
                log.warn("Command window log write failed: taskId={}", id, error);
            }
            buffer.clear();
            pendingTruncation = false;
        }
    }

    private String cleanupWarning(SqlFileExecution job, Exception error) {
        log.warn("SQL file resource cleanup failed after execution completed: taskId={}, connectionId={}, mode={}",
                job.id(), job.connectionId(), job.transaction().mode(), error);
        String warning = "；收尾警告（执行及事务结果保持不变，请勿因此重跑）：" + files.safeMessage(error) + suppressedErrors(error);
        return warning;
    }

    private String suppressedErrors(Throwable error) {
        if (error.getSuppressed().length == 0) return "";
        return "；收尾错误：" + java.util.Arrays.stream(error.getSuppressed())
                .map(files::safeMessage).collect(java.util.stream.Collectors.joining("；"));
    }

    /**
     * Oracle 系的 CREATE OR REPLACE / ALTER … COMPILE 编译失败也返回成功，对象以 INVALID 留在库里。
     * 脚本照 SQL*Plus 的习惯继续往下跑（中途停下会让一份部署脚本只装一半），但每个坏对象都要记进任务结果，
     * 否则调用方只能自己去翻 ALL_ERRORS —— 部署 78 个过程时那就是 78 次核对。
     */
    private final class CompilationCollector {
        private final long id;
        private final DatabaseDialect dialect;
        private final CompilationErrorLog log = new CompilationErrorLog(200);

        CompilationCollector(long id, DatabaseDialect dialect) {
            this.id = id;
            this.dialect = dialect;
        }

        void collect(Connection connection, String sql) {
            collectNew(connection, sql);
        }

        /** 与 collect 相同，另外返回这条语句新增的编译错误行，命令窗口把它们紧跟在语句后面打出来。 */
        List<String> collectNew(Connection connection, String sql) {
            if (dialect.compilationErrorsSql() == null) return List.of();
            var target = CompilationDiagnostics.target(sql);
            if (target.isEmpty()) return List.of();
            var errors = CompilationDiagnostics.read(connection, dialect, sql, null, properties.getSql().getTimeoutSeconds());
            if (log.record(target.get(), errors)) {
                List<String> lines = log.lines();
                try { jobs.updateCompilationErrors(id, lines.isEmpty() ? null : String.join("\n", lines)); } catch (Exception ignored) { }
            }
            return errors.stream().map(error -> CompilationDiagnostics.describe(target.get(), error)).toList();
        }

        String summary() { return log.summary(); }
    }

    private static final class StatementFailure extends Exception {
        private final long index;
        private final String sql;
        private StatementFailure(long index, String sql, Throwable cause) { super(cause); this.index = index; this.sql = sql; }
    }
}
