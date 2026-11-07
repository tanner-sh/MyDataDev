package com.example.dbadmin.service;

import com.example.dbadmin.config.AppProperties;
import com.example.dbadmin.core.DialectRegistry;
import com.example.dbadmin.dto.ApiDtos.BackupTaskRequest;
import com.example.dbadmin.dto.ApiDtos.CronPreviewResponse;
import com.example.dbadmin.dto.ApiDtos.BackupHistoryPage;
import com.example.dbadmin.dto.ApiDtos.BackupRunResponse;
import com.example.dbadmin.dto.ApiDtos.BackupTaskPage;
import com.example.dbadmin.api.ApiProblemException;
import com.example.dbadmin.model.CronSchedules;
import com.example.dbadmin.model.BackupHistory;
import com.example.dbadmin.model.BackupTask;
import com.example.dbadmin.model.DbConnection;
import com.example.dbadmin.model.StorageProfile;
import com.example.dbadmin.repo.AuditRepository;
import com.example.dbadmin.repo.BackupHistoryRepository;
import com.example.dbadmin.repo.BackupTaskRepository;
import com.example.dbadmin.repo.RestoreJobRepository;
import com.example.dbadmin.repo.StorageProfileRepository;
import com.example.dbadmin.storage.BackupStorageRegistry;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.annotation.PostConstruct;
import com.example.dbadmin.service.BackupTargets.ResolvedTarget;
import com.example.dbadmin.service.BackupTargets.TableRef;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;

