package com.example.dbadmin.service;

import com.example.dbadmin.config.AppProperties;
import com.example.dbadmin.core.DatabaseDialect;
import com.example.dbadmin.core.DialectRegistry;
import com.example.dbadmin.dto.ApiDtos.BackupTaskRequest;
import com.example.dbadmin.dto.ApiDtos.CronPreviewResponse;
import com.example.dbadmin.dto.ApiDtos.BackupHistoryPage;
import com.example.dbadmin.dto.ApiDtos.BackupRunResponse;
import com.example.dbadmin.dto.ApiDtos.BackupTaskPage;
import com.example.dbadmin.api.ApiProblemException;
import com.example.dbadmin.model.BackupHistory;
import com.example.dbadmin.model.BackupTask;
import com.example.dbadmin.model.DbConnection;
import com.example.dbadmin.repo.AuditRepository;
import com.example.dbadmin.repo.BackupHistoryRepository;
import com.example.dbadmin.repo.BackupTaskRepository;
import com.example.dbadmin.repo.RestoreJobRepository;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.annotation.PostConstruct;

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.net.URI;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;

@Service
public class BackupService {
    private static final Logger log = LoggerFactory.getLogger(BackupService.class);
    private final BackupTaskRepository repository;
    private final BackupHistoryRepository historyRepository;
    private final ConnectionService connections;
    private final AuditRepository audit;
    private final AppProperties properties;
    private final DialectRegistry dialectRegistry;
    private final BackupExecutionCoordinator coordinator;
    private final RestoreJobRepository restoreJobs;
    private final NativeToolLocator nativeTools;
    private final Object[] taskLocks = taskLocks();

    @Autowired
    public BackupService(BackupTaskRepository repository, BackupHistoryRepository historyRepository, ConnectionService connections, AuditRepository audit, AppProperties properties, DialectRegistry dialectRegistry, BackupExecutionCoordinator coordinator, RestoreJobRepository restoreJobs, NativeToolLocator nativeTools) {
        this.repository = repository;
        this.historyRepository = historyRepository;
        this.connections = connections;
        this.audit = audit;
        this.properties = properties;
        this.dialectRegistry = dialectRegistry;
        this.coordinator = coordinator;
        this.restoreJobs = restoreJobs;
        this.nativeTools = nativeTools;
    }

    public BackupService(BackupTaskRepository repository, BackupHistoryRepository historyRepository, ConnectionService connections, AuditRepository audit, AppProperties properties, DialectRegistry dialectRegistry, BackupExecutionCoordinator coordinator) {
        this(repository, historyRepository, connections, audit, properties, dialectRegistry, coordinator, null, new NativeToolLocator(properties));
    }

    public BackupService(BackupTaskRepository repository, BackupHistoryRepository historyRepository, ConnectionService connections, AuditRepository audit, AppProperties properties, DialectRegistry dialectRegistry) {
        this(repository, historyRepository, connections, audit, properties, dialectRegistry, new BackupExecutionCoordinator(), null, new NativeToolLocator(properties));
    }

    public BackupService(BackupTaskRepository repository, BackupHistoryRepository historyRepository, ConnectionService connections, AuditRepository audit, AppProperties properties) {
        this(repository, historyRepository, connections, audit, properties, new DialectRegistry());
    }

    @PostConstruct
    public void recoverInterruptedTasks() {
        repository.failStaleRunningTasks();
    }

    public List<BackupTask> list() {
        return repository.findAll();
    }

    public List<BackupTask> list(Long connectionId) {
        return connectionId == null ? repository.findAll() : repository.findByConnectionId(connectionId);
    }

    public BackupTaskPage page(long connectionId, String keyword, String status, Integer page, Integer pageSize) {
        int safePage = Math.max(page == null ? 0 : page, 0);
        int size = Math.min(Math.max(pageSize == null ? 10 : pageSize, 1), 100);
        List<BackupTask> rows = repository.findPage(connectionId, keyword, status, size + 1, (long) safePage * size);
        boolean hasMore = rows.size() > size;
        if (hasMore) rows = rows.subList(0, size);
        return new BackupTaskPage(List.copyOf(rows), safePage, size, hasMore);
    }

    public BackupTask create(BackupTaskRequest request, String actor) {
        DbConnection connection = connections.require(request.connectionId());
        BackupTask task = taskFromRequest(0, request, connection, null, null, null, null, null);
        long id = repository.insert(task);
        audit.log(actor, "BACKUP_TASK_CREATE", request.name(), request.scope());
        return repository.findById(id).orElseThrow();
    }

    public BackupTask update(long id, BackupTaskRequest request, String actor) {
        synchronized (taskLock(id)) {
            requireNotRunning(id);
            repository.findById(id).orElseThrow(() -> new IllegalArgumentException("Backup task not found: " + id));
            DbConnection connection = connections.require(request.connectionId());
            BackupTask task = taskFromRequest(id, request, connection, null, null, null, null, null);
            repository.update(id, task);
            audit.log(actor, "BACKUP_TASK_UPDATE", request.name(), request.scope());
            return repository.findById(id).orElseThrow();
        }
    }

    public BackupTask setEnabled(long id, boolean enabled, String actor) {
        synchronized (taskLock(id)) {
            BackupTask task = repository.findById(id).orElseThrow(() -> new IllegalArgumentException("Backup task not found: " + id));
            if (enabled) {
                validateCron(task.cron(), true);
            }
            repository.updateEnabled(id, enabled);
            audit.log(actor, enabled ? "BACKUP_TASK_ENABLE" : "BACKUP_TASK_DISABLE", task.name(), task.cron());
            return repository.findById(id).orElseThrow();
        }
    }

    public CronPreviewResponse previewSchedule(String cronValue) {
        String cron = blankToNull(cronValue);
        if (cron == null) {
            throw new IllegalArgumentException("cron 表达式不能为空。");
        }
        CronExpression expression;
        try {
            expression = CronExpression.parse(cron);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("cron 表达式不合法：" + cron);
        }
        ZoneId zone = ZoneId.systemDefault();
        ZonedDateTime cursor = ZonedDateTime.now(zone);
        List<String> nextRuns = new ArrayList<>(3);
        for (int index = 0; index < 3; index++) {
            cursor = expression.next(cursor);
            if (cursor == null) {
                break;
            }
            nextRuns.add(cursor.toString());
        }
        return new CronPreviewResponse(cron, zone.getId(), nextRuns);
    }

    public void delete(long id, boolean deleteFile, String actor) throws Exception {
        synchronized (taskLock(id)) {
            requireNotRunning(id);
            if (restoreJobs != null && restoreJobs.countActiveByBackupTaskId(id) > 0) {
                throw new ApiProblemException(HttpStatus.CONFLICT, "BACKUP_IN_USE_BY_RESTORE", "该备份任务的历史文件正在用于恢复，暂不能删除。");
            }
            BackupTask task = repository.findById(id).orElseThrow(() -> new IllegalArgumentException("Backup task not found: " + id));
            if (deleteFile) {
                long offset = 0;
                while (true) {
                    List<BackupHistory> histories = historyRepository.findPageByTaskId(id, 100, offset);
                    for (BackupHistory history : histories) deleteHistoryFile(history);
                    if (histories.size() < 100) break;
                    offset += histories.size();
                }
            }
            historyRepository.deleteByTaskId(id);
            repository.delete(id);
            audit.log(actor, "BACKUP_TASK_DELETE", task.name(), deleteFile ? "deleteFile=true" : "deleteFile=false");
        }
    }

    public BackupTask run(long id, String actor) throws Exception {
        return runInternal(id, actor, null);
    }

