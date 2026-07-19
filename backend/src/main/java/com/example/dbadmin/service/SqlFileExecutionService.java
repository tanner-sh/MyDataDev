package com.example.dbadmin.service;

import com.example.dbadmin.api.ApiProblemException;
import com.example.dbadmin.config.AppProperties;
import com.example.dbadmin.core.DatabaseDialect;
import com.example.dbadmin.core.DialectRegistry;
import com.example.dbadmin.dto.ApiDtos.SqlFileExecutionPage;
import com.example.dbadmin.dto.ApiDtos.SqlFileExecutionResponse;
import com.example.dbadmin.model.DbConnection;
import com.example.dbadmin.model.SqlFileExecution;
import com.example.dbadmin.repo.AuditRepository;
import com.example.dbadmin.repo.SqlFileExecutionRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.regex.Pattern;

@Service
public class SqlFileExecutionService {
    private static final int ERROR_SQL_PREVIEW = 2_000;
    private static final Pattern TRANSACTION_CONTROL = Pattern.compile(
            "(?is)^(?:\\s|--[^\\r\\n]*(?:\\R|$)|/\\*.*?\\*/)*(?:START\\s+TRANSACTION|BEGIN(?:\\s+(?:WORK|TRAN(?:SACTION)?))?\\s*$|COMMIT\\b|ROLLBACK\\b|SAVEPOINT\\b|RELEASE\\s+SAVEPOINT\\b|SET\\s+AUTOCOMMIT\\b)");

    private final SqlFileExecutionRepository jobs;
    private final ConnectionService connections;
    private final ExecutionGuard guard;
    private final SqlStatementClassifier classifier;
    private final SqlScriptSplitter scriptSplitter;
    private final DialectRegistry dialects;
    private final MetadataService metadata;
    private final AuditRepository audit;
    private final AppProperties properties;
    private final SqlFileExecutionCoordinator coordinator;
    private final Map<Long, Statement> runningStatements = new ConcurrentHashMap<>();

    public SqlFileExecutionService(SqlFileExecutionRepository jobs, ConnectionService connections, ExecutionGuard guard,
                                   SqlStatementClassifier classifier, SqlScriptSplitter scriptSplitter, DialectRegistry dialects, MetadataService metadata,
                                   AuditRepository audit, AppProperties properties, SqlFileExecutionCoordinator coordinator) {
        this.jobs = jobs;
        this.connections = connections;
        this.guard = guard;
        this.classifier = classifier;
        this.scriptSplitter = scriptSplitter;
        this.dialects = dialects;
        this.metadata = metadata;
        this.audit = audit;
        this.properties = properties;
        this.coordinator = coordinator;
    }

    @PostConstruct
    public void recoverInterruptedJobs() {
        var interrupted = jobs.findActive();
        jobs.failStaleRunning();
        interrupted.forEach(this::deleteFileQuietly);
    }

    public SqlFileExecutionResponse upload(long connectionId, String rawFileName, long contentLength, InputStream input, String actor) throws Exception {
        DbConnection connection = connections.require(connectionId);
        String fileName = safeFileName(rawFileName);
        if (!fileName.toLowerCase(Locale.ROOT).endsWith(".sql")) throw new IllegalArgumentException("只支持 .sql 文件。");
        long maximum = properties.getSqlFile().getMaxUploadBytes();
        if (contentLength == 0) throw new IllegalArgumentException("SQL 文件不能为空。");
        if (contentLength > maximum) throw new IllegalArgumentException("SQL 文件超过允许大小。");

        Path root = root();
        Files.createDirectories(root);
        if (contentLength > 0) {
            FileStore store = Files.getFileStore(root);
            if (store.getUsableSpace() < contentLength + 100L * 1024 * 1024) {
                throw new IllegalStateException("服务器 SQL 文件目录剩余空间不足。");
            }
        }
        Path target = root.resolve("sql-" + UUID.randomUUID() + ".sql").normalize();
        if (!target.startsWith(root)) throw new IllegalArgumentException("SQL 文件路径不安全。");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long size = 0;
        try (var output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
            byte[] buffer = new byte[128 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) continue;
                size += read;
                if (size > maximum) throw new IllegalArgumentException("SQL 文件超过允许大小。");
                digest.update(buffer, 0, read);
                output.write(buffer, 0, read);
            }
        } catch (Exception error) {
            Files.deleteIfExists(target);
            throw error;
        }
        if (size == 0) {
            Files.deleteIfExists(target);
            throw new IllegalArgumentException("SQL 文件不能为空。");
        }