import static com.example.dbadmin.service.BackupTargets.blankToNull;
import static com.example.dbadmin.service.BackupTargets.requestedTableNames;
import static com.example.dbadmin.service.BackupTargets.validateRequestTargetShape;
import static com.example.dbadmin.service.BackupTargets.validateScope;

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
    private final BackgroundTaskControl taskControl;
    private final StorageProfileRepository storageProfiles;
    private final BackupStorageRegistry storage;
    private final Object[] taskLocks = taskLocks();
    private final BackupTargets targets;
    private final NativeBackupRunner nativeRunner;
    private final SqlBackupWriter sqlWriter;

    @Autowired
    public BackupService(BackupTaskRepository repository, BackupHistoryRepository historyRepository, ConnectionService connections, AuditRepository audit, AppProperties properties, DialectRegistry dialectRegistry, BackupExecutionCoordinator coordinator, RestoreJobRepository restoreJobs, NativeToolLocator nativeTools, BackgroundTaskControl taskControl, StorageProfileRepository storageProfiles, BackupStorageRegistry storage) {
        this.repository = repository;
        this.historyRepository = historyRepository;
        this.connections = connections;
        this.audit = audit;
        this.properties = properties;
        this.dialectRegistry = dialectRegistry;
        this.coordinator = coordinator;
        this.restoreJobs = restoreJobs;
        this.nativeTools = nativeTools;
        this.taskControl = taskControl;
        this.storageProfiles = storageProfiles;
        this.storage = storage;
        this.targets = new BackupTargets(connections, dialectRegistry);
        this.nativeRunner = new NativeBackupRunner(connections, properties, nativeTools, targets);
        this.sqlWriter = new SqlBackupWriter(connections, dialectRegistry, properties, targets);
    }

    @PostConstruct
    public void recoverInterruptedTasks() {
        repository.failStaleRunningTasks();
    }

    public List<BackupTask> list() {
        return enrichTasks(repository.findAll());
    }

    public List<BackupTask> list(Long connectionId) {
        return enrichTasks(connectionId == null ? repository.findAll() : repository.findByConnectionId(connectionId));
    }

    public BackupTaskPage page(long connectionId, String keyword, String status, Integer page, Integer pageSize) {
        int safePage = Math.max(page == null ? 0 : page, 0);
        int size = Math.min(Math.max(pageSize == null ? 10 : pageSize, 1), 100);
        long offset = (long) safePage * size;
        if (offset > 1_000_000) throw new IllegalArgumentException("备份任务分页偏移过大，请缩小页码。");
        List<BackupTask> rows = repository.findPage(connectionId, keyword, status, size + 1, offset);
        boolean hasMore = rows.size() > size;
        if (hasMore) rows = rows.subList(0, size);
        return new BackupTaskPage(enrichTasks(rows), safePage, size, hasMore);
    }

    public BackupTask create(BackupTaskRequest request, String actor) {
        DbConnection connection = connections.require(request.connectionId());
        BackupTask task = taskFromRequest(0, request, connection, null, null, null, null, null);
        long id = repository.insert(task);
        audit.onConnection(actor, "BACKUP_TASK_CREATE", task.connectionId(), "backup:" + request.name(), request.scope());
        return repository.findById(id).orElseThrow();
    }

    public BackupTask update(long id, BackupTaskRequest request, String actor) {
        synchronized (taskLock(id)) {
            requireNotRunning(id);
            repository.findById(id).orElseThrow(() -> new IllegalArgumentException("Backup task not found: " + id));
            DbConnection connection = connections.require(request.connectionId());
            BackupTask task = taskFromRequest(id, request, connection, null, null, null, null, null);
            repository.update(id, task);
            audit.onConnection(actor, "BACKUP_TASK_UPDATE", task.connectionId(), "backup:" + request.name(), request.scope());
            return repository.findById(id).orElseThrow();
        }
    }

    public BackupTask setEnabled(long id, boolean enabled, String actor) {
        synchronized (taskLock(id)) {
            BackupTask task = repository.findById(id).orElseThrow(() -> new IllegalArgumentException("Backup task not found: " + id));
            if (enabled) {
                validateCron(task.cron(), true);
                if (task.storageProfileId() != null) {
                    StorageProfile profile = storageProfiles.findById(task.storageProfileId())
                            .orElseThrow(() -> new IllegalArgumentException("文件服务配置不存在：" + task.storageProfileId()));
                    if (!profile.enabled()) throw new ApiProblemException(HttpStatus.CONFLICT, "STORAGE_PROFILE_DISABLED", "文件服务已停用，不能启用该执行计划。");
                }
            }
            repository.updateEnabled(id, enabled);
            audit.onConnection(actor, enabled ? "BACKUP_TASK_ENABLE" : "BACKUP_TASK_DISABLE", task.connectionId(), "backup:" + task.name(), task.cron());
            return repository.findById(id).orElseThrow();
        }
    }

    public CronPreviewResponse previewSchedule(String cronValue, String zoneValue) {
        String cron = blankToNull(cronValue);
        if (cron == null) {
            throw new IllegalArgumentException("cron 表达式不能为空。");
        }
        CronExpression expression = CronSchedules.requireValidCron(cron);
        ZoneId zone = CronSchedules.zoneOrDefault(CronSchedules.normalizeZone(zoneValue));
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
            if (restoreJobs.countActiveByBackupTaskId(id) > 0) {
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
            audit.onConnection(actor, "BACKUP_TASK_DELETE", task.connectionId(), "backup:" + task.name(), deleteFile ? "deleteFile=true" : "deleteFile=false");
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
        StorageProfile storageProfile = null;
        try {
            if (task.storageProfileId() != null) {
                storageProfile = storageProfiles.findById(task.storageProfileId())
                        .orElseThrow(() -> new IllegalArgumentException("文件服务配置不存在：" + task.storageProfileId()));
                if (!storageProfile.enabled()) throw new IllegalStateException("文件服务已停用，备份任务未执行。");
            }
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
            audit.onConnection(actor, cancelled ? "BACKUP_TASK_CANCELLED" : "BACKUP_TASK_RUN_FAILED", task.connectionId(), "backup:" + task.name(), message);
            throw e;
        }
        String message = methodLabel(task.backupMethod()) + " 备份已生成：" + backup.path().getFileName();
        String filePath = backup.path().toAbsolutePath().normalize().toString();
        String checksum = FileIntegrity.sha256(backup.path());
        if (storageProfile != null) {
            String objectKey = remoteObjectKey(task, backup.path());
            Instant expiresAt = Instant.now().plus(Math.max(1, properties.getBackup().getFailedUploadRetentionDays()), java.time.temporal.ChronoUnit.DAYS);
            long[] lastProgressNanos = {0};
            boolean uploadCompleted = false;
            try {
                if (executionId != null) {
                    historyRepository.updateProgress(executionId, "RUNNING", "UPLOADING", 0, backup.size(), "备份已生成，正在上传文件服务。");
                }
                StorageProfile selectedStorage = storageProfile;
                storage.upload(selectedStorage, backup.path(), objectKey, transferred -> {
                    if (executionId != null && progressDue(lastProgressNanos)) {
                        historyRepository.updateProgress(executionId, "RUNNING", "UPLOADING", transferred, backup.size(), "正在上传文件服务。");
                    }
                });
                uploadCompleted = true;
                if (Thread.currentThread().isInterrupted()
                        || executionId != null && historyRepository.findById(executionId).map(BackupHistory::cancelRequested).orElse(false)) {
                    throw new InterruptedException("备份已取消。");
                }
                long remoteSize = storage.size(storageProfile, objectKey);
                if (remoteSize != backup.size()) throw new IllegalStateException("远端文件大小校验失败。");
                message = methodLabel(task.backupMethod()) + " 备份已上传至文件服务：" + storageProfile.name();
                if (executionId == null) {
                    recordHistory(new BackupHistory(0, id, connection.id(), "SUCCESS", message, null, backup.size(), startedAt, Instant.now(),
                            fileFormat(task.backupMethod()), normalizeBackupMethod(task.backupMethod()), connection.dbType(), checksum,
                            "COMPLETED", backup.size(), backup.size(), false, storageProfile.type(), storageProfile.id(),
                            storageProfile.name(), objectKey, null));
                } else {
                    boolean completed = historyRepository.updateExecution(executionId, "SUCCESS", "COMPLETED", backup.size(), backup.size(), message,
                            null, backup.size(), checksum, Instant.now(), storageProfile.type(), storageProfile.id(), objectKey, null);
                    if (!completed) throw new InterruptedException("备份已取消。");
                }
                repository.updateStatus(id, "SUCCESS", message, null, backup.size(), storageProfile.type(), storageProfile.id(), objectKey);
                Files.deleteIfExists(backup.path());
            } catch (Exception uploadError) {
                boolean cancelled = uploadError instanceof InterruptedException || Thread.currentThread().isInterrupted()
                        || executionId != null && historyRepository.findById(executionId).map(BackupHistory::cancelRequested).orElse(false);
                if (cancelled) {
                    if (uploadCompleted) {
                        try { storage.delete(storageProfile, objectKey); }
                        catch (Exception cleanupError) { log.warn("Unable to remove cancelled remote backup task={} key={}", id, objectKey, cleanupError); }
                    }
                    Files.deleteIfExists(backup.path());
                    if (executionId == null) {
                        recordHistory(new BackupHistory(0, id, connection.id(), "CANCELLED", "备份已取消。", null, null, startedAt, Instant.now(),
                                fileFormat(task.backupMethod()), normalizeBackupMethod(task.backupMethod()), connection.dbType(), null,
                                "CANCELLED", 0L, backup.size(), true));
                    } else {
                        historyRepository.updateExecution(executionId, "CANCELLED", "CANCELLED", 0, backup.size(), "备份已取消。",
                                null, null, null, Instant.now());
                    }
                    repository.updateStatus(id, "CANCELLED", "备份已取消。");
                    audit.onConnection(actor, "BACKUP_TASK_CANCELLED", task.connectionId(), "backup:" + task.name(), "备份已取消。");
                    throw uploadError;
                }
                String uploadMessage = "备份已生成，但上传文件服务失败：" + safeMessage(uploadError);
                if (executionId == null) {
                    recordHistory(new BackupHistory(0, id, connection.id(), "FAILED", uploadMessage, filePath, backup.size(), startedAt, Instant.now(),
                            fileFormat(task.backupMethod()), normalizeBackupMethod(task.backupMethod()), connection.dbType(), checksum,
                            "UPLOAD_FAILED", 0L, backup.size(), false, storageProfile.type(), storageProfile.id(),
                            storageProfile.name(), objectKey, expiresAt));
                } else {
                    historyRepository.updateExecution(executionId, "FAILED", "UPLOAD_FAILED", 0, backup.size(), uploadMessage,
                            filePath, backup.size(), checksum, Instant.now(), storageProfile.type(), storageProfile.id(), objectKey, expiresAt);
                }
                repository.updateStatus(id, "FAILED", uploadMessage);
                audit.onConnection(actor, "BACKUP_UPLOAD_FAILED", task.connectionId(), "backup:" + task.name(), uploadMessage);
                throw new IllegalStateException(uploadMessage, uploadError);
            }
            audit.onConnection(actor, "BACKUP_TASK_RUN", task.connectionId(), "backup:" + task.name(), message);
            cleanupRetention(task);
            return repository.findById(id).orElseThrow();
        }
        if (executionId == null) {
            recordHistory(new BackupHistory(0, id, connection.id(), "SUCCESS", message, filePath, backup.size(), startedAt, Instant.now(),
                    fileFormat(task.backupMethod()), normalizeBackupMethod(task.backupMethod()), connection.dbType(), checksum,
                    "COMPLETED", 1L, 1L, false));
        } else {
            boolean completed = historyRepository.updateExecution(executionId, "SUCCESS", "COMPLETED", 1, 1L, message,
                    filePath, backup.size(), checksum, Instant.now());
            if (!completed) {
                Files.deleteIfExists(backup.path());
                historyRepository.updateExecution(executionId, "CANCELLED", "CANCELLED", 0, 1L,
                        "备份已取消。", null, null, null, Instant.now());
                repository.updateStatus(id, "CANCELLED", "备份已取消。");
                throw new InterruptedException("备份已取消。");
            }
        }
        updateStatus(id, "SUCCESS", message, filePath, backup.size());
        audit.onConnection(actor, "BACKUP_TASK_RUN", task.connectionId(), "backup:" + task.name(), message);
        cleanupRetention(task);
        return repository.findById(id).orElseThrow();
    }

    private String remoteObjectKey(BackupTask task, Path file) {
        java.time.LocalDate today = java.time.LocalDate.now();
        return "connection-" + task.connectionId() + "/task-" + task.id() + "/"
                + today.getYear() + "/" + String.format("%02d", today.getMonthValue()) + "/"
                + String.format("%02d", today.getDayOfMonth()) + "/" + file.getFileName();
    }

    private String safeMessage(Exception error) {
        return error.getMessage() == null || error.getMessage().isBlank() ? error.getClass().getSimpleName() : error.getMessage();
    }

    private String fileFormat(String method) {
        return switch (normalizeBackupMethod(method)) {
            case "MYSQLDUMP" -> "MYSQLDUMP";
            case "PG_DUMP" -> "PG_DUMP";
            case "ORACLE_EXP" -> "ORACLE_DMP";
            default -> "SQL";
        };
    }

    private void cleanupRetention(BackupTask task) {
        if (task.retentionDays() == null && task.retentionCount() == null) return;
        try {
            Instant cutoff = task.retentionDays() == null ? null : Instant.now().minus(task.retentionDays(), java.time.temporal.ChronoUnit.DAYS);
            while (true) {
                List<BackupHistory> candidates = historyRepository.findRetentionCandidates(task.id(), task.retentionCount(), cutoff, 200);
                for (BackupHistory history : candidates) {
                    deleteHistoryFile(history);
                    historyRepository.delete(history.id());
                }
                if (candidates.size() < 200) break;
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
            String operationKey = backupOperationKey(id);
            if (!taskControl.tryAcquire(connectionId, operationKey)) {
                throw new ApiProblemException(HttpStatus.CONFLICT, "CONNECTION_BACKGROUND_BUSY", "该连接已有后台重任务正在执行，请等待完成后重试。");
            }
            String sourceDbType = connection == null ? null : connection.dbType();
            long executionId = 0;
            try {
                executionId = historyRepository.insert(new BackupHistory(0, id, connectionId, "QUEUED", "备份任务已进入后台执行队列。",
                        null, null, Instant.now(), null, fileFormat(task.backupMethod()), normalizeBackupMethod(task.backupMethod()),
                        sourceDbType, null, "QUEUED", 0L, 1L, false));
                long queuedExecutionId = executionId;
                boolean accepted = coordinator.submit(
                        id,
                        () -> repository.updateStatus(id, "RUNNING", "备份任务已进入后台执行队列。"),
                        () -> {
                            try {
                                runInternal(id, actor, queuedExecutionId);
                            } catch (Exception ignored) {
                                // run records the failed history and task status.
                            }
                        },
                        () -> taskControl.releaseCompleted(connectionId, operationKey)
                );
                if (!accepted) {
                    // The task is already running; that run owns the permit and
                    // must keep it, so nothing is released here.
                    throw new ApiProblemException(HttpStatus.CONFLICT, "BACKUP_ALREADY_RUNNING", "该备份任务正在执行，请勿重复启动。");
                }
            } catch (RejectedExecutionException e) {
                taskControl.releaseCompleted(connectionId, operationKey);
                repository.updateStatus(id, "FAILED", "备份执行队列已满，任务未启动。");
                if (executionId > 0) {
                    historyRepository.updateExecution(executionId, "FAILED", "FAILED", 0, 1L, "备份执行队列已满，任务未启动。", null, null, null, Instant.now());
                }
                throw new ApiProblemException(HttpStatus.TOO_MANY_REQUESTS, "BACKUP_QUEUE_FULL", "备份执行队列已满，请稍后重试。");
            } catch (RuntimeException error) {
                taskControl.releaseCompleted(connectionId, operationKey);
                throw error;
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
            taskControl.requestCancel(backupOperationKey(taskId));
            coordinator.cancel(taskId);
            // The permit stays with the coordinator wrapper until the worker
            // has either stopped or skipped this queued task.
            boolean uploadRetry = Set.of("UPLOAD_RETRY_QUEUED", "UPLOADING").contains(history.phase()) && history.filePath() != null;
            if (uploadRetry) {
                historyRepository.updateExecution(historyId, "FAILED", "UPLOAD_FAILED", value(history.progressCurrent()), history.fileSize(),
                        "重新上传已取消，本地暂存文件仍保留。", history.filePath(), history.fileSize(), history.checksumSha256(),
                        history.finishedAt(), history.storageType(), history.storageProfileId(), history.storageObjectKey(), history.stagingExpiresAt());
                refreshTaskSummary(taskId);
            } else {
                historyRepository.updateExecution(historyId, "CANCELLED", "CANCELLED", value(history.progressCurrent()), history.progressTotal(), "备份已取消。", null, null, null, Instant.now());
                repository.updateStatus(taskId, "CANCELLED", "备份已取消。");
            }
            audit.onConnection(actor, "BACKUP_TASK_CANCEL", history.connectionId(), "backup-task:" + taskId, "history=" + historyId);
            return historyRepository.findById(historyId).orElseThrow();
        }
    }

    public BackupHistory retryUpload(long taskId, long historyId, String actor) {
        synchronized (taskLock(taskId)) {
            BackupTask task = repository.findById(taskId).orElseThrow(() -> new IllegalArgumentException("Backup task not found: " + taskId));
            BackupHistory history = historyRepository.findByTaskIdAndId(taskId, historyId)
                    .orElseThrow(() -> new IllegalArgumentException("Backup history not found: " + historyId));
            if (!"UPLOAD_FAILED".equals(history.phase()) || history.filePath() == null || history.storageProfileId() == null || history.storageObjectKey() == null) {
                throw new ApiProblemException(HttpStatus.CONFLICT, "BACKUP_UPLOAD_NOT_RETRYABLE", "该历史记录没有可重试的上传文件。");
            }
            if (history.stagingExpiresAt() != null && history.stagingExpiresAt().isBefore(Instant.now())) {
                throw new ApiProblemException(HttpStatus.GONE, "BACKUP_STAGING_EXPIRED", "本地暂存文件已过期，无法重新上传。");
            }
            Path path = checkedBackupPath(history.filePath());
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                throw new ApiProblemException(HttpStatus.GONE, "BACKUP_STAGING_EXPIRED", "本地暂存文件不存在，无法重新上传。");
            }
            StorageProfile profile = storageProfiles.findById(history.storageProfileId())
                    .orElseThrow(() -> new IllegalStateException("备份使用的文件服务配置已不存在。"));
            if (!profile.enabled()) throw new ApiProblemException(HttpStatus.CONFLICT, "STORAGE_PROFILE_DISABLED", "文件服务已停用，不能重新上传。");
            if (coordinator.isRunning(taskId)) throw new ApiProblemException(HttpStatus.CONFLICT, "BACKUP_ALREADY_RUNNING", "该备份任务正在执行，请稍后重试。");
            if (!historyRepository.queueUploadRetry(historyId, value(history.fileSize()), "重新上传已进入后台队列。")) {
                throw new ApiProblemException(HttpStatus.CONFLICT, "BACKUP_UPLOAD_NOT_RETRYABLE", "该历史记录状态已变化，请刷新后重试。");
            }
            try {
                boolean accepted = coordinator.submit(taskId, () -> { }, () -> retryUploadInternal(task, history, profile, path, actor));
                if (!accepted) throw new ApiProblemException(HttpStatus.CONFLICT, "BACKUP_ALREADY_RUNNING", "该备份任务正在执行，请稍后重试。");
            } catch (RejectedExecutionException error) {
                historyRepository.updateProgress(historyId, "FAILED", "UPLOAD_FAILED", 0, history.fileSize(), "后台队列已满，请稍后重试。");
                throw new ApiProblemException(HttpStatus.TOO_MANY_REQUESTS, "BACKUP_QUEUE_FULL", "备份执行队列已满，请稍后重试。");
            }
            audit.onConnection(actor, "BACKUP_UPLOAD_RETRY", task.connectionId(), "backup:" + task.name(), "history=" + historyId);
            return historyRepository.findById(historyId).orElseThrow();
        }
    }

    private void retryUploadInternal(BackupTask task, BackupHistory history, StorageProfile profile, Path path, String actor) {
        long[] lastProgressNanos = {0};
        try {
            if (history.checksumSha256() != null && !history.checksumSha256().equalsIgnoreCase(FileIntegrity.sha256(path))) {
                throw new IllegalStateException("本地暂存文件校验失败，内容已发生变化。");
            }
            historyRepository.updateProgress(history.id(), "RUNNING", "UPLOADING", 0, history.fileSize(), "正在重新上传文件服务。");
            storage.upload(profile, path, history.storageObjectKey(), transferred -> {
                if (progressDue(lastProgressNanos)) historyRepository.updateProgress(history.id(), "RUNNING", "UPLOADING", transferred, history.fileSize(), "正在重新上传文件服务。");
            });
            long size = storage.size(profile, history.storageObjectKey());
            if (history.fileSize() != null && size != history.fileSize()) throw new IllegalStateException("远端文件大小校验失败。");
            String message = "备份已重新上传至文件服务：" + profile.name();
            boolean completed = historyRepository.updateExecution(history.id(), "SUCCESS", "COMPLETED", size, size, message, null, size,
                    history.checksumSha256(), history.finishedAt(), profile.type(), profile.id(), history.storageObjectKey(), null);
            if (!completed) {
                try { storage.delete(profile, history.storageObjectKey()); }
                catch (Exception cleanupError) { log.warn("Unable to remove cancelled retry upload history={}", history.id(), cleanupError); }
                return;
            }
            Files.deleteIfExists(path);
            refreshTaskSummary(task.id());
            audit.onConnection(actor, "BACKUP_UPLOAD_RETRY_SUCCESS", task.connectionId(), "backup:" + task.name(), "history=" + history.id());
        } catch (Exception error) {
            String message = "重新上传失败：" + safeMessage(error);
            historyRepository.updateExecution(history.id(), "FAILED", "UPLOAD_FAILED", 0, history.fileSize(), message,
                    path.toString(), history.fileSize(), history.checksumSha256(), history.finishedAt(), profile.type(),
                    profile.id(), history.storageObjectKey(), history.stagingExpiresAt());
            refreshTaskSummary(task.id());
            audit.onConnection(actor, "BACKUP_UPLOAD_RETRY_FAILED", task.connectionId(), "backup:" + task.name(), message);
        }
    }

    @Scheduled(cron = "0 41 3 * * *")
    public void cleanupExpiredUploadStaging() {
        List<BackupHistory> expired = historyRepository.findExpiredStaging(Instant.now(), 500);
        for (BackupHistory history : expired) {
            try {
                Files.deleteIfExists(checkedBackupPath(history.filePath()));
                historyRepository.markStagingExpired(history.id(), "上传失败的本地暂存文件已超过保留期限并被清理。");
                refreshTaskSummary(history.taskId());
            } catch (Exception error) {
                log.warn("Unable to clean expired upload staging history={}", history.id(), error);
            }
        }
    }

    public BackupHistoryPage historyByConnection(long connectionId, Integer page, Integer pageSize) {
        int safePage = Math.max(page == null ? 0 : page, 0);
        int safePageSize = Math.min(Math.max(pageSize == null ? 20 : pageSize, 1), 100);
        long offset = (long) safePage * safePageSize;
        if (offset > 1_000_000) throw new IllegalArgumentException("备份历史分页偏移过大，请缩小页码。");
        List<BackupHistory> rows = historyRepository.findPageByConnectionId(connectionId, safePageSize + 1, offset);
        boolean hasMore = rows.size() > safePageSize;
        if (hasMore) rows = rows.subList(0, safePageSize);
        return new BackupHistoryPage(enrichHistories(rows), safePage, safePageSize, hasMore);
    }

    private long value(Long value) {
        return value == null ? 0 : value;
    }

    private boolean progressDue(long[] lastProgressNanos) {
        long now = System.nanoTime();
        long interval = Math.max(100, properties.getBackgroundTasks().getProgressIntervalMs()) * 1_000_000L;
        if (lastProgressNanos[0] != 0 && now - lastProgressNanos[0] < interval) return false;
        lastProgressNanos[0] = now;
        return true;
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

    public DownloadInfo backupDownloadInfo(long id) {
        BackupTask task = repository.findById(id).orElseThrow(() -> new IllegalArgumentException("Backup task not found: " + id));
        if (!task.lastFileAvailable()) throw new IllegalStateException("该备份任务还没有生成可下载文件。");
        if (task.lastStorageProfileId() != null && task.lastStorageObjectKey() != null) {
            return new DownloadInfo(fileName(task.lastStorageObjectKey()), task.lastFileSize() == null ? -1 : task.lastFileSize());
        }
        Path path = backupFile(id);
        return new DownloadInfo(path.getFileName().toString(), task.lastFileSize() == null ? -1 : task.lastFileSize());
    }

    public void writeBackupFile(long id, OutputStream output) throws Exception {
        BackupTask task = repository.findById(id).orElseThrow(() -> new IllegalArgumentException("Backup task not found: " + id));
        if (task.lastStorageProfileId() != null && task.lastStorageObjectKey() != null && "SUCCESS".equals(task.lastStatus())) {
            StorageProfile profile = storageProfiles.findById(task.lastStorageProfileId())
                    .orElseThrow(() -> new IllegalStateException("备份使用的文件服务配置已不存在。"));
            storage.download(profile, task.lastStorageObjectKey(), output, ignored -> { });
            return;
        }
        Files.copy(backupFile(id), output);
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
        return new BackupHistoryPage(enrichHistories(rows), safePage, safePageSize, hasMore);
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

    /**
     * 校验一份备份：把它完整读一遍，比对大小与 SHA-256。
     *
     * <p><b>这是文件级校验，不是恢复演练。</b>它回答的是「文件还在吗、内容有没有变、远端还
     * 取得回来吗」—— 这三件事恰好覆盖了现实中最常见的备份失效：上传截断、远端存储损坏或被
     * 清理、备份进程被杀留下半成品。SHA-256 与备份当时记下的一致，就说明这份文件与写出来
     * 那一刻逐字节相同。</p>
     *
     * <p>真正的「恢复演练」（在一个临时 Schema 上跑一遍再删掉）需要一个可写的临时库、建库
     * 权限，以及一条保证清理的路径，那是另一件事，不在这里做 —— 说清楚边界比给一个名字像
     * 演练、实际只读了文件的功能要好。</p>
     *
     * <p>整份文件要过一遍网络和磁盘，所以这是一个显式动作而不是自动检查。</p>
     */
    public com.example.dbadmin.dto.ApiDtos.BackupVerificationResponse verifyHistory(
            long taskId, long historyId, String actor) throws Exception {
        BackupTask task = repository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Backup task not found: " + taskId));
        BackupHistory history = historyRepository.findByTaskIdAndId(taskId, historyId)
                .orElseThrow(() -> new IllegalArgumentException("Backup history not found: " + historyId));
        if (!history.fileAvailable()) throw new IllegalStateException("该备份历史没有可校验的文件。");

        long started = System.nanoTime();
        BackupVerification verification = new BackupVerification();
        boolean readable = true;
        String failure = null;
        try {
            writeHistoryFile(taskId, historyId, verification);
        } catch (Exception error) {
            readable = false;
            failure = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        }
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        String checksum = readable ? verification.checksum() : null;
        String recorded = history.checksumSha256();
        boolean checksumMatches = readable && recorded != null && recorded.equalsIgnoreCase(checksum);
        boolean sizeMatches = readable && (history.fileSize() == null || history.fileSize() == verification.size());
        Boolean looksComplete = readable && isTextBackup(history.fileFormat())
                ? BackupVerification.looksComplete(verification.tailText()) : null;

        String message = message(readable, recorded, checksumMatches, sizeMatches, looksComplete, failure);
        audit.onConnection(actor, "BACKUP_VERIFY", task.connectionId(), "backup:" + task.name(),
                "history=" + historyId + " readable=" + readable + " checksum="
                        + (recorded == null ? "unrecorded" : checksumMatches) + " bytes=" + verification.size());
        return new com.example.dbadmin.dto.ApiDtos.BackupVerificationResponse(
                historyId, readable, checksumMatches, sizeMatches, looksComplete,
                verification.size(), history.fileSize(), checksum, recorded, elapsedMs, message);
    }

    /** 文本备份才谈得上「尾部完整」；Oracle 的 .dmp 是二进制，只有校验和说得上话。 */
    private static boolean isTextBackup(String fileFormat) {
        return fileFormat == null || !fileFormat.toUpperCase(java.util.Locale.ROOT).contains("DMP");
    }

    private static String message(
            boolean readable, String recorded, boolean checksumMatches,
            boolean sizeMatches, Boolean looksComplete, String failure
    ) {
        if (!readable) return "备份文件读取失败：" + failure;
        if (recorded == null) return "这份备份没有记录校验和（早于该功能的历史记录），已确认文件可以完整读出。";
        if (!checksumMatches) return "校验和与备份当时记录的不一致，这份文件已经不可信，请重新备份。";
        if (!sizeMatches) return "校验和一致但文件大小与记录不符，请确认存储是否被改动过。";
        if (Boolean.FALSE.equals(looksComplete)) {
            return "校验和一致，但文件末尾不像一条写完的语句 —— 备份当时可能就被中断了，恢复前请再确认。";
        }
        return "校验通过：文件可完整读出，校验和与备份当时一致。";
    }

    public DownloadInfo historyDownloadInfo(long taskId, long historyId) throws Exception {
        BackupHistory history = historyRepository.findByTaskIdAndId(taskId, historyId)
                .orElseThrow(() -> new IllegalArgumentException("Backup history not found: " + historyId));
        if (!history.fileAvailable()) throw new IllegalStateException("该备份历史没有可下载文件。");
        if (history.storageProfileId() != null && history.storageObjectKey() != null) {
            long size = history.fileSize() == null ? storage.size(storageProfiles.findById(history.storageProfileId()).orElseThrow(), history.storageObjectKey()) : history.fileSize();
            return new DownloadInfo(fileName(history.storageObjectKey()), size);
        }
        Path path = historyFile(taskId, historyId);
        return new DownloadInfo(path.getFileName().toString(), history.fileSize() == null ? Files.size(path) : history.fileSize());
    }

    public void writeHistoryFile(long taskId, long historyId, OutputStream output) throws Exception {
        BackupHistory history = historyRepository.findByTaskIdAndId(taskId, historyId)
                .orElseThrow(() -> new IllegalArgumentException("Backup history not found: " + historyId));
        if (history.storageProfileId() != null && history.storageObjectKey() != null && "SUCCESS".equals(history.status())) {
            StorageProfile profile = storageProfiles.findById(history.storageProfileId())
                    .orElseThrow(() -> new IllegalStateException("备份使用的文件服务配置已不存在。"));
            storage.download(profile, history.storageObjectKey(), output, ignored -> { });
            return;
        }
        Files.copy(historyFile(taskId, historyId), output);
    }

    public void deleteHistory(long taskId, long historyId, boolean deleteFile, String actor) throws Exception {
        synchronized (taskLock(taskId)) {
            requireNotRunning(taskId);
            BackupTask task = repository.findById(taskId).orElseThrow(() -> new IllegalArgumentException("Backup task not found: " + taskId));
            BackupHistory history = historyRepository.findByTaskIdAndId(taskId, historyId)
                    .orElseThrow(() -> new IllegalArgumentException("Backup history not found: " + historyId));
            if (restoreJobs.countActiveBySource("HISTORY", historyId) > 0) {
                throw new ApiProblemException(HttpStatus.CONFLICT, "BACKUP_IN_USE_BY_RESTORE", "该备份文件正在用于恢复，暂不能删除。");
            }
            if (deleteFile) {
                deleteHistoryFile(history);
            }
            historyRepository.delete(historyId);
            refreshTaskSummary(taskId);
            audit.onConnection(actor, "BACKUP_HISTORY_DELETE", task.connectionId(), "backup:" + task.name(), deleteFile ? "deleteFile=true" : "deleteFile=false");
        }
    }

    private void deleteHistoryFile(BackupHistory history) throws Exception {
        if ("SUCCESS".equals(history.status()) && history.storageProfileId() != null && history.storageObjectKey() != null) {
            StorageProfile profile = storageProfiles.findById(history.storageProfileId())
                    .orElseThrow(() -> new IllegalStateException("备份使用的文件服务配置已不存在。"));
            storage.delete(profile, history.storageObjectKey());
        }
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
        if (latest.storageProfileId() == null || latest.storageObjectKey() == null) {
            repository.updateSummary(taskId, latest.status(), latest.message(), latest.filePath(), latest.fileSize(), latest.finishedAt());
            return;
        }
        repository.updateSummary(taskId, latest.status(), latest.message(), latest.filePath(), latest.fileSize(), latest.finishedAt(),
                latest.storageType(), latest.storageProfileId(), latest.storageObjectKey());
    }

    private String fileName(String objectKey) {
        int index = Math.max(objectKey.lastIndexOf('/'), objectKey.lastIndexOf('\\'));
        return index < 0 ? objectKey : objectKey.substring(index + 1);
    }

    public record DownloadInfo(String fileName, long size) { }

    private List<BackupTask> enrichTasks(List<BackupTask> tasks) {
        if (tasks.isEmpty()) return List.of();
        Map<Long, StorageProfile> profiles = storageProfiles.findAll().stream()
                .collect(java.util.stream.Collectors.toMap(StorageProfile::id, profile -> profile));
        return tasks.stream().map(task -> {
            StorageProfile profile = task.storageProfileId() == null ? null : profiles.get(task.storageProfileId());
            return profile == null ? task : task.withStorageProfile(profile.name(), profile.type());
        }).toList();
    }

    private List<BackupHistory> enrichHistories(List<BackupHistory> histories) {
        if (histories.isEmpty()) return List.of();
        Map<Long, StorageProfile> profiles = storageProfiles.findAll().stream()
                .collect(java.util.stream.Collectors.toMap(StorageProfile::id, profile -> profile));
        return histories.stream().map(history -> {
            StorageProfile profile = history.storageProfileId() == null ? null : profiles.get(history.storageProfileId());
            return profile == null ? history : history.withStorageProfileName(profile.name());
        }).toList();
    }

    private BackupTask taskFromRequest(long id, BackupTaskRequest request, DbConnection connection, String lastStatus, String lastMessage, String lastFilePath, Long lastFileSize, Instant lastRunAt) {
        List<String> requestedTables = requestedTableNames(request.tableNames(), request.tableName());
        String rawScope = request.scope() == null ? "" : request.scope().toUpperCase(Locale.ROOT);
        String scope = validateScope(request.scope(), requestedTables);
        validateRequestTargetShape(rawScope, request.schemaName(), requestedTables);
        String cron = blankToNull(request.cron());
        validateCron(cron, request.enabled());
        String scheduleZone = CronSchedules.normalizeZone(request.scheduleZone());
        String backupMethod = validateBackupMethod(request.backupMethod(), connection);
        String toolPath = blankToNull(request.toolPath());
        String extraArgs = validateExtraArgs(backupMethod, request.extraArgs());
        String nativeConnectName = blankToNull(request.nativeConnectName());
        Integer retentionDays = validateRetention(request.retentionDays(), "保留天数", 3650);
        Integer retentionCount = validateRetention(request.retentionCount(), "最大保留份数", 10000);
        StorageProfile storageProfile = request.storageProfileId() == null ? null : storageProfiles.findById(request.storageProfileId())
                .orElseThrow(() -> new IllegalArgumentException("文件服务配置不存在：" + request.storageProfileId()));
        if (storageProfile != null && !storageProfile.enabled()) {
            throw new IllegalArgumentException("所选文件服务已停用，请重新选择或启用配置。");
        }
        if (!"SQL".equals(backupMethod)) {
            nativeTools.validateOverrideName(NativeBackupRunner.backupTool(backupMethod), toolPath);
        }
        String requestedSchema = Set.of("SCHEMA", "TABLES").contains(scope) ? blankToNull(request.schemaName()) : null;
        ResolvedTarget resolved;
        try {
            resolved = targets.resolveRequestedTarget(connection, scope, requestedSchema, requestedTables);
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
                lastFilePath, lastFileSize, lastRunAt, retentionDays, retentionCount,
                storageProfile == null ? null : storageProfile.id(), storageProfile == null ? null : storageProfile.name(),
                storageProfile == null ? "LOCAL" : storageProfile.type(), null, null, null, scheduleZone
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
        CronSchedules.requireValidCron(cron);
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
        return NativeBackupRunner.isNativeMethod(method) ? nativeRunner.run(task, connection, method) : sqlWriter.write(task, connection);
    }

    private String validateBackupMethod(String method, DbConnection connection) {
        String normalized = normalizeBackupMethod(method);
        if (!Set.of("SQL", "MYSQLDUMP", "ORACLE_EXP", "PG_DUMP").contains(normalized)) {
            throw new IllegalArgumentException("不支持的备份方式：" + method);
        }
        List<String> supportedNativeMethods = dialectRegistry.dialectFor(connection).capabilities().nativeBackupMethods();
        if ("MYSQLDUMP".equals(normalized) && !supportedNativeMethods.contains("MYSQLDUMP")) {
            throw new IllegalArgumentException("mysqldump 备份仅支持 MySQL/MariaDB 连接。");
        }
        if ("PG_DUMP".equals(normalized) && !supportedNativeMethods.contains("PG_DUMP")) {
            throw new IllegalArgumentException("当前连接的数据库类型不支持 pg_dump 备份。");
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
            case "PG_DUMP" -> "pg_dump";
            case "ORACLE_EXP" -> "Oracle exp";
            default -> "SQL";
        };
    }

    /** 额外参数按备份方式校验：能拦住什么取决于工具，见 {@link NativeToolArguments}。 */
    private String validateExtraArgs(String backupMethod, String extraArgs) {
        return NativeToolArguments.normalize(NativeBackupRunner.extraArgsTool(backupMethod), extraArgs, "备份");
    }

    private String backupOperationKey(long taskId) {
        return "backup:" + taskId;
    }

    /** 一次备份产出的文件；三种写法（SQL / 原生工具）共用。 */
    record BackupFile(Path path, long size) {
    }
}