    private BackupTask runInternal(long id, String actor, Long executionId) throws Exception {
        BackupTask task = repository.findById(id).orElseThrow(() -> new IllegalArgumentException("Backup task not found: " + id));
        DbConnection connection = connections.require(task.connectionId());
        Instant startedAt = Instant.now();
        if (executionId != null) {
            historyRepository.updateExecution(executionId, "RUNNING", "PREPARING", 0, 1L, "正在准备备份。", null, null, null, null);
        }
        BackupFile backup;
        try {
            backup = runBackup(task, connection);
        } catch (Exception e) {
            boolean cancelled = e instanceof InterruptedException || Thread.currentThread().isInterrupted()
                    || executionId != null && historyRepository.findById(executionId).map(BackupHistory::cancelRequested).orElse(false);
            String message = cancelled ? "备份已取消。" : e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            String status = cancelled ? "CANCELLED" : "FAILED";
            if (executionId == null) {
                recordHistory(new BackupHistory(0, id, connection.id(), status, message, null, null, startedAt, Instant.now(),
                        fileFormat(task.backupMethod()), normalizeBackupMethod(task.backupMethod()), connection.dbType(), null,
                        status, 0L, 1L, cancelled));
            } else {
                historyRepository.updateExecution(executionId, status, status, 0, 1L, message, null, null, null, Instant.now());
            }
            updateStatus(id, status, message, null, null);
            audit.log(actor, cancelled ? "BACKUP_TASK_CANCELLED" : "BACKUP_TASK_RUN_FAILED", task.name(), message);
            throw e;
        }
        String message = methodLabel(task.backupMethod()) + " 备份已生成：" + backup.path().getFileName();
        String filePath = backup.path().toAbsolutePath().normalize().toString();
        String checksum = FileIntegrity.sha256(backup.path());
        if (executionId == null) {
            recordHistory(new BackupHistory(0, id, connection.id(), "SUCCESS", message, filePath, backup.size(), startedAt, Instant.now(),
                    fileFormat(task.backupMethod()), normalizeBackupMethod(task.backupMethod()), connection.dbType(), checksum,
                    "COMPLETED", 1L, 1L, false));
        } else {
            historyRepository.updateExecution(executionId, "SUCCESS", "COMPLETED", 1, 1L, message, filePath, backup.size(), checksum, Instant.now());
        }
        updateStatus(id, "SUCCESS", message, filePath, backup.size());
        audit.log(actor, "BACKUP_TASK_RUN", task.name(), message);
        cleanupRetention(task);
        return repository.findById(id).orElseThrow();
    }

    private String fileFormat(String method) {
        return switch (normalizeBackupMethod(method)) {
            case "MYSQLDUMP" -> "MYSQLDUMP";
            case "ORACLE_EXP" -> "ORACLE_DMP";
            default -> "SQL";
        };
    }

    private void cleanupRetention(BackupTask task) {
        if (task.retentionDays() == null && task.retentionCount() == null) return;
        try {
            List<BackupHistory> successful = historyRepository.findSuccessfulByTaskId(task.id());
            Instant cutoff = task.retentionDays() == null ? null : Instant.now().minus(task.retentionDays(), java.time.temporal.ChronoUnit.DAYS);
            for (int index = 0; index < successful.size(); index++) {
                BackupHistory history = successful.get(index);
                boolean overCount = task.retentionCount() != null && index >= task.retentionCount();
                boolean overDays = cutoff != null && history.finishedAt() != null && history.finishedAt().isBefore(cutoff);
                if (!overCount && !overDays) continue;
                if (restoreJobs != null && restoreJobs.countActiveBySource("HISTORY", history.id()) > 0) continue;
                deleteHistoryFile(history);
                historyRepository.delete(history.id());
            }
            refreshTaskSummary(task.id());
        } catch (Exception error) {
            log.warn("Unable to apply backup retention task={}", task.id(), error);
        }
    }

    private void recordHistory(BackupHistory history) {
        try {
            historyRepository.insert(history);
        } catch (RuntimeException error) {
            log.error("Unable to persist backup history task={} status={}", history.taskId(), history.status(), error);
        }
    }

    private void updateStatus(long taskId, String status, String message, String filePath, Long fileSize) {
        try {
            if (filePath == null && fileSize == null) repository.updateStatus(taskId, status, message);
            else repository.updateStatus(taskId, status, message, filePath, fileSize);
        } catch (RuntimeException error) {
            log.error("Unable to persist backup task status task={} status={}", taskId, status, error);
        }
    }

    public BackupTask enqueue(long id, String actor) {
        return enqueueInternal(id, actor).task();
    }

    public BackupRunResponse enqueueWithExecution(long id, String actor) {
        return enqueueInternal(id, actor);
    }

    private BackupRunResponse enqueueInternal(long id, String actor) {
        synchronized (taskLock(id)) {
            BackupTask task = repository.findById(id).orElseThrow(() -> new IllegalArgumentException("Backup task not found: " + id));
            if (coordinator.isRunning(id)) {
                throw new ApiProblemException(HttpStatus.CONFLICT, "BACKUP_ALREADY_RUNNING", "该备份任务正在执行，请勿重复启动。");
            }
            DbConnection connection = connections.require(task.connectionId());
            long connectionId = connection == null ? task.connectionId() : connection.id();
            String sourceDbType = connection == null ? null : connection.dbType();
            long executionId = historyRepository.insert(new BackupHistory(0, id, connectionId, "QUEUED", "备份任务已进入后台执行队列。",
                    null, null, Instant.now(), null, fileFormat(task.backupMethod()), normalizeBackupMethod(task.backupMethod()),
                    sourceDbType, null, "QUEUED", 0L, 1L, false));
            try {
                boolean accepted = coordinator.submit(
                        id,
                        () -> repository.updateStatus(id, "RUNNING", "备份任务已进入后台执行队列。"),
                        () -> {
                            try {
                                runInternal(id, actor, executionId);
                            } catch (Exception ignored) {
                                // run records the failed history and task status.
                            }
                        }
                );
                if (!accepted) {
                    throw new ApiProblemException(HttpStatus.CONFLICT, "BACKUP_ALREADY_RUNNING", "该备份任务正在执行，请勿重复启动。");
                }
            } catch (RejectedExecutionException e) {
                repository.updateStatus(id, "FAILED", "备份执行队列已满，任务未启动。");
                historyRepository.updateExecution(executionId, "FAILED", "FAILED", 0, 1L, "备份执行队列已满，任务未启动。", null, null, null, Instant.now());
                throw new ApiProblemException(HttpStatus.TOO_MANY_REQUESTS, "BACKUP_QUEUE_FULL", "备份执行队列已满，请稍后重试。");
            }
            return new BackupRunResponse(repository.findById(task.id()).orElseThrow(), historyRepository.findById(executionId).orElseThrow());
        }
    }

    public BackupHistory cancel(long taskId, long historyId, String actor) {
        synchronized (taskLock(taskId)) {
            BackupHistory history = historyRepository.findByTaskIdAndId(taskId, historyId)
                    .orElseThrow(() -> new IllegalArgumentException("Backup history not found: " + historyId));
            if (!Set.of("QUEUED", "RUNNING").contains(history.status())) return history;
            historyRepository.requestCancel(historyId);
            coordinator.cancel(taskId);
            historyRepository.updateExecution(historyId, "CANCELLED", "CANCELLED", value(history.progressCurrent()), history.progressTotal(), "备份已取消。", null, null, null, Instant.now());
            repository.updateStatus(taskId, "CANCELLED", "备份已取消。");
            audit.log(actor, "BACKUP_TASK_CANCEL", String.valueOf(taskId), "history=" + historyId);
            return historyRepository.findById(historyId).orElseThrow();
        }
    }

    public BackupHistoryPage historyByConnection(long connectionId, Integer page, Integer pageSize) {
        int safePage = Math.max(page == null ? 0 : page, 0);
        int safePageSize = Math.min(Math.max(pageSize == null ? 20 : pageSize, 1), 100);
        List<BackupHistory> rows = historyRepository.findPageByConnectionId(connectionId, safePageSize + 1, (long) safePage * safePageSize);
        boolean hasMore = rows.size() > safePageSize;
        if (hasMore) rows = rows.subList(0, safePageSize);
        return new BackupHistoryPage(List.copyOf(rows), safePage, safePageSize, hasMore);
    }

    private long value(Long value) {
        return value == null ? 0 : value;
    }