        Instant expiresAt = Instant.now().plus(Math.max(1, properties.getSqlFile().getReadyTtlHours()), ChronoUnit.HOURS);
        SqlFileExecution draft = new SqlFileExecution(0, connection.id(), connection.name(), connection.dbType(), fileName,
                target.toString(), size, HexFormat.of().formatHex(digest.digest()), null, "ANALYZING", "DETECTING_ENCODING",
                0, null, 0, 0, 0, 0, 0, 0, 0, null, null, "正在分析 SQL 文件。", false, false,
                false, actor, expiresAt, null, null, Instant.now());
        long id;
        try {
            id = jobs.insert(draft);
        } catch (Exception error) {
            Files.deleteIfExists(target);
            throw error;
        }
        try {
            coordinator.submit(id, () -> analyze(id));
        } catch (RejectedExecutionException error) {
            jobs.markTerminal(id, "FAILED", "QUEUE_FULL", 0, 0, 0, null, null, "SQL 文件分析队列已满，任务未启动。");
            Files.deleteIfExists(target);
            throw new ApiProblemException(HttpStatus.TOO_MANY_REQUESTS, "SQL_FILE_QUEUE_FULL", "SQL 文件任务队列已满，请稍后重试。");
        }
        audit.log(actor, "SQL_FILE_UPLOAD", "connection:" + connectionId, fileName + "; size=" + size);
        return response(require(id));
    }

    public SqlFileExecutionResponse start(long id, String productionConfirmation, String actor) throws Exception {
        SqlFileExecution job = require(id);
        if (!"READY".equals(job.status())) throw new ApiProblemException(HttpStatus.CONFLICT, "SQL_FILE_NOT_READY", "SQL 文件尚未完成解析或已经执行。");
        if (job.expiresAt().isBefore(Instant.now())) throw new ApiProblemException(HttpStatus.CONFLICT, "SQL_FILE_EXPIRED", "SQL 文件已过期，请重新选择文件。");
        DbConnection connection = connections.require(job.connectionId());
        if (!connection.dbType().equalsIgnoreCase(job.targetDbType())) throw new IllegalStateException("目标连接类型已变化，请重新选择文件。");
        boolean mutation = job.mutationCount() + job.ddlCount() + job.unknownCount() > 0;
        if (mutation) guard.requireMutationAllowed(connection, productionConfirmation);
        else guard.requireQueryAllowed(connection, SqlStatementClassifier.Kind.QUERY, productionConfirmation);
        if (jobs.countRunningByConnection(connection.id()) > 0) {
            throw new ApiProblemException(HttpStatus.CONFLICT, "SQL_FILE_ALREADY_RUNNING", "该连接已有 SQL 文件任务正在执行。");
        }
        if (!jobs.queue(id)) throw new ApiProblemException(HttpStatus.CONFLICT, "SQL_FILE_NOT_READY", "SQL 文件任务状态已发生变化。");
        try {
            coordinator.submit(id, () -> execute(id));
        } catch (RejectedExecutionException error) {
            jobs.markTerminal(id, "FAILED", "QUEUE_FULL", 0, 0, 0, null, null, "SQL 文件执行队列已满，任务未启动。");
            deleteFileQuietly(job);
            throw new ApiProblemException(HttpStatus.TOO_MANY_REQUESTS, "SQL_FILE_QUEUE_FULL", "SQL 文件执行队列已满，请稍后重试。");
        }
        audit.log(actor, "SQL_FILE_START", "connection:" + connection.id(), "job=" + id + "; file=" + job.fileName());
        return response(require(id));
    }

    public SqlFileExecutionResponse cancel(long id, String actor) {
        SqlFileExecution job = require(id);
        if (!Set.of("ANALYZING", "READY", "QUEUED", "RUNNING").contains(job.status())) return response(job);
        jobs.requestCancel(id);
        Statement statement = runningStatements.get(id);
        if (statement != null) try { statement.cancel(); } catch (Exception ignored) { }
        coordinator.cancel(id);
        jobs.markTerminal(id, "CANCELLED", "CANCELLED", job.statementCurrent(), job.successCount(), job.queryRowCount(),
                null, null, "SQL 文件任务已取消。");
        deleteFileQuietly(job);
        audit.log(actor, "SQL_FILE_CANCEL", "connection:" + job.connectionId(), "job=" + id);
        return response(require(id));
    }

    public SqlFileExecutionResponse get(long id) { return response(require(id)); }

    public SqlFileExecutionPage list(Long connectionId, Integer page, Integer pageSize) {
        int safePage = Math.max(page == null ? 0 : page, 0);
        int size = Math.min(Math.max(pageSize == null ? 20 : pageSize, 1), 100);
        var rows = jobs.findPage(connectionId, size + 1, (long) safePage * size);
        boolean hasMore = rows.size() > size;
        if (hasMore) rows = rows.subList(0, size);
        return new SqlFileExecutionPage(rows.stream().map(this::response).toList(), safePage, size, hasMore);
    }

    private void analyze(long id) {
        SqlFileExecution job = require(id);
        try {
            ensureNotCancelled(id);
            Path path = checkedPath(job);
            Charset charset = SqlFileStatementReader.detectCharset(path);
            long[] counts = new long[5];
            boolean[] flags = new boolean[2];
            SqlFileStatementReader.read(path, charset, job.targetDbType(), properties.getSqlFile().getMaxStatementChars(), (index, sql) -> {
                ensureNotCancelled(id);
                var parts = scriptSplitter.split(sql);
                if (parts.stream().anyMatch(part -> TRANSACTION_CONTROL.matcher(part.sql()).find())) {
                    throw new IllegalArgumentException("第 " + index + " 个执行单元包含事务控制语句；SQL 文件任务采用逐条提交，请移除该语句。");
                }
                SqlStatementClassifier.Kind kind = strongestKind(parts.stream().map(part -> classifier.classify(part.sql())).toList());
                counts[0]++;
                switch (kind) {
                    case QUERY -> counts[1]++;
                    case MUTATION -> counts[2]++;
                    case DDL -> { counts[3]++; flags[0] = true; }
                    case UNKNOWN -> counts[4]++;
                }
                flags[1] = flags[1] || parts.stream().anyMatch(part -> classifier.changesSession(part.sql()));
            }, bytes -> {
                ensureNotCancelled(id);
                jobs.updateAnalysisProgress(id, Math.min(bytes, job.fileSize()));
            });
            if (counts[0] == 0) throw new IllegalArgumentException("SQL 文件中没有可执行语句。");
            jobs.markReady(id, charset.name(), counts[0], counts[1], counts[2], counts[3], counts[4], flags[0], flags[1]);
        } catch (CancelledException ignored) {
            jobs.markTerminal(id, "CANCELLED", "CANCELLED", 0, 0, 0, null, null, "SQL 文件解析已取消。");
            deleteFileQuietly(job);
        } catch (Exception error) {
            jobs.markTerminal(id, "FAILED", "ANALYSIS_FAILED", 0, 0, 0, null, null, safeMessage(error));
            deleteFileQuietly(job);
        }
    }

    private void execute(long id) {
        SqlFileExecution job = require(id);
        long[] current = {0};
        long[] success = {0};
        long[] queryRows = {0};
        try {
            jobs.markRunning(id);
            ensureNotCancelled(id);
            verifyChecksum(job);
            DbConnection target = connections.require(job.connectionId());
            DatabaseDialect dialect = dialects.dialectFor(target);
            try (Connection connection = connections.open(job.connectionId())) {
                connection.setAutoCommit(false);
                SqlFileStatementReader.read(checkedPath(job), Charset.forName(job.detectedCharset()), job.targetDbType(),
                        properties.getSqlFile().getMaxStatementChars(), (index, sql) -> {
                    ensureNotCancelled(id);
                    current[0] = index;
                    try (Statement statement = connection.createStatement()) {
                        runningStatements.put(id, statement);
                        dialect.configureStreamingStatement(connection, statement, 500, properties.getSqlFile().getStatementTimeoutSeconds());
                        boolean result = statement.execute(sql);
                        while (true) {
                            if (result) {
                                try (ResultSet rows = statement.getResultSet()) {
                                    while (rows.next()) {
                                        queryRows[0]++;
                                        if ((queryRows[0] & 4095) == 0) ensureNotCancelled(id);
                                    }
                                }
                            } else if (statement.getUpdateCount() == -1) {
                                break;
                            }
                            result = statement.getMoreResults();
                        }
                        connection.commit();
                        success[0]++;
                        jobs.updateExecutionProgress(id, index, success[0], queryRows[0], "已执行第 " + index + " / " + job.statementTotal() + " 条语句。");
                    } catch (CancelledException error) {
                        try { connection.rollback(); } catch (Exception ignored) { }
                        throw error;
                    } catch (Exception error) {
                        try { connection.rollback(); } catch (Exception ignored) { }
                        throw new StatementFailure(index, sql, error);
                    } finally {
                        runningStatements.remove(id);
                    }
                }, ignored -> ensureNotCancelled(id));
            }
            jobs.markTerminal(id, "SUCCESS", "COMPLETED", current[0], success[0], queryRows[0], null, null,
                    "SQL 文件执行完成，共成功 " + success[0] + " 条语句。");
            audit.log(job.actor(), "SQL_FILE_SUCCESS", "connection:" + job.connectionId(), "job=" + id + "; statements=" + success[0]);
        } catch (StatementFailure failure) {
            jobs.markTerminal(id, "FAILED", "EXECUTION_FAILED", failure.index, success[0], queryRows[0], failure.index,
                    preview(failure.sql), safeMessage(failure.getCause()));
            audit.log(job.actor(), "SQL_FILE_FAILED", "connection:" + job.connectionId(), "job=" + id + "; statement=" + failure.index + "; " + safeMessage(failure.getCause()));
        } catch (CancelledException ignored) {
            jobs.markTerminal(id, "CANCELLED", "CANCELLED", current[0], success[0], queryRows[0], null, null, "SQL 文件执行已取消。");
        } catch (Exception error) {
            jobs.markTerminal(id, "FAILED", "EXECUTION_FAILED", current[0], success[0], queryRows[0], null, null, safeMessage(error));
            audit.log(job.actor(), "SQL_FILE_FAILED", "connection:" + job.connectionId(), "job=" + id + "; " + safeMessage(error));
        } finally {
            runningStatements.remove(id);
            if (job.metadataChanged()) metadata.invalidateConnection(job.connectionId());
            if (job.sessionChanged()) connections.resetRemoteSession(job.connectionId());
            deleteFileQuietly(job);
        }
    }

    @Scheduled(fixedDelay = 60 * 60 * 1000, initialDelay = 60 * 1000)
    public void cleanupExpiredReadyJobs() {
        for (SqlFileExecution job : jobs.findExpiredReady(Instant.now())) {
            jobs.markTerminal(job.id(), "EXPIRED", "EXPIRED", 0, 0, 0, null, null, "SQL 文件等待确认超时，已自动清理。");
            deleteFileQuietly(job);
        }
    }

    private SqlFileExecution require(long id) {
        return jobs.findById(id).orElseThrow(() -> new IllegalArgumentException("SQL file execution not found: " + id));
    }

    private void ensureNotCancelled(long id) {
        if (Thread.currentThread().isInterrupted() || jobs.isCancelRequested(id)) throw new CancelledException();
    }

    private Path root() { return Path.of(properties.getSqlFile().getDirectory()).toAbsolutePath().normalize(); }

    private Path checkedPath(SqlFileExecution job) { return FileIntegrity.checkedPath(root(), job.filePath()); }

    private void verifyChecksum(SqlFileExecution job) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(checkedPath(job))) {
            byte[] buffer = new byte[128 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                ensureNotCancelled(job.id());
                if (read > 0) digest.update(buffer, 0, read);
            }
        }
        if (!job.checksumSha256().equalsIgnoreCase(HexFormat.of().formatHex(digest.digest()))) {
            throw new IllegalStateException("SQL 文件校验失败，文件内容已发生变化。");
        }
    }

    private void deleteFileQuietly(SqlFileExecution job) {
        try { Files.deleteIfExists(checkedPath(job)); } catch (Exception ignored) { }
    }

    private String safeFileName(String raw) {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("缺少 SQL 文件名。");
        String value = Path.of(raw).getFileName().toString().trim();
        if (value.isBlank() || value.length() > 500 || value.indexOf('\0') >= 0) throw new IllegalArgumentException("SQL 文件名不合法。");
        return value;
    }

    private String preview(String sql) { return sql.length() <= ERROR_SQL_PREVIEW ? sql : sql.substring(0, ERROR_SQL_PREVIEW) + "…"; }

    private SqlStatementClassifier.Kind strongestKind(java.util.List<SqlStatementClassifier.Kind> kinds) {
        if (kinds.stream().anyMatch(kind -> kind == SqlStatementClassifier.Kind.DDL)) return SqlStatementClassifier.Kind.DDL;
        if (kinds.stream().anyMatch(kind -> kind == SqlStatementClassifier.Kind.MUTATION)) return SqlStatementClassifier.Kind.MUTATION;
        if (kinds.stream().anyMatch(kind -> kind == SqlStatementClassifier.Kind.UNKNOWN)) return SqlStatementClassifier.Kind.UNKNOWN;
        return SqlStatementClassifier.Kind.QUERY;
    }
    private String safeMessage(Throwable error) {
        String value = error == null ? null : error.getMessage();
        return value == null || value.isBlank() ? (error == null ? "未知错误" : error.getClass().getSimpleName()) : (value.length() <= 10_000 ? value : value.substring(0, 10_000));
    }

    private SqlFileExecutionResponse response(SqlFileExecution job) {
        return new SqlFileExecutionResponse(job.id(), job.connectionId(), job.connectionName(), job.targetDbType(), job.fileName(),
                job.fileSize(), job.checksumSha256(), job.detectedCharset(), job.status(), job.phase(), job.processedBytes(),
                job.statementTotal(), job.statementCurrent(), job.queryCount(), job.mutationCount(), job.ddlCount(), job.unknownCount(),
                job.successCount(), job.queryRowCount(), job.failedStatementIndex(), job.failedSqlPreview(), job.message(),
                job.metadataChanged(), job.sessionChanged(), job.cancelRequested(), job.expiresAt(), job.startedAt(), job.finishedAt(), job.createdAt());
    }

    private static final class CancelledException extends RuntimeException { }
    private static final class StatementFailure extends Exception {
        private final long index;
        private final String sql;
        private StatementFailure(long index, String sql, Throwable cause) { super(cause); this.index = index; this.sql = sql; }
    }
}
