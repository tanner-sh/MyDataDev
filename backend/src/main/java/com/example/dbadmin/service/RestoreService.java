package com.example.dbadmin.service;

import com.example.dbadmin.config.AppProperties;
import com.example.dbadmin.core.DatabaseDialect;
import com.example.dbadmin.core.DialectRegistry;
import com.example.dbadmin.dto.ApiDtos.ActiveOperations;
import com.example.dbadmin.dto.ApiDtos.RestoreJobPage;
import com.example.dbadmin.dto.ApiDtos.RestorePreflightRequest;
import com.example.dbadmin.dto.ApiDtos.RestorePreflightResponse;
import com.example.dbadmin.dto.ApiDtos.RestoreSourceRef;
import com.example.dbadmin.dto.ApiDtos.RestoreStartRequest;
import com.example.dbadmin.model.BackupHistory;
import com.example.dbadmin.model.DbConnection;
import com.example.dbadmin.model.RestoreJob;
import com.example.dbadmin.model.RestoreUpload;
import com.example.dbadmin.repo.AuditRepository;
import com.example.dbadmin.repo.BackupHistoryRepository;
import com.example.dbadmin.repo.RestoreJobRepository;
import com.example.dbadmin.repo.RestoreUploadRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.http.HttpStatus;
import com.example.dbadmin.api.ApiProblemException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.FileStore;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class RestoreService {
    private static final Logger log = LoggerFactory.getLogger(RestoreService.class);
    private final RestoreUploadRepository uploads;
    private final RestoreJobRepository jobs;
    private final BackupHistoryRepository histories;
    private final ConnectionService connections;
    private final ExecutionGuard guard;
    private final AuditRepository audit;
    private final AppProperties properties;
    private final SqlRestoreTranslator translator;
    private final DialectRegistry dialectRegistry;
    private final BackupExecutionCoordinator coordinator;
    private final ObjectMapper objectMapper;
    private final NativeToolLocator nativeTools;
    private final BackgroundTaskControl taskControl;
    private final LargeFileUploadGuard uploadGuard;
    private final Map<String, PreparedPlan> plans = new ConcurrentHashMap<>();
    private final Map<Long, Statement> runningStatements = new ConcurrentHashMap<>();
    private final Map<Long, Process> runningProcesses = new ConcurrentHashMap<>();
    private final Map<Long, List<String>> preparedTables = new ConcurrentHashMap<>();
    private final Map<Long, FileFingerprint> verifiedSources = new ConcurrentHashMap<>();

    public RestoreService(RestoreUploadRepository uploads, RestoreJobRepository jobs, BackupHistoryRepository histories,
                          ConnectionService connections, ExecutionGuard guard, AuditRepository audit, AppProperties properties,
                          SqlRestoreTranslator translator, DialectRegistry dialectRegistry,
                          BackupExecutionCoordinator coordinator, ObjectMapper objectMapper, NativeToolLocator nativeTools,
                          BackgroundTaskControl taskControl, LargeFileUploadGuard uploadGuard) {
        this.uploads = uploads;
        this.jobs = jobs;
        this.histories = histories;
        this.connections = connections;
        this.guard = guard;
        this.audit = audit;
        this.properties = properties;
        this.translator = translator;
        this.dialectRegistry = dialectRegistry;
        this.coordinator = coordinator;
        this.objectMapper = objectMapper;
        this.nativeTools = nativeTools;
        this.taskControl = taskControl;
        this.uploadGuard = uploadGuard;
    }

    @PostConstruct
    public void recoverInterruptedJobs() {
        jobs.failStaleRunning();
    }

    public RestoreUpload upload(MultipartFile file, String fileFormat, String sourceDbType) throws Exception {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("请选择要恢复的文件。");
        if (file.getSize() > properties.getRestore().getMaxUploadBytes()) throw new IllegalArgumentException("上传文件超过允许大小。");
        return uploadStream(file.getInputStream(), file.getOriginalFilename(), fileFormat, sourceDbType, file.getSize());
    }

    public RestoreUpload uploadStream(InputStream input, String originalFilename, String fileFormat, String sourceDbType) throws Exception {
        return uploadStream(input, originalFilename, fileFormat, sourceDbType, -1);
    }

    public RestoreUpload uploadStream(InputStream input, String originalFilename, String fileFormat, String sourceDbType, long declaredSize) throws Exception {
        if (input == null) throw new IllegalArgumentException("请选择要恢复的文件。");
        if (declaredSize > properties.getRestore().getMaxUploadBytes()) throw new IllegalArgumentException("上传文件超过允许大小。");
        String format = normalizeFormat(fileFormat, originalFilename);
        Path root = restoreRoot().resolve("uploads");
        Files.createDirectories(root);
        if (declaredSize > 0) {
            FileStore store = Files.getFileStore(root);
            if (store.getUsableSpace() < declaredSize + 100L * 1024 * 1024) {
                throw new IllegalStateException("服务器恢复文件目录剩余空间不足。");
            }
        }
        String extension = extension(originalFilename);
        Path target = root.resolve("upload-" + UUID.randomUUID() + extension).normalize();
        if (!target.startsWith(root.toAbsolutePath().normalize())) throw new IllegalArgumentException("上传文件名不安全。");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long size = 0;
        if (!uploadGuard.tryAcquire()) {
            throw new ApiProblemException(HttpStatus.TOO_MANY_REQUESTS, "UPLOAD_BUSY", "当前大文件上传数量已达上限，请稍后重试。");
        }
        try (var output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
            byte[] buffer = new byte[128 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) continue;
                size += read;
                if (size > properties.getRestore().getMaxUploadBytes()) {
                    throw new IllegalArgumentException("上传文件超过允许大小。");
                }
                digest.update(buffer, 0, read);
                output.write(buffer, 0, read);
            }
        } catch (Exception error) {
            Files.deleteIfExists(target);
            throw error;
        } finally {
            uploadGuard.release();
        }
        if (size == 0) {
            Files.deleteIfExists(target);
            throw new IllegalArgumentException("请选择要恢复的文件。");
        }
        Instant expires = Instant.now().plus(Math.max(1, properties.getRestore().getUploadTtlHours()), ChronoUnit.HOURS);
        RestoreUpload draft = new RestoreUpload(0, safeName(originalFilename), target.toAbsolutePath().toString(), size,
                HexFormat.of().formatHex(digest.digest()), format, blankToNull(sourceDbType), Instant.now(), expires);
        long id = uploads.insert(draft);
        return uploads.findById(id).orElseThrow();
    }

    public RestorePreflightResponse preflight(RestorePreflightRequest request) throws Exception {
        SourceFile source = resolveSource(request.source(), request.fileFormat(), request.sourceDbType());
        DbConnection target = connections.require(request.targetConnectionId());
        if (target.readonly()) throw new ApiProblemException(HttpStatus.FORBIDDEN, "READONLY_CONNECTION", "当前连接为只读连接，不允许恢复数据。");
        FileFingerprint fingerprint = verifyChecksum(source.path(), source.checksum());
        String mode = normalizeMode(request.conflictMode());
        Map<String, String> mappings = safeMappings(request.namespaceMapping());
        List<String> warnings = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        long statementCount = 0;
        List<String> namespaces = List.of();
        List<String> tables = List.of();
        String resolvedToolPath = null;
        if ("SQL".equals(source.format())) {
            SqlRestoreTranslator.Analysis analysis = translator.analyze(source.path(), source.dbType(), target.dbType(), mappings);
            statementCount = analysis.statementCount();
            namespaces = analysis.namespaces();
            tables = analysis.tables();
            errors.addAll(analysis.errors());
            if (errors.isEmpty()) validateTargetConflicts(target, tables, mappings, mode, errors, warnings);
        } else {
            validateNativeCompatibility(source.format(), source.dbType(), target.dbType(), request.toolPath(), errors);
            if (errors.isEmpty()) {
                try {
                    NativeToolLocator.ResolvedTool tool = nativeTools.resolve(restoreTool(source.format()), request.toolPath());
                    resolvedToolPath = tool.path().toString();
                    warnings.add("本次恢复将使用 " + resolvedToolPath + "（" + tool.version() + "）。");
                } catch (IllegalArgumentException error) {
                    errors.add(error.getMessage());
                }
            }
            try {
                extraArgs(request.extraArgs());
            } catch (IllegalArgumentException error) {
                errors.add(error.getMessage());
            }
            warnings.add("原生恢复由数据库工具执行；对象覆盖行为还会受到备份文件自身参数影响。");
        }
        if ("OVERWRITE".equals(mode)) warnings.add("覆盖模式只会删除预检识别到的目标基础表，执行后无法自动撤销。");
        if ("APPEND".equals(mode)) warnings.add("追加模式忽略输入文件中的建表和索引语句，仅执行 INSERT。");
        boolean valid = errors.isEmpty();
        String token = null;
        if (valid) {
            token = UUID.randomUUID().toString();
            plans.put(token, new PreparedPlan(token, Instant.now().plus(10, ChronoUnit.MINUTES), request, source, target.dbType(),
                    statementCount, namespaces, tables, resolvedToolPath, fingerprint));
        }
        return new RestorePreflightResponse(valid, token, source.format(), source.dbType(), target.dbType(), statementCount,
                namespaces, tables, List.copyOf(warnings), List.copyOf(errors));
    }

    public RestoreJob start(RestoreStartRequest request, String actor) throws Exception {
        PreparedPlan plan = plans.remove(request.planToken());
        if (plan == null || plan.expiresAt().isBefore(Instant.now())) throw new IllegalArgumentException("恢复预检已过期，请重新预检。");
        if (!matches(plan, request)) throw new IllegalArgumentException("恢复参数与预检结果不一致，请重新预检。");
        DbConnection target = connections.require(request.targetConnectionId());
        if (!target.dbType().equalsIgnoreCase(plan.targetDbType())) {
            throw new IllegalArgumentException("目标连接类型在预检后已发生变化，请重新预检。");
        }
        guard.requireMutationAllowed(target, request.productionConfirmation());
        if (jobs.countActiveByConnectionId(target.id()) > 0) throw new ApiProblemException(HttpStatus.CONFLICT, "RESTORE_ALREADY_RUNNING", "该连接已有恢复任务正在执行。");
        if ("OVERWRITE".equalsIgnoreCase(request.conflictMode()) && !target.name().equals(request.productionConfirmation())) {
            throw new ApiProblemException(HttpStatus.CONFLICT, "RESTORE_CONFIRMATION_REQUIRED", "覆盖恢复需要输入目标连接名确认。", Map.of("confirmationText", target.name()));
        }
        String mappingJson = objectMapper.writeValueAsString(safeMappings(request.namespaceMapping()));
        RestoreJob draft = new RestoreJob(0, plan.source().kind(), plan.source().id(), plan.source().name(),
                plan.source().path().toString(), plan.source().checksum(), plan.source().format(), plan.source().dbType(),
                target.id(), target.dbType(), normalizeMode(request.conflictMode()), mappingJson, "QUEUED", "QUEUED", 0L,
                plan.statementCount(), "恢复任务已进入队列。", false, actor, null, null, Instant.now());
        long id = jobs.insert(draft);
        String operationKey = restoreOperationKey(id);
        if (!taskControl.tryAcquire(target.id(), operationKey)) {
            jobs.updateProgress(id, "FAILED", "CONNECTION_BUSY", 0, plan.statementCount(), "该连接已有后台重任务正在执行。", null, Instant.now());
            throw new ApiProblemException(HttpStatus.CONFLICT, "CONNECTION_BACKGROUND_BUSY", "该连接已有后台重任务正在执行，请等待完成后重试。");
        }
        preparedTables.put(id, plan.tables());
        verifiedSources.put(id, plan.fingerprint());
        try {
            boolean accepted = coordinator.submit(-id, () -> { }, () -> {
                try {
                    run(id, plan.resolvedToolPath(), request.extraArgs());
                } finally {
                    taskControl.release(target.id(), operationKey);
                    preparedTables.remove(id);
                    verifiedSources.remove(id);
                }
            });
            if (!accepted) throw new ApiProblemException(HttpStatus.CONFLICT, "RESTORE_ALREADY_RUNNING", "该恢复任务正在执行，请勿重复启动。");
        } catch (RejectedExecutionException error) {
            taskControl.release(target.id(), operationKey);
            preparedTables.remove(id);
            verifiedSources.remove(id);
            jobs.updateProgress(id, "FAILED", "FAILED", 0, plan.statementCount(), "恢复执行队列已满，任务未启动。", null, Instant.now());
            throw new ApiProblemException(HttpStatus.TOO_MANY_REQUESTS, "RESTORE_QUEUE_FULL", "恢复执行队列已满，请稍后重试。");
        } catch (RuntimeException error) {
            taskControl.release(target.id(), operationKey);
            preparedTables.remove(id);
            verifiedSources.remove(id);
            throw error;
        }
        audit.log(actor, "RESTORE_START", target.name(), plan.source().name());
        return jobs.findById(id).orElseThrow();
    }

    public RestoreJobPage list(Long connectionId, Integer page, Integer pageSize) {
        int safePage = Math.max(page == null ? 0 : page, 0);
        int size = Math.min(Math.max(pageSize == null ? 20 : pageSize, 1), 100);
        long offset = (long) safePage * size;
        if (offset > 1_000_000) throw new IllegalArgumentException("恢复历史分页偏移过大，请缩小页码。");
        List<RestoreJob> rows = jobs.findPage(connectionId, size + 1, offset);
        boolean hasMore = rows.size() > size;
        if (hasMore) rows = rows.subList(0, size);
        return new RestoreJobPage(List.copyOf(rows), safePage, size, hasMore);
    }

    public RestoreJob get(long id) {
        return jobs.findById(id).orElseThrow(() -> new IllegalArgumentException("Restore job not found: " + id));
    }

    public RestoreJob cancel(long id, String actor) throws Exception {
        RestoreJob job = get(id);
        if (!Set.of("QUEUED", "RUNNING").contains(job.status())) return job;
        jobs.requestCancel(id);
        taskControl.requestCancel(restoreOperationKey(id));
        coordinator.cancel(-id);
        Statement statement = runningStatements.get(id);
        if (statement != null) statement.cancel();
        Process process = runningProcesses.get(id);
        if (process != null) process.destroyForcibly();
        taskControl.release(job.targetConnectionId(), restoreOperationKey(id));
        jobs.updateProgress(id, "CANCELLED", "CANCELLED", value(job.progressCurrent()), job.progressTotal(), "恢复已取消。", null, Instant.now());
        audit.log(actor, "RESTORE_CANCEL", job.sourceName(), "job=" + id);
        return get(id);
    }

    public ActiveOperations active(Long connectionId, List<BackupHistory> backupOperations) {
        return new ActiveOperations(backupOperations, jobs.findActive(connectionId));
    }

    private void run(long id, String toolPath, String extraArgs) {
        RestoreJob job = get(id);
        jobs.updateProgress(id, "RUNNING", "PREPARING", 0, job.progressTotal(), "正在准备恢复。", Instant.now(), null);
        try {
            verifyUnchanged(Path.of(job.sourceFilePath()), job.sourceChecksum(), verifiedSources.get(id));
            if ("SQL".equals(job.fileFormat())) runSql(job);
            else runNative(job, toolPath, extraArgs);
            ensureNotCancelled(id);
            jobs.updateProgress(id, "SUCCESS", "COMPLETED", job.progressTotal() == null ? 0 : job.progressTotal(),
                    job.progressTotal(), "恢复完成。", null, Instant.now());
            audit.log(job.actor(), "RESTORE_SUCCESS", job.sourceName(), "job=" + id);
        } catch (CancelledException cancelled) {
            RestoreJob latest = jobs.findById(id).orElse(job);
            jobs.updateProgress(id, "CANCELLED", "CANCELLED", value(latest.progressCurrent()), latest.progressTotal(), "恢复已取消。", null, Instant.now());
        } catch (Exception error) {
            RestoreJob latest = jobs.findById(id).orElse(job);
            boolean cancelled = latest.cancelRequested() || "CANCELLED".equals(latest.status()) || Thread.currentThread().isInterrupted();
            String status = cancelled ? "CANCELLED" : "FAILED";
            String message = cancelled ? "恢复已取消。" : safeMessage(error);
            jobs.updateProgress(id, status, status, value(latest.progressCurrent()), latest.progressTotal(), message, null, Instant.now());
            audit.log(job.actor(), cancelled ? "RESTORE_CANCELLED" : "RESTORE_FAILED", job.sourceName(), message);
        } finally {
            runningStatements.remove(id);
            runningProcesses.remove(id);
        }
    }

    private void runSql(RestoreJob job) throws Exception {
        Map<String, String> mappings = objectMapper.readValue(job.namespaceMapping(), new TypeReference<>() { });
        DbConnection target = connections.require(job.targetConnectionId());
        DatabaseDialect dialect = dialectRegistry.dialectFor(target);
        try (Connection connection = connections.open(target.id())) {
            connection.setAutoCommit(false);
            if ("OVERWRITE".equals(job.conflictMode())) {
                List<String> tables = preparedTables.get(job.id());
                if (tables == null) {
                    throw new IllegalStateException("恢复预检结果已丢失，请重新预检后启动。");
                }
                dropMappedTables(connection, dialect, tables, mappings);
                connection.commit();
            }
            long[] current = {0};
            long[] lastProgressNanos = {0};
            try {
                try (Statement statement = connection.createStatement()) {
                    statement.setQueryTimeout(Math.max(1, properties.getBackup().getTimeoutSeconds()));
                    translator.translate(Path.of(job.sourceFilePath()), job.sourceDbType(), target.dbType(), mappings, job.conflictMode(), (index, sql, data) -> {
                        ensureNotCancelled(job.id());
                        runningStatements.put(job.id(), statement);
                        try {
                            statement.execute(sql);
                        } finally {
                            runningStatements.remove(job.id());
                        }
                        current[0] = index;
                        if (data && current[0] % 500 == 0) connection.commit();
                        if (progressDue(lastProgressNanos)) {
                            jobs.updateProgress(job.id(), "RUNNING", data ? "RESTORING_DATA" : "RESTORING_SCHEMA", current[0], job.progressTotal(),
                                    "正在执行第 " + current[0] + " 条语句。", null, null);
                        }
                    });
                }
                connection.commit();
                jobs.updateProgress(job.id(), "RUNNING", "FINALIZING", current[0], job.progressTotal(),
                        "SQL 恢复语句执行完成，正在收尾。", null, null);
            } catch (Exception error) {
                try { connection.rollback(); } catch (Exception ignored) { }
                throw error;
            }
        }
    }

    private void runNative(RestoreJob job, String toolPath, String extraArgs) throws Exception {
        if (toolPath == null || toolPath.isBlank()) throw new IllegalArgumentException("原生恢复需要填写工具路径。");
        DbConnection target = connections.require(job.targetConnectionId());
        List<String> command = new ArrayList<>();
        ProcessBuilder builder;
        if ("MYSQLDUMP".equals(job.fileFormat())) {
            MysqlTarget mysql = mysqlTarget(target.jdbcUrl());
            command.add(toolPath);
            command.add("--host=" + mysql.host());
            command.add("--port=" + mysql.port());
            if (target.username() != null && !target.username().isBlank()) command.add("--user=" + target.username());
            command.addAll(extraArgs(extraArgs));
            if (mysql.database() != null) command.add(mysql.database());
            builder = new ProcessBuilder(command);
            builder.redirectInput(Path.of(job.sourceFilePath()).toFile());
            String password = connections.password(target.id());
            if (password != null) builder.environment().put("MYSQL_PWD", password);
        } else {
            String password = connections.password(target.id());
            String connect = oracleConnectName(target.jdbcUrl());
            Set<PosixFilePermission> ownerOnly = Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            Path root = restoreRoot();
            Files.createDirectories(root);
            Path par = Files.getFileStore(root).supportsFileAttributeView("posix")
                    ? Files.createTempFile(root, ".oracle-imp-", ".par", PosixFilePermissions.asFileAttribute(ownerOnly))
                    : Files.createTempFile(root, ".oracle-imp-", ".par");
            Files.writeString(par, "userid=" + target.username() + "/" + (password == null ? "" : password) + (connect == null ? "" : "@" + connect)
                    + "\nfile=" + Path.of(job.sourceFilePath()).toAbsolutePath() + "\n", StandardCharsets.UTF_8);
            command.add(toolPath);
            command.add("parfile=" + par.toAbsolutePath());
            command.addAll(extraArgs(extraArgs));
            builder = new ProcessBuilder(command);
            builder.redirectErrorStream(true);
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            try {
                executeNative(job.id(), builder);
            } finally {
                Files.deleteIfExists(par);
            }
            return;
        }
        builder.redirectErrorStream(true);
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        executeNative(job.id(), builder);
    }

    private void executeNative(long jobId, ProcessBuilder builder) throws Exception {
        Process process = builder.start();
        runningProcesses.put(jobId, process);
        try {
            long timeoutSeconds = Math.max(1, properties.getBackup().getTimeoutSeconds());
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                terminateProcess(process);
                throw new IllegalStateException("原生恢复执行超时（" + timeoutSeconds + " 秒）。");
            }
            ensureNotCancelled(jobId);
            int exit = process.exitValue();
            if (exit != 0) throw new IllegalStateException("原生恢复失败，退出码 " + exit + "。");
        } finally {
            runningProcesses.remove(jobId);
            terminateProcess(process);
        }
    }

    private void terminateProcess(Process process) {
        if (process == null || !process.isAlive()) return;
        process.descendants().forEach(handle -> {
            if (handle.isAlive()) handle.destroyForcibly();
        });
        process.destroyForcibly();
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                log.warn("Native restore process did not exit after forcible termination pid={}", process.pid());
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void validateTargetConflicts(DbConnection target, List<String> sourceTables, Map<String, String> mappings,
                                         String mode, List<String> errors, List<String> warnings) throws Exception {
        try (Connection connection = connections.open(target.id())) {
            for (String sourceTable : sourceTables) {
                TableName source = tableName(sourceTable);
                String namespace = source.namespace() == null ? null : mappings.getOrDefault(source.namespace(), source.namespace());
                boolean exists = tableExists(connection.getMetaData(), target, namespace, source.name());
                if ("SAFE".equals(mode) && exists) errors.add("安全模式下目标表已存在：" + qualified(namespace, source.name()));
                if ("APPEND".equals(mode) && !exists) errors.add("追加模式要求目标表已存在：" + qualified(namespace, source.name()));
                if ("OVERWRITE".equals(mode) && exists) warnings.add("将删除并重建：" + qualified(namespace, source.name()));
            }
        }
    }

    private boolean tableExists(DatabaseMetaData meta, DbConnection target, String namespace, String table) throws Exception {
        boolean catalogBased = Set.of("mysql", "mariadb", "oceanbase-mysql").contains(target.dbType().toLowerCase(Locale.ROOT));
        try (ResultSet rows = meta.getTables(catalogBased ? namespace : null, catalogBased ? null : namespace, table, new String[]{"TABLE"})) {
            return rows.next();
        }
    }

    private void dropMappedTables(Connection connection, DatabaseDialect dialect, List<String> tables, Map<String, String> mappings) throws Exception {
        for (int index = tables.size() - 1; index >= 0; index--) {
            TableName source = tableName(tables.get(index));
            String namespace = source.namespace() == null ? null : mappings.getOrDefault(source.namespace(), source.namespace());
            try (Statement statement = connection.createStatement()) {
                statement.execute("DROP TABLE " + dialect.qualifiedName(namespace, source.name()));
            } catch (Exception ignored) {
                // Missing target tables are valid in overwrite mode.
            }
        }
    }

    private SourceFile resolveSource(RestoreSourceRef ref, String requestedFormat, String requestedDbType) {
        if (ref == null || ref.id() == null) throw new IllegalArgumentException("恢复来源不能为空。");
        if ("HISTORY".equalsIgnoreCase(ref.kind())) {
            BackupHistory history = histories.findById(ref.id()).orElseThrow(() -> new IllegalArgumentException("Backup history not found: " + ref.id()));
            if (history.filePath() == null) throw new IllegalArgumentException("该历史没有可恢复文件。");
            Path path = FileIntegrity.checkedPath(restoreRoot(), history.filePath());
            String format = history.fileFormat() == null ? formatFromMethod(history.backupMethod(), requestedFormat) : history.fileFormat();
            String dbType = history.sourceDbType() == null ? blankToNull(requestedDbType) : history.sourceDbType();
            return new SourceFile("HISTORY", history.id(), path.getFileName().toString(), path,
                    history.checksumSha256() == null ? uncheckedChecksum(path) : history.checksumSha256(), format, dbType);
        }
        if ("UPLOAD".equalsIgnoreCase(ref.kind())) {
            RestoreUpload upload = uploads.findById(ref.id()).orElseThrow(() -> new IllegalArgumentException("Restore upload not found: " + ref.id()));
            if (upload.expiresAt().isBefore(Instant.now())) throw new IllegalArgumentException("上传文件已过期，请重新上传。");
            return new SourceFile("UPLOAD", upload.id(), upload.originalName(), FileIntegrity.checkedPath(restoreRoot(), upload.filePath()),
                    upload.checksumSha256(), upload.fileFormat(), upload.sourceDbType() == null ? requestedDbType : upload.sourceDbType());
        }
        throw new IllegalArgumentException("不支持的恢复来源：" + ref.kind());
    }

    private void validateNativeCompatibility(String format, String sourceDbType, String targetDbType, String toolPath, List<String> errors) {
        String source = family(sourceDbType);
        String target = family(targetDbType);
        if ("MYSQLDUMP".equals(format) && !("MYSQL".equals(source) && "MYSQL".equals(target))) errors.add("MySQL dump 只能恢复到 MySQL 兼容连接。");
        if ("ORACLE_DMP".equals(format) && !("ORACLE".equals(source) && "ORACLE".equals(target))) errors.add("Oracle dmp 只能恢复到 Oracle 兼容连接。");
        try {
            nativeTools.validateOverrideName(restoreTool(format), toolPath);
        } catch (IllegalArgumentException error) {
            errors.add(error.getMessage());
        }
    }

    private boolean matches(PreparedPlan plan, RestoreStartRequest request) {
        RestorePreflightRequest before = plan.request();
        return before != null && before.source().kind().equalsIgnoreCase(request.source().kind())
                && before.source().id().equals(request.source().id())
                && before.targetConnectionId().equals(request.targetConnectionId())
                && before.fileFormat().equalsIgnoreCase(request.fileFormat())
                && before.sourceDbType().equalsIgnoreCase(request.sourceDbType())
                && before.conflictMode().equalsIgnoreCase(request.conflictMode())
                && java.util.Objects.equals(blankToNull(before.toolPath()), blankToNull(request.toolPath()))
                && java.util.Objects.equals(normalizedExtraArgs(before.extraArgs()), normalizedExtraArgs(request.extraArgs()))
                && safeMappings(before.namespaceMapping()).equals(safeMappings(request.namespaceMapping()));
    }

    private void ensureNotCancelled(long id) {
        if (taskControl.isCancelled(restoreOperationKey(id),
                () -> jobs.findById(id).map(RestoreJob::cancelRequested).orElse(true))) {
            throw new CancelledException();
        }
    }

    private boolean progressDue(long[] lastProgressNanos) {
        long now = System.nanoTime();
        long interval = Math.max(100, properties.getBackgroundTasks().getProgressIntervalMs()) * 1_000_000L;
        if (lastProgressNanos[0] != 0 && now - lastProgressNanos[0] < interval) return false;
        lastProgressNanos[0] = now;
        return true;
    }

    private String restoreOperationKey(long id) {
        return "restore:" + id;
    }

    @Scheduled(fixedDelay = 60 * 60 * 1000, initialDelay = 60 * 1000)
    public void cleanupExpiredUploads() {
        for (RestoreUpload upload : uploads.findExpired(Instant.now())) {
            try {
                if (jobs.countActiveBySource("UPLOAD", upload.id()) > 0) continue;
                Files.deleteIfExists(FileIntegrity.checkedPath(restoreRoot(), upload.filePath()));
                uploads.delete(upload.id());
            } catch (Exception ignored) { }
        }
        plans.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(Instant.now()));
    }

    private Path restoreRoot() {
        return Path.of(properties.getBackup().getDirectory()).toAbsolutePath().normalize();
    }

    private FileFingerprint verifyChecksum(Path path, String expected) throws Exception {
        if (!Files.isRegularFile(path)) throw new IllegalStateException("恢复文件不存在。");
        BasicFileAttributes before = Files.readAttributes(path, BasicFileAttributes.class);
        String actual = FileIntegrity.sha256(path);
        if (expected != null && !expected.equalsIgnoreCase(actual)) throw new IllegalStateException("恢复文件校验失败，文件内容已发生变化。");
        BasicFileAttributes after = Files.readAttributes(path, BasicFileAttributes.class);
        FileFingerprint fingerprint = fingerprint(after);
        if (!fingerprint(before).equals(fingerprint)) {
            throw new IllegalStateException("恢复文件在校验期间发生变化，请重新预检。");
        }
        return fingerprint;
    }

    private void verifyUnchanged(Path path, String expected, FileFingerprint verified) throws Exception {
        if (!Files.isRegularFile(path)) throw new IllegalStateException("恢复文件不存在。");
        FileFingerprint current = fingerprint(Files.readAttributes(path, BasicFileAttributes.class));
        if (verified == null || !verified.equals(current)) {
            verifyChecksum(path, expected);
        }
    }

    private FileFingerprint fingerprint(BasicFileAttributes attributes) {
        return new FileFingerprint(attributes.size(), attributes.lastModifiedTime().toMillis(),
                attributes.fileKey() == null ? null : attributes.fileKey().toString());
    }

    private String uncheckedChecksum(Path path) {
        try { return FileIntegrity.sha256(path); } catch (Exception error) { throw new IllegalStateException("无法计算文件校验值。", error); }
    }

    private Map<String, String> safeMappings(Map<String, String> mapping) {
        Map<String, String> safe = new LinkedHashMap<>();
        if (mapping != null) mapping.forEach((source, target) -> {
            if (source != null && !source.isBlank() && target != null && !target.isBlank()) safe.put(source.trim(), target.trim());
        });
        return Map.copyOf(safe);
    }

    private String normalizeFormat(String format, String filename) {
        String normalized = format == null ? "" : format.trim().toUpperCase(Locale.ROOT);
        if (normalized.isBlank()) normalized = filename != null && filename.toLowerCase(Locale.ROOT).endsWith(".dmp") ? "ORACLE_DMP" : "SQL";
        if (!Set.of("SQL", "MYSQLDUMP", "ORACLE_DMP").contains(normalized)) throw new IllegalArgumentException("不支持的恢复文件格式：" + format);
        return normalized;
    }

    private String normalizeMode(String mode) {
        String normalized = mode == null ? "" : mode.toUpperCase(Locale.ROOT);
        if (!Set.of("SAFE", "OVERWRITE", "APPEND").contains(normalized)) throw new IllegalArgumentException("不支持的恢复冲突策略：" + mode);
        return normalized;
    }

    private String formatFromMethod(String method, String fallback) {
        if ("MYSQLDUMP".equalsIgnoreCase(method)) return "MYSQLDUMP";
        if ("ORACLE_EXP".equalsIgnoreCase(method)) return "ORACLE_DMP";
        return normalizeFormat(fallback, null);
    }

    private String family(String dbType) {
        String type = dbType == null ? "" : dbType.toLowerCase(Locale.ROOT);
        if (Set.of("mysql", "mariadb", "oceanbase-mysql").contains(type)) return "MYSQL";
        if (Set.of("oracle", "oceanbase-oracle", "dm").contains(type)) return "ORACLE";
        return type.toUpperCase(Locale.ROOT);
    }

    private MysqlTarget mysqlTarget(String jdbcUrl) {
        try {
            URI uri = URI.create(jdbcUrl.replaceFirst("^jdbc:", ""));
            String database = uri.getPath() == null ? null : uri.getPath().replaceFirst("^/", "");
            return new MysqlTarget(uri.getHost() == null ? "localhost" : uri.getHost(), uri.getPort() < 0 ? 3306 : uri.getPort(), blankToNull(database));
        } catch (Exception error) {
            throw new IllegalArgumentException("无法解析 MySQL JDBC URL。");
        }
    }

    private String oracleConnectName(String jdbcUrl) {
        String prefix = "jdbc:oracle:thin:@";
        return jdbcUrl != null && jdbcUrl.startsWith(prefix) ? jdbcUrl.substring(prefix.length()) : null;
    }

    private List<String> extraArgs(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        List<String> result = new ArrayList<>();
        for (String line : raw.split("\\R")) {
            String value = line.trim();
            if (value.isBlank()) continue;
            if (value.length() > 2_000) throw new IllegalArgumentException("单个恢复额外参数不能超过 2000 个字符。");
            if (result.size() >= 100) throw new IllegalArgumentException("恢复额外参数最多填写 100 行。");
            if (value.matches(".*[|&;<>`$].*")) throw new IllegalArgumentException("恢复额外参数包含不允许的 shell 控制字符。");
            String lower = value.toLowerCase(Locale.ROOT);
            String option = lower.contains("=") ? lower.substring(0, lower.indexOf('=')) : lower;
            if (Set.of("--host", "--port", "--user", "--password", "--database", "-h", "-p", "-u",
                    "userid", "file", "parfile", "log").contains(option)
                    || value.startsWith("-h") || value.startsWith("-P") || value.startsWith("-p") || value.startsWith("-u")) {
                throw new IllegalArgumentException("恢复额外参数不能覆盖系统控制参数：" + value);
            }
            result.add(value);
        }
        return result;
    }

    private String normalizedExtraArgs(String raw) {
        List<String> args = extraArgs(raw);
        return args.isEmpty() ? null : String.join("\n", args);
    }

    private NativeToolLocator.Tool restoreTool(String format) {
        return "MYSQLDUMP".equals(format) ? NativeToolLocator.Tool.MYSQL : NativeToolLocator.Tool.ORACLE_IMP;
    }

    private TableName tableName(String qualified) {
        int dot = qualified.lastIndexOf('.');
        return dot < 0 ? new TableName(null, qualified) : new TableName(qualified.substring(0, dot), qualified.substring(dot + 1));
    }

    private String qualified(String namespace, String name) { return namespace == null ? name : namespace + "." + name; }
    private String safeName(String name) { return name == null || name.isBlank() ? "restore-file" : Path.of(name).getFileName().toString(); }
    private String extension(String name) { if (name == null) return ".bin"; int dot = name.lastIndexOf('.'); return dot < 0 ? ".bin" : name.substring(dot).replaceAll("[^A-Za-z0-9.]", ""); }
    private String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private String safeMessage(Exception error) { return error.getMessage() == null || error.getMessage().isBlank() ? error.getClass().getSimpleName() : error.getMessage(); }
    private long value(Long value) { return value == null ? 0 : value; }

    private record SourceFile(String kind, long id, String name, Path path, String checksum, String format, String dbType) { }
    private record PreparedPlan(String token, Instant expiresAt, RestorePreflightRequest request, SourceFile source,
                                String targetDbType, long statementCount, List<String> namespaces, List<String> tables,
                                String resolvedToolPath, FileFingerprint fingerprint) { }
    private record FileFingerprint(long size, long modifiedMillis, String fileKey) { }
    private record TableName(String namespace, String name) { }
    private record MysqlTarget(String host, int port, String database) { }
    private static final class CancelledException extends RuntimeException { }
}