    public Path backupFile(long id) {
        BackupTask task = repository.findById(id).orElseThrow(() -> new IllegalArgumentException("Backup task not found: " + id));
        if (task.lastFilePath() == null || task.lastFilePath().isBlank()) {
            throw new IllegalStateException("该备份任务还没有生成可下载文件。");
        }
        Path path = checkedBackupPath(task.lastFilePath());
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("备份文件不存在，请重新执行备份任务。");
        }
        return path;
    }

    public List<BackupHistory> history(long taskId) {
        repository.findById(taskId).orElseThrow(() -> new IllegalArgumentException("Backup task not found: " + taskId));
        return historyRepository.findByTaskId(taskId);
    }

    public BackupHistoryPage history(long taskId, Integer page, Integer pageSize) {
        repository.findById(taskId).orElseThrow(() -> new IllegalArgumentException("Backup task not found: " + taskId));
        int safePage = Math.max(page == null ? 0 : page, 0);
        int safePageSize = Math.min(Math.max(pageSize == null ? 20 : pageSize, 1), 100);
        long offset = (long) safePage * safePageSize;
        if (offset > 1_000_000) {
            throw new IllegalArgumentException("备份历史分页偏移过大，请从较早页面重新浏览。");
        }
        List<BackupHistory> rows = historyRepository.findPageByTaskId(taskId, safePageSize + 1, offset);
        boolean hasMore = rows.size() > safePageSize;
        if (hasMore) rows = rows.subList(0, safePageSize);
        return new BackupHistoryPage(List.copyOf(rows), safePage, safePageSize, hasMore);
    }

    public Path historyFile(long taskId, long historyId) {
        BackupHistory history = historyRepository.findByTaskIdAndId(taskId, historyId)
                .orElseThrow(() -> new IllegalArgumentException("Backup history not found: " + historyId));
        if (history.filePath() == null || history.filePath().isBlank()) {
            throw new IllegalStateException("该备份历史没有生成可下载文件。");
        }
        Path path = checkedBackupPath(history.filePath());
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("备份文件不存在，请重新执行备份任务。");
        }
        return path;
    }

    public void deleteHistory(long taskId, long historyId, boolean deleteFile, String actor) throws Exception {
        synchronized (taskLock(taskId)) {
            requireNotRunning(taskId);
            BackupTask task = repository.findById(taskId).orElseThrow(() -> new IllegalArgumentException("Backup task not found: " + taskId));
            BackupHistory history = historyRepository.findByTaskIdAndId(taskId, historyId)
                    .orElseThrow(() -> new IllegalArgumentException("Backup history not found: " + historyId));
            if (restoreJobs != null && restoreJobs.countActiveBySource("HISTORY", historyId) > 0) {
                throw new ApiProblemException(HttpStatus.CONFLICT, "BACKUP_IN_USE_BY_RESTORE", "该备份文件正在用于恢复，暂不能删除。");
            }
            if (deleteFile) {
                deleteHistoryFile(history);
            }
            historyRepository.delete(historyId);
            refreshTaskSummary(taskId);
            audit.log(actor, "BACKUP_HISTORY_DELETE", task.name(), deleteFile ? "deleteFile=true" : "deleteFile=false");
        }
    }

    private void deleteHistoryFile(BackupHistory history) throws Exception {
        if (history.filePath() == null || history.filePath().isBlank()) {
            return;
        }
        Path path = checkedBackupPath(history.filePath());
        Files.deleteIfExists(path);
        Files.deleteIfExists(checkedBackupPath(path + ".log"));
        Files.deleteIfExists(checkedBackupPath(path + ".out"));
    }

    private void refreshTaskSummary(long taskId) {
        List<BackupHistory> histories = historyRepository.findPageByTaskId(taskId, 1, 0);
        if (histories.isEmpty()) {
            repository.updateSummary(taskId, null, null, null, null, null);
            return;
        }
        BackupHistory latest = histories.get(0);
        repository.updateSummary(taskId, latest.status(), latest.message(), latest.filePath(), latest.fileSize(), latest.finishedAt());
    }

    private BackupTask taskFromRequest(long id, BackupTaskRequest request, DbConnection connection, String lastStatus, String lastMessage, String lastFilePath, Long lastFileSize, Instant lastRunAt) {
        List<String> requestedTables = requestedTableNames(request.tableNames(), request.tableName());
        String rawScope = request.scope() == null ? "" : request.scope().toUpperCase(Locale.ROOT);
        String scope = validateScope(request.scope(), requestedTables);
        validateRequestTargetShape(rawScope, request.schemaName(), requestedTables);
        String cron = blankToNull(request.cron());
        validateCron(cron, request.enabled());
        String backupMethod = validateBackupMethod(request.backupMethod(), connection);
        String toolPath = blankToNull(request.toolPath());
        String extraArgs = validateExtraArgs(request.extraArgs());
        String nativeConnectName = blankToNull(request.nativeConnectName());
        Integer retentionDays = validateRetention(request.retentionDays(), "保留天数", 3650);
        Integer retentionCount = validateRetention(request.retentionCount(), "最大保留份数", 10000);
        if (!"SQL".equals(backupMethod)) {
            nativeTools.validateOverrideName(backupTool(backupMethod), toolPath);
        }
        String requestedSchema = Set.of("SCHEMA", "TABLES").contains(scope) ? blankToNull(request.schemaName()) : null;
        ResolvedTarget resolved;
        try {
            resolved = resolveRequestedTarget(connection, scope, requestedSchema, requestedTables);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            throw new IllegalStateException("无法验证备份目标：" + message, e);
        }
        List<String> tableNames = "TABLES".equals(scope)
                ? resolved.tables().stream().map(TableRef::name).toList()
                : List.of();
        String tableName = tableNames.isEmpty() ? null : tableNames.get(0);
        return new BackupTask(
                id, request.name().trim(), request.connectionId(), scope, resolved.namespace(), tableName, tableNames,
                backupMethod, toolPath, extraArgs, nativeConnectName, cron, request.enabled(), lastStatus, lastMessage,
                lastFilePath, lastFileSize, lastRunAt, retentionDays, retentionCount
        );
    }

    private Integer validateRetention(Integer value, String label, int max) {
        if (value == null) return null;
        if (value < 1 || value > max) throw new IllegalArgumentException(label + "必须在 1 到 " + max + " 之间。");
        return value;
    }

    private void validateCron(String cron, boolean enabled) {
        if (cron == null) {
            if (enabled) {
                throw new IllegalArgumentException("启用定时备份需要填写 cron 表达式。");
            }
            return;
        }
        try {
            CronExpression.parse(cron);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("cron 表达式不合法：" + cron);
        }
    }

    private Path checkedBackupPath(String filePath) {
        Path path = Path.of(filePath).toAbsolutePath().normalize();
        Path backupRoot = Path.of(properties.getBackup().getDirectory()).toAbsolutePath().normalize();
        if (!path.startsWith(backupRoot)) {
            throw new IllegalStateException("备份文件路径不在允许目录内。");
        }
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            try {
                Path realRoot = backupRoot.toRealPath();
                Path realPath = path.toRealPath();
                if (!realPath.startsWith(realRoot)) {
                    throw new IllegalStateException("备份文件的真实路径不在允许目录内。");
                }
            } catch (IOException error) {
                throw new IllegalStateException("无法验证备份文件真实路径。", error);
            }
        }
        return path;
    }

    private BackupFile runBackup(BackupTask task, DbConnection connection) throws Exception {
        // Connections are editable after a task is created. Revalidate the
        // saved method against the current dialect before invoking a tool.
        String method = validateBackupMethod(task.backupMethod(), connection);
        return switch (method) {
            case "MYSQLDUMP" -> runMysqlDump(task, connection, resolveTaskTarget(task, connection), nativeTools.resolve(NativeToolLocator.Tool.MYSQLDUMP, task.toolPath()).path().toString());
            case "ORACLE_EXP" -> runOracleExp(task, connection, resolveTaskTarget(task, connection), nativeTools.resolve(NativeToolLocator.Tool.ORACLE_EXP, task.toolPath()).path().toString());
            default -> writeSqlBackup(task, connection);
        };
    }

    private BackupFile runMysqlDump(BackupTask task, DbConnection connection, ResolvedTarget resolved, String toolPath) throws Exception {
        MysqlJdbcTarget target = mysqlTarget(connection.jdbcUrl());
        String database = "DATABASE".equals(resolved.scope()) ? target.database() : resolved.namespace();
        if (database == null || database.isBlank()) {
            throw new IllegalArgumentException("mysqldump 备份需要 JDBC URL 中包含数据库名，或选择一个数据库。");
        }
        Path file = backupPath(task, "mysqldump", ".sql");
        List<String> command = new ArrayList<>();
        command.add(toolPath);
        command.add("--host=" + target.host());
        command.add("--port=" + target.port());
        if (connection.username() != null && !connection.username().isBlank()) {
            command.add("--user=" + connection.username());
        }
        command.add("--result-file=" + file.toAbsolutePath().normalize());
        command.addAll(extraArgs(task.extraArgs()));
        command.add(database);
        if ("TABLES".equals(resolved.scope())) {
            command.addAll(resolved.tables().stream().map(TableRef::name).toList());
        }
        ProcessBuilder builder = new ProcessBuilder(command);
        String password = connections.password(connection.id());
        if (password != null) {
            builder.environment().put("MYSQL_PWD", password);
        }
        runNativeProcess(builder, file, "mysqldump", password);
        return new BackupFile(file, Files.size(file));
    }

    private BackupFile runOracleExp(BackupTask task, DbConnection connection, ResolvedTarget resolved, String toolPath) throws Exception {
        validateOracleNativeTarget(resolved);
        String username = blankToNull(connection.username());
        if (username == null) {
            throw new IllegalArgumentException("Oracle exp 备份需要配置数据库用户名。");
        }
        Path file = backupPath(task, "oracle-exp", ".dmp");
        Path log = file.resolveSibling(file.getFileName() + ".log");
        String password = connections.password(connection.id());
        String connectName = task.nativeConnectName() == null || task.nativeConnectName().isBlank()
                ? oracleConnectName(connection.jdbcUrl())
                : task.nativeConnectName();
        String userid = username + "/" + (password == null ? "" : password) + (connectName == null || connectName.isBlank() ? "" : "@" + connectName);
        validateOracleParameterValue(userid, "Oracle 用户名、密码或连接名");
        validateOracleParameterValue(file.toAbsolutePath().normalize().toString(), "Oracle 备份文件路径");
        validateOracleParameterValue(log.toAbsolutePath().normalize().toString(), "Oracle 日志路径");
        List<String> parameters = new ArrayList<>();
        parameters.add("userid=" + userid);
        parameters.add("file=" + file.toAbsolutePath().normalize());
        parameters.add("log=" + log.toAbsolutePath().normalize());
        if ("TABLES".equals(resolved.scope())) {
            List<String> tables = resolved.tables().stream()
                    .map(table -> resolved.namespace() == null || resolved.namespace().isBlank()
                            ? table.name()
                            : resolved.namespace() + "." + table.name())
                    .toList();
            parameters.add("tables=(" + String.join(",", tables) + ")");
        } else if ("SCHEMA".equals(resolved.scope())) {
            parameters.add("owner=" + resolved.namespace());
        } else {
            parameters.add("full=y");
        }
        Path parameterFile = writeOracleParameterFile(file, parameters);
        List<String> command = new ArrayList<>();
        command.add(toolPath);
        command.add("parfile=" + parameterFile.toAbsolutePath().normalize());
        command.addAll(extraArgs(task.extraArgs()));
        try {
            runNativeProcess(new ProcessBuilder(command), file, "Oracle exp", password);
            return new BackupFile(file, Files.size(file));
        } finally {
            deleteTemporaryFile(parameterFile);
            deleteTemporaryFile(log);
        }
    }

    private Path writeOracleParameterFile(Path outputFile, List<String> parameters) throws Exception {
        Files.createDirectories(outputFile.getParent());
        Set<PosixFilePermission> ownerOnly = Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
        Path parameterFile = Files.getFileStore(outputFile.getParent()).supportsFileAttributeView("posix")
                ? Files.createTempFile(outputFile.getParent(), ".oracle-exp-", ".par", PosixFilePermissions.asFileAttribute(ownerOnly))
                : Files.createTempFile(outputFile.getParent(), ".oracle-exp-", ".par");
        try {
            Files.write(parameterFile, parameters, StandardCharsets.UTF_8);
            return parameterFile;
        } catch (Exception error) {
            deleteTemporaryFile(parameterFile);
            throw error;
        }
    }

    private void validateOracleParameterValue(String value, String label) {
        if (value != null && (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('\0') >= 0)) {
            throw new IllegalArgumentException(label + "不能包含换行符或 NUL 字符。");
        }
    }

    private void runNativeProcess(ProcessBuilder builder, Path outputFile, String label, String secret) throws Exception {
        Files.createDirectories(outputFile.getParent());
        builder.redirectErrorStream(true);
        Process process = null;
        ProcessOutputCollector outputCollector = null;
        boolean success = false;
        try {
            process = builder.start();
            outputCollector = new ProcessOutputCollector(process.getInputStream());
            outputCollector.start(label);
            boolean finished = process.waitFor(Math.max(1, properties.getBackup().getTimeoutSeconds()), TimeUnit.SECONDS);
            if (!finished) {
                throw new IllegalStateException(label + " 执行超时。");
            }
            outputCollector.await(5_000);
            String output = outputCollector.output();
            if (process.exitValue() != 0) {
                throw new IllegalStateException(label + " 执行失败，退出码 " + process.exitValue() + (output.isBlank() ? "" : "：" + scrub(output, secret)));
            }
            if (!Files.exists(outputFile) || !Files.isRegularFile(outputFile)) {
                throw new IllegalStateException(label + " 未生成备份文件。");
            }
            success = true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        } finally {
            terminateProcess(process);
            if (outputCollector != null) outputCollector.await(5_000);
            if (!success) deleteTemporaryFile(outputFile);
        }
    }

    private void terminateProcess(Process process) {
        if (process == null) return;
        process.descendants().forEach(handle -> {
            if (handle.isAlive()) handle.destroyForcibly();
        });
        if (!process.isAlive()) return;
        process.destroyForcibly();
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                log.warn("Native backup process did not exit after forcible termination pid={}", process.pid());
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void deleteTemporaryFile(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (Exception error) {
            log.warn("Unable to delete temporary backup file {}", path, error);
        }
    }

    private Path backupPath(BackupTask task, String prefix, String extension) throws Exception {
        Path dir = Path.of(properties.getBackup().getDirectory());
        Files.createDirectories(dir);
        return dir.resolve(prefix + "-task-" + task.id() + "-" + Instant.now().toEpochMilli() + extension);
    }

    private String validateBackupMethod(String method, DbConnection connection) {
        String normalized = normalizeBackupMethod(method);
        if (!Set.of("SQL", "MYSQLDUMP", "ORACLE_EXP").contains(normalized)) {
            throw new IllegalArgumentException("不支持的备份方式：" + method);
        }
        List<String> supportedNativeMethods = dialectRegistry.dialectFor(connection).capabilities().nativeBackupMethods();
        if ("MYSQLDUMP".equals(normalized) && !supportedNativeMethods.contains("MYSQLDUMP")) {
            throw new IllegalArgumentException("mysqldump 备份仅支持 MySQL/MariaDB 连接。");
        }
        if ("ORACLE_EXP".equals(normalized) && !supportedNativeMethods.contains("ORACLE_EXP")) {
            throw new IllegalArgumentException("Oracle exp 备份仅支持 Oracle 连接。");
        }
        return normalized;
    }

    private String normalizeBackupMethod(String method) {
        return method == null || method.isBlank() ? "SQL" : method.toUpperCase(Locale.ROOT);
    }

    private void requireNotRunning(long taskId) {
        if (coordinator.isRunning(taskId)) {
            throw new ApiProblemException(HttpStatus.CONFLICT, "BACKUP_ALREADY_RUNNING", "备份任务正在执行，暂不能修改或删除。");
        }
    }

    private Object taskLock(long taskId) {
        return taskLocks[Math.floorMod(Long.hashCode(taskId), taskLocks.length)];
    }

    private static Object[] taskLocks() {
        Object[] locks = new Object[64];
        java.util.Arrays.setAll(locks, ignored -> new Object());
        return locks;
    }

    private String methodLabel(String method) {
        return switch (normalizeBackupMethod(method)) {
            case "MYSQLDUMP" -> "mysqldump";
            case "ORACLE_EXP" -> "Oracle exp";
            default -> "SQL";
        };
    }

    private String validateExtraArgs(String extraArgs) {
        List<String> args = extraArgs(extraArgs);
        return args.isEmpty() ? null : String.join("\n", args);
    }

    private NativeToolLocator.Tool backupTool(String backupMethod) {
        return "MYSQLDUMP".equals(backupMethod) ? NativeToolLocator.Tool.MYSQLDUMP : NativeToolLocator.Tool.ORACLE_EXP;
    }

    private List<String> extraArgs(String extraArgs) {
        if (extraArgs == null || extraArgs.isBlank()) {
            return List.of();
        }
        List<String> args = new ArrayList<>();
        for (String raw : extraArgs.split("\\R")) {
            String arg = raw.trim();
            if (arg.isEmpty()) {
                continue;
            }
            if (arg.length() > 2_000) {
                throw new IllegalArgumentException("单个备份额外参数不能超过 2000 个字符。");
            }
            if (args.size() >= 100) {
                throw new IllegalArgumentException("备份额外参数最多填写 100 行。");
            }
            validateExtraArg(arg);
            args.add(arg);
        }
        return args;
    }

    private void validateExtraArg(String arg) {
        if (arg.matches(".*[|&;<>`$].*")) {
            throw new IllegalArgumentException("备份额外参数包含不允许的 shell 控制字符：" + arg);
        }
        String lower = arg.toLowerCase(Locale.ROOT);
        String optionName = lower.contains("=") ? lower.substring(0, lower.indexOf('=')) : lower;
        Set<String> blockedNames = Set.of(
                "--result-file", "--host", "--port", "--user", "--password",
                "--databases", "--all-databases", "--tables",
                "file", "log", "userid", "owner", "tables", "full", "parfile"
        );
        boolean blockedShortOption = arg.startsWith("-r") || arg.startsWith("-h") || arg.startsWith("-P")
                || arg.startsWith("-p") || arg.startsWith("-u");
        if (blockedNames.contains(optionName) || blockedShortOption) {
            throw new IllegalArgumentException("备份额外参数不能覆盖系统控制参数：" + arg);
        }
    }

    private MysqlJdbcTarget mysqlTarget(String jdbcUrl) {
        try {
            String raw = jdbcUrl.replaceFirst("^jdbc:", "");
            URI uri = URI.create(raw);
            String database = uri.getPath() == null ? null : uri.getPath().replaceFirst("^/", "");
            if (database != null && database.contains("/")) {
                database = database.substring(0, database.indexOf('/'));
            }
            return new MysqlJdbcTarget(uri.getHost() == null ? "localhost" : uri.getHost(), uri.getPort() == -1 ? 3306 : uri.getPort(), database == null || database.isBlank() ? null : database);
        } catch (Exception e) {
            throw new IllegalArgumentException("无法解析 MySQL JDBC URL：" + jdbcUrl);
        }
    }

    private String oracleConnectName(String jdbcUrl) {
        String prefix = "jdbc:oracle:thin:@";
        if (jdbcUrl == null || !jdbcUrl.startsWith(prefix)) {
            return null;
        }
        return jdbcUrl.substring(prefix.length());
    }

    private String scrub(String message, String secret) {
        if (secret == null || secret.isBlank()) {
            return message;
        }
        return message.replace(secret, "******");
    }

    private BackupFile writeSqlBackup(BackupTask task, DbConnection dbConnection) throws Exception {
        Path dir = Path.of(properties.getBackup().getDirectory());
        Files.createDirectories(dir);
        Path file = dir.resolve("backup-task-" + task.id() + "-" + Instant.now().toEpochMilli() + ".sql");
        try (Connection connection = connections.open(task.connectionId());
             ReadOnlyQueryScope ignored = ReadOnlyQueryScope.begin(connection, true)) {
            DatabaseDialect dialect = dialectRegistry.dialectFor(dbConnection);
            ResolvedTarget resolved = resolveTarget(connection, dialect, task.scope(), task.schemaName(), task.tableNames());
            if (resolved.tables().isEmpty()) {
                throw new IllegalArgumentException("没有找到可备份的表。");
            }
            try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                writer.write("-- MyDataDev SQL Backup\n");
                writer.write("-- Task: " + sqlCommentValue(task.name()) + "\n");
                writer.write("-- Connection: " + sqlCommentValue(dbConnection.name()) + "\n");
                writer.write("-- Format-Version: 2\n");
                writer.write("-- Source-Db-Type: " + sqlCommentValue(dbConnection.dbType()) + "\n");
                writer.write("-- Generated At: " + Instant.now() + "\n\n");
                for (TableRef table : resolved.tables()) {
                    ensureBackupNotInterrupted();
                    writeCreateTable(connection, writer, table, dialect, dbConnection.dbType());
                }
                for (TableRef table : resolved.tables()) {
                    ensureBackupNotInterrupted();
                    writeTableBackup(connection, writer, table, dialect, dbConnection.dbType());
                }
                Set<String> selectedTables = resolved.tables().stream().map(this::tableKey).collect(java.util.stream.Collectors.toSet());
                for (TableRef table : resolved.tables()) {
                    ensureBackupNotInterrupted();
                    writeTableConstraints(connection, writer, table, dialect, selectedTables);
                }
            }
        } catch (Exception e) {
            Files.deleteIfExists(file);
            throw e;
        }
        return new BackupFile(file, Files.size(file));
    }

    private List<String> requestedTableNames(List<String> tableNames, String legacyTableName) {
        List<String> requested = tableNames == null ? new ArrayList<>() : new ArrayList<>(tableNames);
        String legacy = blankToNull(legacyTableName);
        if (requested.isEmpty() && legacy != null) {
            requested.add(legacy);
        } else if (!requested.isEmpty() && legacy != null) {
            String first = blankToNull(requested.get(0));
            if (!legacy.equals(first)) {
                throw new IllegalArgumentException("tableName 必须与 tableNames 的第一项一致。");
            }
        }
        if (requested.size() > 100) {
            throw new IllegalArgumentException("一次最多选择 100 张表。");
        }
        Set<String> unique = new HashSet<>();
        List<String> normalized = new ArrayList<>(requested.size());
        for (String value : requested) {
            String name = blankToNull(value);
            if (name == null) {
                throw new IllegalArgumentException("表名不能为空。");
            }
            if (!unique.add(name)) {
                throw new IllegalArgumentException("不能重复选择表：" + name);
            }
            normalized.add(name);
        }
        return List.copyOf(normalized);
    }

    private void validateRequestTargetShape(String rawScope, String schemaName, List<String> tableNames) {
        String schema = blankToNull(schemaName);
        switch (rawScope) {
            case "DATABASE" -> {
                if (schema != null) {
                    throw new IllegalArgumentException("全库备份不能指定 Schema/数据库。");
                }
                if (!tableNames.isEmpty()) {
                    throw new IllegalArgumentException("全库备份不能指定表。");
                }
            }
            case "SCHEMA" -> {
                if (schema == null) {
                    throw new IllegalArgumentException("请选择 Schema/数据库。");
                }
                if (!tableNames.isEmpty()) {
                    throw new IllegalArgumentException("Schema/数据库备份不能指定表。");
                }
            }
            case "TABLES" -> {
                if (schema == null) {
                    throw new IllegalArgumentException("请选择 Schema/数据库。");
                }
            }
            case "TABLE" -> {
                // Legacy single-table tasks may omit the namespace; resolve it from the connection.
            }
            default -> {
                // validateScope reports unsupported values.
            }
        }
    }

    private ResolvedTarget resolveRequestedTarget(DbConnection dbConnection, String scope, String schemaName, List<String> tableNames) throws Exception {
        if ("DATABASE".equals(scope)) {
            return new ResolvedTarget(scope, null, List.of());
        }
        try (Connection connection = connections.open(dbConnection.id())) {
            DatabaseDialect dialect = dialectRegistry.dialectFor(dbConnection);
            if ("SCHEMA".equals(scope)) {
                String requestedNamespace = blankToNull(schemaName);
                if (requestedNamespace == null) requestedNamespace = blankToNull(dialect.currentSchema(connection));
                if (requestedNamespace == null) {
                    throw new IllegalArgumentException("无法确定当前 Schema/数据库，请重新选择备份目标。");
                }
                return new ResolvedTarget(scope, resolveNamespace(connection, dialect, requestedNamespace), List.of());
            }
            return resolveTarget(connection, dialect, scope, schemaName, tableNames);
        }
    }

    private ResolvedTarget resolveTaskTarget(BackupTask task, DbConnection dbConnection) throws Exception {
        String scope = normalizeScope(task.scope());
        if ("DATABASE".equals(scope)) {
            return new ResolvedTarget(scope, null, List.of());
        }
        try (Connection connection = connections.open(dbConnection.id())) {
            return resolveTarget(connection, dialectRegistry.dialectFor(dbConnection), task.scope(), task.schemaName(), task.tableNames());
        }
    }

    private ResolvedTarget resolveTarget(Connection connection, DatabaseDialect dialect, String rawScope, String schemaName, List<String> rawTableNames) throws Exception {
        List<String> tableNames = requestedTableNames(rawTableNames, null);
        String scope = validateScope(rawScope, tableNames);
        if ("DATABASE".equals(scope)) {
            if (dialect.namespaceKind() == DatabaseDialect.NamespaceKind.CATALOG) {
                String currentNamespace = blankToNull(dialect.currentSchema(connection));
                if (currentNamespace != null) currentNamespace = resolveNamespace(connection, dialect, currentNamespace);
                return new ResolvedTarget(scope, currentNamespace, physicalTables(connection, dialect, currentNamespace));
            }
            List<TableRef> allTables = new ArrayList<>();
            for (String namespace : availableNamespaces(connection, dialect)) {
                allTables.addAll(physicalTables(connection, dialect, namespace));
            }
            return new ResolvedTarget(scope, null, List.copyOf(allTables));
        }
        String requestedNamespace = blankToNull(schemaName);
        if (requestedNamespace == null) {
            requestedNamespace = blankToNull(dialect.currentSchema(connection));
        }
        if (requestedNamespace == null) {
            throw new IllegalArgumentException("无法确定当前 Schema/数据库，请重新选择备份目标。");
        }
        String namespace = resolveNamespace(connection, dialect, requestedNamespace);
        List<TableRef> availableTables = physicalTables(connection, dialect, namespace);
        if ("SCHEMA".equals(scope)) {
            return new ResolvedTarget(scope, namespace, availableTables);
        }
        Map<String, TableRef> exactTables = new LinkedHashMap<>();
        Map<String, List<TableRef>> foldedTables = new LinkedHashMap<>();
        for (TableRef table : availableTables) {
            exactTables.putIfAbsent(table.name(), table);
            foldedTables.computeIfAbsent(table.name().toLowerCase(Locale.ROOT), ignored -> new ArrayList<>()).add(table);
        }
        List<TableRef> selected = new ArrayList<>(tableNames.size());
        List<String> missing = new ArrayList<>();
        List<String> ambiguous = new ArrayList<>();
        Set<String> selectedActualNames = new HashSet<>();
        for (String tableName : tableNames) {
            TableRef table = exactTables.get(tableName);
            if (table == null) {
                List<TableRef> folded = foldedTables.getOrDefault(tableName.toLowerCase(Locale.ROOT), List.of());
                if (folded.size() == 1) {
                    table = folded.get(0);
                } else if (folded.size() > 1) {
                    ambiguous.add(tableName);
                    continue;
                }
            }
            if (table == null) {
                missing.add(tableName);
            } else if (!selectedActualNames.add(table.name())) {
                throw new IllegalArgumentException("多个目标解析到了同一张表：" + table.name());
            } else {
                selected.add(table);
            }
        }
        if (!ambiguous.isEmpty()) {
            throw new IllegalArgumentException("以下表名大小写不明确，请使用数据库返回的精确名称：" + String.join(", ", ambiguous));
        }
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("以下表不存在或不是物理表：" + String.join(", ", missing));
        }
        return new ResolvedTarget(scope, namespace, List.copyOf(selected));
    }

    private String resolveNamespace(Connection connection, DatabaseDialect dialect, String requestedNamespace) throws Exception {
        List<String> available = availableNamespaces(connection, dialect);
        if (available.contains(requestedNamespace)) {
            return requestedNamespace;
        }
        List<String> folded = available.stream().filter(namespace -> namespace.equalsIgnoreCase(requestedNamespace)).toList();
        if (folded.size() == 1) {
            return folded.get(0);
        }
        if (folded.size() > 1) {
            throw new IllegalArgumentException("Schema/数据库名称大小写不明确，请使用精确名称：" + requestedNamespace);
        }
        throw new IllegalArgumentException("未找到 Schema/数据库：" + requestedNamespace);
    }

    private List<String> availableNamespaces(Connection connection, DatabaseDialect dialect) throws Exception {
        Set<String> namespaces = new java.util.LinkedHashSet<>();
        String current = blankToNull(dialect.currentSchema(connection));
        if (current != null && !isSystemSchema(current)) {
            namespaces.add(current);
        }
        DatabaseMetaData meta = connection.getMetaData();
        try (ResultSet rs = dialect.namespaceKind() == DatabaseDialect.NamespaceKind.CATALOG
                ? meta.getCatalogs()
                : meta.getSchemas()) {
            String column = dialect.namespaceKind() == DatabaseDialect.NamespaceKind.CATALOG ? "TABLE_CAT" : "TABLE_SCHEM";
            while (rs.next()) {
                String namespace = blankToNull(rs.getString(column));
                if (namespace != null && !isSystemSchema(namespace)) {
                    namespaces.add(namespace);
                }
            }
        }
        return namespaces.stream()
                .sorted(String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.naturalOrder()))
                .toList();
    }

    private List<TableRef> physicalTables(Connection connection, DatabaseDialect dialect, String namespace) throws Exception {
        DatabaseMetaData meta = connection.getMetaData();
        DatabaseDialect.MetadataScope metadataScope = dialect.metadataScope(connection, namespace);
        String schemaPattern = metadataScope.schemaPattern() == null ? null : metadataExactPattern(meta, metadataScope.schemaPattern());
        List<TableRef> tables = new ArrayList<>();
        try (ResultSet rs = meta.getTables(metadataScope.catalog(), schemaPattern, "%", new String[]{"TABLE", "BASE TABLE"})) {
            while (rs.next()) {
                String foundNamespace = blankToNull(dialect.resultNamespace(rs));
                if (namespace != null && foundNamespace != null && !namespace.equals(foundNamespace)) {
                    continue;
                }
                String effectiveNamespace = foundNamespace == null ? namespace : foundNamespace;
                String name = blankToNull(rs.getString("TABLE_NAME"));
                String type = blankToNull(rs.getString("TABLE_TYPE"));
                if (name != null && ("TABLE".equalsIgnoreCase(type) || "BASE TABLE".equalsIgnoreCase(type)) && !isSystemSchema(effectiveNamespace)) {
                    tables.add(new TableRef(effectiveNamespace, name));
                }
            }
        }
        tables.sort(Comparator
                .comparing((TableRef table) -> table.schema() == null ? "" : table.schema(), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(table -> table.schema() == null ? "" : table.schema())
                .thenComparing(TableRef::name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(TableRef::name));
        return List.copyOf(tables);
    }

    private String metadataExactPattern(DatabaseMetaData meta, String value) throws Exception {
        String escape = meta.getSearchStringEscape();
        if (escape == null || escape.isEmpty()) return value;
        return value.replace(escape, escape + escape)
                .replace("%", escape + "%")
                .replace("_", escape + "_");
    }

    private void validateOracleNativeTarget(ResolvedTarget target) {
        if ("DATABASE".equals(target.scope())) {
            return;
        }
        validateOracleNativeIdentifier(target.namespace(), "Schema");
        if ("TABLES".equals(target.scope())) {
            for (TableRef table : target.tables()) {
                validateOracleNativeIdentifier(table.name(), "表");
            }
        }
    }

    private void validateOracleNativeIdentifier(String value, String label) {
        if (value == null || !value.matches("[A-Za-z][A-Za-z0-9_$#]*") || !value.equals(value.toUpperCase(Locale.ROOT))) {
            throw new IllegalArgumentException("Oracle exp 暂不支持需要引号或区分大小写的" + label + "标识符：" + value);
        }
    }

    private void writeCreateTable(Connection connection, BufferedWriter writer, TableRef table, DatabaseDialect dialect, String dbType) throws Exception {
        String quoteString = identifierQuote(connection);
        String qualified = table(table.schema(), table.name(), quoteString);
        DatabaseMetaData meta = connection.getMetaData();
        DatabaseDialect.MetadataScope scope = dialect.metadataScope(connection, table.schema());
        String schemaPattern = scope.schemaPattern() == null ? null : metadataExactPattern(meta, scope.schemaPattern());
        List<String> definitions = new ArrayList<>();
        try (ResultSet columns = meta.getColumns(scope.catalog(), schemaPattern, table.name(), "%")) {
            while (columns.next()) {
                String name = columns.getString("COLUMN_NAME");
                String type = columns.getString("TYPE_NAME");
                int size = columns.getInt("COLUMN_SIZE");
                int scale = columns.getInt("DECIMAL_DIGITS");
                boolean nullable = columns.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls;
                String defaultValue = columns.getString("COLUMN_DEF");
                boolean autoIncrement = "YES".equalsIgnoreCase(safeMetadataString(columns, "IS_AUTOINCREMENT"));
                StringBuilder definition = new StringBuilder(quote(name, quoteString)).append(' ').append(columnType(type, size, scale));
                if (defaultValue != null && !defaultValue.isBlank() && !autoIncrement) definition.append(" DEFAULT ").append(defaultValue.trim());
                if (!nullable) definition.append(" NOT NULL");
                if (autoIncrement) definition.append(identityClause(dbType));
                definitions.add(definition.toString());
            }
        }
        List<String> primaryKeys = new ArrayList<>();
        try (ResultSet keys = meta.getPrimaryKeys(scope.catalog(), schemaPattern, table.name())) {
            java.util.TreeMap<Short, String> ordered = new java.util.TreeMap<>();
            while (keys.next()) ordered.put(keys.getShort("KEY_SEQ"), keys.getString("COLUMN_NAME"));
            primaryKeys.addAll(ordered.values());
        }
        if (!primaryKeys.isEmpty()) definitions.add("PRIMARY KEY (" + primaryKeys.stream().map(name -> quote(name, quoteString)).collect(java.util.stream.Collectors.joining(", ")) + ")");
        writer.write("-- Table structure: " + sqlCommentValue(qualified) + "\n");
        writer.write("CREATE TABLE " + qualified + " (\n  " + String.join(",\n  ", definitions) + "\n);\n\n");
    }

    private void writeTableConstraints(Connection connection, BufferedWriter writer, TableRef table, DatabaseDialect dialect, Set<String> selectedTables) throws Exception {
        String quoteString = identifierQuote(connection);
        String qualified = table(table.schema(), table.name(), quoteString);
        DatabaseMetaData meta = connection.getMetaData();
        DatabaseDialect.MetadataScope scope = dialect.metadataScope(connection, table.schema());
        String schemaPattern = scope.schemaPattern() == null ? null : metadataExactPattern(meta, scope.schemaPattern());
        Set<String> primaryIndexNames = new HashSet<>();
        try (ResultSet keys = meta.getPrimaryKeys(scope.catalog(), schemaPattern, table.name())) {
            while (keys.next()) {
                String name = keys.getString("PK_NAME");
                if (name != null) primaryIndexNames.add(name);
            }
        }
        Map<String, IndexBackup> indexes = new LinkedHashMap<>();
        try (ResultSet rows = meta.getIndexInfo(scope.catalog(), schemaPattern, table.name(), false, false)) {
            while (rows.next()) {
                String name = rows.getString("INDEX_NAME");
                String column = rows.getString("COLUMN_NAME");
                if (name == null || column == null || primaryIndexNames.contains(name)) continue;
                IndexBackup index = indexes.get(name);
                if (index == null) {
                    index = new IndexBackup(!rows.getBoolean("NON_UNIQUE"), new java.util.TreeMap<>());
                    indexes.put(name, index);
                }
                index.columns().put(rows.getShort("ORDINAL_POSITION"), column);
            }
        }
        for (Map.Entry<String, IndexBackup> entry : indexes.entrySet()) {
            writer.write("CREATE " + (entry.getValue().unique() ? "UNIQUE " : "") + "INDEX " + quote(entry.getKey(), quoteString) + " ON " + qualified + " ("
                    + entry.getValue().columns().values().stream().map(name -> quote(name, quoteString)).collect(java.util.stream.Collectors.joining(", ")) + ");\n");
        }
        Map<String, ForeignKeyBackup> foreignKeys = new LinkedHashMap<>();
        try (ResultSet rows = meta.getImportedKeys(scope.catalog(), schemaPattern, table.name())) {
            while (rows.next()) {
                String name = rows.getString("FK_NAME");
                String pkSchema = rows.getString("PKTABLE_SCHEM");
                String pkCatalog = rows.getString("PKTABLE_CAT");
                String referencedNamespace = dialect.namespaceKind() == DatabaseDialect.NamespaceKind.CATALOG ? pkCatalog : pkSchema;
                String referencedTable = rows.getString("PKTABLE_NAME");
                if (!selectedTables.contains(tableKey(new TableRef(referencedNamespace, referencedTable)))) continue;
                ForeignKeyBackup key = foreignKeys.computeIfAbsent(name == null ? "fk_" + table.name() + "_" + foreignKeys.size() : name,
                        ignored -> new ForeignKeyBackup(referencedNamespace, referencedTable, new java.util.TreeMap<>(), new java.util.TreeMap<>()));
                short sequence = rows.getShort("KEY_SEQ");
                key.localColumns().put(sequence, rows.getString("FKCOLUMN_NAME"));
                key.referencedColumns().put(sequence, rows.getString("PKCOLUMN_NAME"));
            }
        }
        for (Map.Entry<String, ForeignKeyBackup> entry : foreignKeys.entrySet()) {
            ForeignKeyBackup key = entry.getValue();
            writer.write("ALTER TABLE " + qualified + " ADD CONSTRAINT " + quote(entry.getKey(), quoteString) + " FOREIGN KEY ("
                    + key.localColumns().values().stream().map(name -> quote(name, quoteString)).collect(java.util.stream.Collectors.joining(", ")) + ") REFERENCES "
                    + table(key.referencedNamespace(), key.referencedTable(), quoteString) + " ("
                    + key.referencedColumns().values().stream().map(name -> quote(name, quoteString)).collect(java.util.stream.Collectors.joining(", ")) + ");\n");
        }
        if (!indexes.isEmpty() || !foreignKeys.isEmpty()) writer.write("\n");
    }

    private void writeTableBackup(Connection connection, BufferedWriter writer, TableRef table, DatabaseDialect dialect, String dbType) throws Exception {
        String quoteString = identifierQuote(connection);
        String tableName = table(table.schema(), table.name(), quoteString);
        writer.write("-- Table: " + sqlCommentValue(tableName) + "\n");
        try (Statement statement = connection.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
            dialect.configureStreamingStatement(connection, statement, 500, properties.getBackup().getTimeoutSeconds());
            try (ResultSet rs = statement.executeQuery("SELECT * FROM " + tableName)) {
            ResultSetMetaData md = rs.getMetaData();
            List<String> columns = new ArrayList<>();
            for (int i = 1; i <= md.getColumnCount(); i++) {
                columns.add(md.getColumnLabel(i));
            }
            String columnSql = columns.stream().map(column -> quote(column, quoteString)).reduce((a, b) -> a + ", " + b).orElse("");
            long rows = 0;
            while (rs.next()) {
                ensureBackupNotInterrupted();
                writer.write("INSERT INTO " + tableName + " (" + columnSql + ") VALUES (");
                for (int i = 1; i <= md.getColumnCount(); i++) {
                    if (i > 1) writer.write(", ");
                    writeLiteral(writer, rs.getObject(i), tableName, columns.get(i - 1), dbType);
                }
                writer.write(");\n");
                rows++;
            }
            writer.write("-- Rows: " + rows + "\n\n");
            }
        }
    }

    private void writeLiteral(BufferedWriter writer, Object value, String tableName, String columnName, String dbType) throws Exception {
        if (value instanceof Clob clob) {
            writer.write('\'');
            try (Reader reader = clob.getCharacterStream()) {
                char[] buffer = new char[8_192];
                int read;
                while ((read = reader.read(buffer)) != -1) {
                    for (int index = 0; index < read; index++) {
                        char ch = buffer[index];
                        if (ch == '\'') writer.write("''");
                        else writer.write(ch);
                    }
                }
            }
            writer.write('\'');
            return;
        }
        if (value instanceof Blob blob) {
            try (InputStream input = blob.getBinaryStream()) {
                writeBinary(writer, input.readAllBytes(), dbType);
            }
            return;
        }
        if (value instanceof byte[] bytes) {
            writeBinary(writer, bytes, dbType);
            return;
        }
        writer.write(literal(value, tableName, columnName));
    }

    private void writeBinary(BufferedWriter writer, byte[] bytes, String dbType) throws Exception {
        String hex = HexFormat.of().formatHex(bytes);
        String normalized = dbType == null ? "" : dbType.toLowerCase(Locale.ROOT);
        if (normalized.equals("postgresql")) writer.write("decode('" + hex + "', 'hex')");
        else if (normalized.equals("sqlserver")) writer.write("0x" + hex);
        else if (Set.of("oracle", "dm", "oceanbase-oracle").contains(normalized)) writer.write("hextoraw('" + hex + "')");
        else writer.write("X'" + hex + "'");
    }

    private void ensureBackupNotInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("备份已取消。");
    }

    private String safeMetadataString(ResultSet rows, String column) {
        try { return rows.getString(column); } catch (Exception ignored) { return null; }
    }

    private String columnType(String raw, int size, int scale) {
        String type = raw == null || raw.isBlank() ? "VARCHAR" : raw;
        String upper = type.toUpperCase(Locale.ROOT);
        if (type.contains("(")) return type;
        if ((upper.contains("CHAR") || upper.contains("BINARY")) && size > 0 && size < 1_000_000) return type + "(" + size + ")";
        if ((upper.contains("DECIMAL") || upper.contains("NUMERIC") || upper.equals("NUMBER")) && size > 0) return type + "(" + size + "," + Math.max(0, scale) + ")";
        return type;
    }

    private String identityClause(String dbType) {
        String normalized = dbType == null ? "" : dbType.toLowerCase(Locale.ROOT);
        if (Set.of("mysql", "mariadb", "oceanbase-mysql").contains(normalized)) return " AUTO_INCREMENT";
        if (normalized.equals("sqlserver")) return " IDENTITY(1,1)";
        if (!normalized.equals("sqlite")) return " GENERATED BY DEFAULT AS IDENTITY";
        return "";
    }

    private String tableKey(TableRef table) {
        return (table.schema() == null ? "" : table.schema().toLowerCase(Locale.ROOT)) + "\u0000" + table.name().toLowerCase(Locale.ROOT);
    }

    private String validateScope(String scope, List<String> tableNames) {
        String normalized = normalizeScope(scope);
        if (!Set.of("DATABASE", "SCHEMA", "TABLES").contains(normalized)) {
            throw new IllegalArgumentException("不支持的备份范围：" + scope);
        }
        if ("TABLES".equals(normalized) && tableNames.isEmpty()) {
            throw new IllegalArgumentException("表备份至少需要选择一张表。");
        }
        return normalized;
    }

    private String normalizeScope(String scope) {
        String normalized = scope == null ? "" : scope.toUpperCase(Locale.ROOT);
        return "TABLE".equals(normalized) ? "TABLES" : normalized;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private boolean isSystemSchema(String schema) {
        if (schema == null) {
            return false;
        }
        String s = schema.toUpperCase(Locale.ROOT);
        return s.equals("INFORMATION_SCHEMA")
                || s.equals("SYS")
                || s.equals("SYSTEM")
                || s.equals("PG_CATALOG")
                || s.equals("SQLJ")
                || s.startsWith("SYS_")
                || s.equals("MYSQL")
                || s.startsWith("PG_TOAST")
                || s.startsWith("PG_TEMP_");
    }

    private String table(String schema, String table, String quoteString) {
        return schema == null || schema.isBlank()
                ? quote(table, quoteString)
                : quote(schema, quoteString) + "." + quote(table, quoteString);
    }

    private String quote(String identifier, String quoteString) {
        if (quoteString == null || quoteString.isBlank()) {
            return identifier;
        }
        return quoteString + identifier.replace(quoteString, quoteString + quoteString) + quoteString;
    }

    private String identifierQuote(Connection connection) throws Exception {
        String quote = connection.getMetaData().getIdentifierQuoteString();
        return quote == null || quote.isBlank() ? "" : quote.trim();
    }

    private String literal(Object value, String tableName, String columnName) throws Exception {
        if (value == null) {
            return "NULL";
        }
        if (value instanceof Clob clob) {
            return quoteLiteral(readClob(clob));
        }
        if (value instanceof Blob blob) {
            throw unsupportedBinary(tableName, columnName, "BLOB " + blob.length() + " bytes");
        }
        if (value instanceof byte[] bytes) {
            throw unsupportedBinary(tableName, columnName, "BINARY " + bytes.length + " bytes");
        }
        if (value instanceof Number) {
            return value.toString();
        }
        if (value instanceof Boolean) {
            return Boolean.TRUE.equals(value) ? "TRUE" : "FALSE";
        }
        if (value instanceof java.sql.Date date) {
            return quoteLiteral(date.toLocalDate().toString());
        }
        if (value instanceof java.sql.Time time) {
            return quoteLiteral(time.toLocalTime().toString());
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return quoteLiteral(timestamp.toLocalDateTime().toString().replace('T', ' '));
        }
        if (value instanceof LocalDate || value instanceof LocalTime || value instanceof LocalDateTime || value instanceof OffsetDateTime || value instanceof ZonedDateTime || value instanceof Instant) {
            return quoteLiteral(value.toString());
        }
        return quoteLiteral(value.toString());
    }

    private String readClob(Clob clob) throws Exception {
        StringBuilder builder = new StringBuilder();
        try (Reader reader = clob.getCharacterStream()) {
            char[] buffer = new char[8192];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                builder.append(buffer, 0, read);
            }
        }
        return builder.toString();
    }

    private IllegalArgumentException unsupportedBinary(String tableName, String columnName, String detail) {
        return new IllegalArgumentException("SQL 备份暂不支持二进制字段：" + tableName + "." + quoteForMessage(columnName) + " (" + detail + ")");
    }

    private String quoteForMessage(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private String quoteLiteral(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    private String sqlCommentValue(String value) {
        return value == null ? "" : value.replace('\r', ' ').replace('\n', ' ').replace('\0', ' ');
    }

    private static final class ProcessOutputCollector {
        private static final int MAX_CAPTURE_BYTES = 4_000;
        private final InputStream input;
        private final ByteArrayOutputStream captured = new ByteArrayOutputStream(MAX_CAPTURE_BYTES);
        private Thread thread;

        private ProcessOutputCollector(InputStream input) {
            this.input = input;
        }

        private void start(String label) {
            thread = new Thread(this::drain, "dbadmin-native-output-" + label.replaceAll("[^A-Za-z0-9_-]", "-"));
            thread.setDaemon(true);
            thread.start();
        }

        private void drain() {
            try (input) {
                byte[] buffer = new byte[8_192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    synchronized (captured) {
                        int retained = Math.min(read, MAX_CAPTURE_BYTES - captured.size());
                        if (retained > 0) captured.write(buffer, 0, retained);
                    }
                }
            } catch (IOException ignored) {
                // Process termination can close the pipe while the collector
                // is blocked. The retained prefix remains useful for errors.
            }
        }

        private void await(long timeoutMillis) {
            Thread running = thread;
            if (running == null) return;
            try {
                running.join(timeoutMillis);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        private String output() {
            synchronized (captured) {
                return captured.toString(StandardCharsets.UTF_8);
            }
        }
    }

    private record TableRef(String schema, String name) {
    }

    private record ResolvedTarget(String scope, String namespace, List<TableRef> tables) {
    }

    private record BackupFile(Path path, long size) {
    }

    private record IndexBackup(boolean unique, java.util.TreeMap<Short, String> columns) {
    }

    private record ForeignKeyBackup(String referencedNamespace, String referencedTable,
                                    java.util.TreeMap<Short, String> localColumns,
                                    java.util.TreeMap<Short, String> referencedColumns) {
    }

    private record MysqlJdbcTarget(String host, int port, String database) {
    }
}
