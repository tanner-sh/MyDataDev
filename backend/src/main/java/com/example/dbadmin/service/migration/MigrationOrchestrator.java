package com.example.dbadmin.service.migration;

import com.example.dbadmin.config.AppProperties;
import com.example.dbadmin.core.DatabaseDialect;
import com.example.dbadmin.core.DialectRegistry;
import com.example.dbadmin.dto.MigrationDtos.MigrationVerificationReport;
import com.example.dbadmin.dto.MigrationDtos.TableVerificationResult;
import com.example.dbadmin.model.DbConnection;
import com.example.dbadmin.model.MigrationArtifact;
import com.example.dbadmin.model.MigrationItem;
import com.example.dbadmin.model.MigrationJob;
import com.example.dbadmin.model.StorageProfile;
import com.example.dbadmin.repo.AuditRepository;
import com.example.dbadmin.repo.MigrationArtifactRepository;
import com.example.dbadmin.repo.MigrationItemRepository;
import com.example.dbadmin.repo.MigrationJobRepository;
import com.example.dbadmin.service.BackgroundTaskControl;
import com.example.dbadmin.service.CompilationDiagnostics;
import com.example.dbadmin.service.ConnectionService;
import com.example.dbadmin.service.MetadataCacheService;
import com.example.dbadmin.service.ReadOnlyQueryScope;
import com.example.dbadmin.service.migration.artifact.MddPackageFormat;
import com.example.dbadmin.service.migration.artifact.MddPackageReader;
import com.example.dbadmin.service.migration.artifact.MddPackageWriter;
import com.example.dbadmin.service.migration.mapping.MigrationAssessor.MigrationPlan;
import com.example.dbadmin.service.migration.mapping.MigrationAssessor.ProgramExecutionPlan;
import com.example.dbadmin.service.migration.mapping.MigrationAssessor.SequenceExecutionPlan;
import com.example.dbadmin.service.migration.mapping.MigrationAssessor.TableExecutionPlan;
import com.example.dbadmin.service.migration.mapping.TargetDdlEmitter;
import com.example.dbadmin.service.migration.model.CanonicalSchema.ArtifactPartEntry;
import com.example.dbadmin.service.migration.model.CanonicalSchema.ArtifactTableEntry;
import com.example.dbadmin.service.migration.model.CanonicalSchema.TableChecksum;
import com.example.dbadmin.service.migration.model.DbTargetDialect;
import com.example.dbadmin.service.migration.model.MigrationOptions;
import com.example.dbadmin.service.migration.transfer.JdbcDataCopier;
import com.example.dbadmin.service.migration.verify.MigrationVerifier;
import com.example.dbadmin.storage.BackupStorageRegistry;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 异构迁移后台编排执行引擎。
 *
 * <p>严格按 6 阶段执行：
 * <ul>
 *   <li>Stage 1：建基础表 + 主键 + 表/列注释（按 conflictMode 处理已存在表）；</li>
 *   <li>Stage 2：受控并发流式灌数据（在线直迁 / .mddpkg 分片导入），持久化分片检查点支持断点续传；</li>
 *   <li>Stage 3：补建二级索引、唯一约束、外键、CHECK 约束；</li>
 *   <li>Stage 4：创建独立序列并按实测 MAX+1 对齐自增/序列当前值；</li>
 *   <li>Stage 5：按拓扑序执行已确认的可编程对象并查编译诊断；</li>
 *   <li>Stage 6：自动触发 L1/L2/L3 一致性校验并归档验收报告。</li>
 * </ul></p>
 */
@Component
public class MigrationOrchestrator {
    private static final Logger log = LoggerFactory.getLogger(MigrationOrchestrator.class);
    /** 全局任务进度落盘节流间隔（500 ms），避免万表/高并发场景打爆本地 H2 行锁。 */
    private static final long JOB_PROGRESS_THROTTLE_NANOS = 500_000_000L;

    private final AppProperties properties;
    private final ConnectionService connections;
    private final DialectRegistry dialectRegistry;
    private final MetadataCacheService metadataCache;
    private final BackgroundTaskControl taskControl;
    private final BackupStorageRegistry storageRegistry;
    private final TargetDdlEmitter ddlEmitter;
    private final JdbcDataCopier dataCopier;
    private final MigrationVerifier verifier;
    private final MigrationJobRepository jobs;
    private final MigrationItemRepository items;
    private final MigrationArtifactRepository artifacts;
    private final AuditRepository audit;
    private final ObjectMapper objectMapper;

    private final ThreadPoolExecutor jobExecutor;
    private final Map<Long, MigrationPlan> activePlans = new ConcurrentHashMap<>();
    private final Map<Long, Map<String, TableChecksum>> jobSourceChecksums = new ConcurrentHashMap<>();

    public MigrationOrchestrator(
            AppProperties properties,
            ConnectionService connections,
            DialectRegistry dialectRegistry,
            MetadataCacheService metadataCache,
            BackgroundTaskControl taskControl,
            BackupStorageRegistry storageRegistry,
            TargetDdlEmitter ddlEmitter,
            JdbcDataCopier dataCopier,
            MigrationVerifier verifier,
            MigrationJobRepository jobs,
            MigrationItemRepository items,
            MigrationArtifactRepository artifacts,
            AuditRepository audit,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.connections = connections;
        this.dialectRegistry = dialectRegistry;
        this.metadataCache = metadataCache;
        this.taskControl = taskControl;
        this.storageRegistry = storageRegistry;
        this.ddlEmitter = ddlEmitter;
        this.dataCopier = dataCopier;
        this.verifier = verifier;
        this.jobs = jobs;
        this.items = items;
        this.artifacts = artifacts;
        this.audit = audit;
        this.objectMapper = objectMapper;

        int workers = Math.max(1, properties.getMigration().getWorkerThreads());
        int capacity = Math.max(4, properties.getMigration().getQueueCapacity());
        this.jobExecutor = new ThreadPoolExecutor(
                workers, workers, 60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(capacity),
                r -> {
                    Thread t = new Thread(r, "dbadmin-migration-job");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    @PostConstruct
    public void recoverInterruptedJobs() {
        jobs.failStaleRunning();
        items.failStaleRunning();
    }

    @PreDestroy
    public void shutdown() {
        jobExecutor.shutdownNow();
    }

    public static String operationKey(long jobId) {
        return "migration:" + jobId;
    }

    public void rememberPlan(long jobId, MigrationPlan plan) {
        activePlans.put(jobId, plan);
    }

    public MigrationPlan cachedPlan(long jobId) {
        return activePlans.get(jobId);
    }

    public Map<String, TableChecksum> cachedSourceChecksums(long jobId) {
        return jobSourceChecksums.get(jobId);
    }

    public void submit(long jobId, MigrationPlan plan, Long sourceConnectionId, Long targetConnectionId, String actor, Long ownerUserId) {
        activePlans.put(jobId, plan);
        String opKey = operationKey(jobId);
        jobExecutor.execute(() -> {
            try {
                executeJob(jobId, plan, actor, ownerUserId, opKey);
            } catch (Throwable fatal) {
                log.error("迁移任务 {} 出现未捕获异常", jobId, fatal);
                MigrationJob current = jobs.findById(jobId).orElse(null);
                if (current != null && ("QUEUED".equals(current.status()) || "RUNNING".equals(current.status()))) {
                    jobs.updateProgress(jobId, "FAILED", "FAILED",
                            current.totalItems(), current.completedItems(), Math.max(1, current.failedItems()),
                            current.totalRows(), current.migratedRows(), current.migratedBytes(),
                            "迁移任务异常中止：" + rootMessage(fatal), current.startedAt(), Instant.now());
                }
            } finally {
                if (sourceConnectionId != null) taskControl.releaseCompleted(sourceConnectionId, opKey);
                if (targetConnectionId != null) taskControl.releaseCompleted(targetConnectionId, opKey);
                taskControl.clear(opKey);
            }
        });
    }

    private void executeJob(long jobId, MigrationPlan plan, String actor, Long ownerUserId, String opKey) {
        Instant startedAt = Instant.now();
        MigrationJob job = jobs.findById(jobId).orElseThrow();
        List<MigrationItem> allItems = items.findByJobId(jobId);
        Map<String, MigrationItem> tableItemById = new LinkedHashMap<>();
        Map<String, MigrationItem> seqItemByName = new LinkedHashMap<>();
        Map<String, MigrationItem> progItemByKey = new LinkedHashMap<>();
        for (MigrationItem item : allItems) {
            if ("TABLE".equals(item.itemKind())) tableItemById.put(item.tableId(), item);
            else if ("SEQUENCE".equals(item.itemKind())) seqItemByName.put(item.sourceName(), item);
            else progItemByKey.put(item.itemKind() + ":" + item.sourceName(), item);
        }

        if ("EXPORT_ARTIFACT".equalsIgnoreCase(plan.mode())) {
            executeExportArtifact(jobId, job, plan, tableItemById, startedAt, actor, ownerUserId, opKey);
            return;
        }
        executeTargetLoad(jobId, job, plan, tableItemById, seqItemByName, progItemByKey, startedAt, actor, opKey);
    }

    private void executeExportArtifact(
            long jobId,
            MigrationJob job,
            MigrationPlan plan,
            Map<String, MigrationItem> tableItemById,
            Instant startedAt,
            String actor,
            Long ownerUserId,
            String opKey
    ) {
        int totalItems = plan.tables().size();
        AtomicInteger completed = new AtomicInteger(0);
        AtomicInteger failed = new AtomicInteger(0);
        AtomicLong migratedRows = new AtomicLong(0);
        AtomicLong migratedBytes = new AtomicLong(0);
        AtomicLong lastJobUpdate = new AtomicLong(0);

        DbConnection sourceConnMeta = connections.require(job.sourceConnectionId());
        DatabaseDialect sourceJdbcDialect = dialectRegistry.dialectFor(sourceConnMeta);
        DbTargetDialect sourceDialect = plan.sourceDialect();
        String sourceSchema = plan.sourceSchema().sourceSchema();
        String snapshotToken = plan.sourceSchema().snapshotToken();

        int poolSize = totalItems == 0 ? 0 : Math.min(effectiveConcurrency(plan.options(), sourceDialect), totalItems);
        boolean useParallelWriter = poolSize > 1;
        jobs.updateProgress(jobId, "RUNNING", "EXPORTING_ARTIFACT", totalItems, 0, 0,
                job.totalRows(), 0, 0,
                "正在流式导出标准制品（.mddpkg，并发度 " + Math.max(1, poolSize) + "）…", startedAt, null);

        Path dir = Path.of(properties.getMigration().getDirectory()).toAbsolutePath().normalize();
        String fileName = buildArtifactFileName(sourceConnMeta.name(), sourceSchema, plan.options().artifactName());
        Path outputPath = dir.resolve(fileName);

        try (MddPackageWriter writer = new MddPackageWriter(
                outputPath, objectMapper, plan.sourceSchema(), "0.10.2",
                properties.getMigration().getPartMaxBytes(), properties.getMigration().getPartMaxRows())) {

            if (poolSize > 0) {
                // 大表优先（LPT 调度），避免单张百 GB 巨表拖尾导致其余线程空转
                List<TableExecutionPlan> sortedTables = new ArrayList<>(plan.tables());
                sortedTables.sort(Comparator.comparingLong(MigrationOrchestrator::estimatedTableRows).reversed());
                ConcurrentLinkedQueue<TableExecutionPlan> queue = new ConcurrentLinkedQueue<>(sortedTables);

                AtomicBoolean stopRequested = new AtomicBoolean(false);
                AtomicReference<Throwable> firstFailure = new AtomicReference<>();

                ExecutorService exportPool = Executors.newFixedThreadPool(poolSize, r -> {
                    Thread t = new Thread(r, "dbadmin-mig-export-" + jobId);
                    t.setDaemon(true);
                    return t;
                });
                List<Future<?>> futures = new ArrayList<>(poolSize);
                for (int w = 0; w < poolSize; w++) {
                    futures.add(exportPool.submit(() -> {
                        try (Connection sourceConn = openScoped(sourceConnMeta.id(), sourceSchema);
                             ReadOnlyQueryScope ignored = ReadOnlyQueryScope.begin(sourceConn, true)) {
                            TableExecutionPlan tp;
                            while (!stopRequested.get() && (tp = queue.poll()) != null) {
                                checkCancelled(jobId, opKey);
                                MigrationItem item = tableItemById.get(tp.sourceTable().tableId());
                                Instant itemStart = Instant.now();
                                long[] prevRows = {0L};
                                long[] prevBytes = {0L};
                                final String tableName = tp.sourceTable().name();
                                maybeUpdateJobProgress(lastJobUpdate, jobId, "EXPORTING_TABLE:" + tableName,
                                        totalItems, completed.get(), failed.get(), job.totalRows(),
                                        migratedRows.get(), migratedBytes.get(),
                                        "正在并行导出（" + poolSize + " 线程）：已完成 " + completed.get() + "/" + totalItems
                                                + " 表，当前 " + tableName + "…", startedAt);

                                JdbcDataCopier.CopyStats stats = dataCopier.exportTableToArtifact(
                                        sourceConn, sourceJdbcDialect, sourceDialect, sourceSchema,
                                        tp.sourceTable(), snapshotToken, plan.options(), writer, useParallelWriter,
                                        () -> stopRequested.get() || isCancelled(jobId, opKey),
                                        (rows, bytes) -> {
                                            long dr = rows - prevRows[0];
                                            long db = bytes - prevBytes[0];
                                            prevRows[0] = rows;
                                            prevBytes[0] = bytes;
                                            long curRows = migratedRows.addAndGet(dr);
                                            long curBytes = migratedBytes.addAndGet(db);
                                            items.updateProgress(item.id(), "DATA_RUNNING", rows, rows, bytes, 0, 0, null, null, itemStart, null);
                                            maybeUpdateJobProgress(lastJobUpdate, jobId, "EXPORTING_TABLE:" + tableName,
                                                    totalItems, completed.get(), failed.get(), job.totalRows(),
                                                    curRows, curBytes,
                                                    "正在并行导出（" + poolSize + " 线程）：" + completed.get() + "/" + totalItems
                                                            + " 表，当前 " + tableName + "（" + rows + " 行）…", startedAt);
                                        }
                                );
                                long remRows = stats.rows() - prevRows[0];
                                long remBytes = stats.bytes() - prevBytes[0];
                                long curRows = remRows != 0 ? migratedRows.addAndGet(remRows) : migratedRows.get();
                                long curBytes = remBytes != 0 ? migratedBytes.addAndGet(remBytes) : migratedBytes.get();
                                int done = completed.incrementAndGet();
                                int partFlag = stats.rows() > 0 ? 1 : 0;
                                items.updateProgress(item.id(), "DONE", stats.rows(), stats.rows(), stats.bytes(),
                                        partFlag, partFlag, null, null, itemStart, Instant.now());
                                maybeUpdateJobProgress(lastJobUpdate, jobId, "EXPORTING_ARTIFACT",
                                        totalItems, done, failed.get(), job.totalRows(),
                                        curRows, curBytes,
                                        "正在并行导出（" + poolSize + " 线程）：已完成 " + done + "/" + totalItems + " 表…", startedAt);
                            }
                        } catch (InterruptedException cancelled) {
                            stopRequested.set(true);
                            firstFailure.compareAndSet(null, cancelled);
                            Thread.currentThread().interrupt();
                        } catch (Throwable error) {
                            stopRequested.set(true);
                            failed.incrementAndGet();
                            firstFailure.compareAndSet(null, error);
                        }
                    }));
                }
                exportPool.shutdown();
                for (Future<?> f : futures) {
                    try {
                        f.get();
                    } catch (Exception ignored) {
                    }
                }
                if (isCancelled(jobId, opKey) || firstFailure.get() instanceof InterruptedException) {
                    throw new InterruptedException("导出任务已取消。");
                }
                if (firstFailure.get() != null) {
                    Throwable err = firstFailure.get();
                    if (err instanceof Exception ex) throw ex;
                    throw new RuntimeException(err);
                }
            }

            MddPackageWriter.WriteResult result = writer.finish();
            Long storageProfileId = plan.options().storageProfileId();
            String remotePath = null;
            if (storageProfileId != null) {
                jobs.updateProgress(jobId, "RUNNING", "UPLOADING_ARTIFACT", totalItems, completed.get(), failed.get(),
                        migratedRows.get(), migratedRows.get(), migratedBytes.get(),
                        "正在将标准制品上传至远端文件服务…", startedAt, null);
                StorageProfile profile = storageRegistry.requireProfile(storageProfileId);
                remotePath = "migrations/" + fileName;
                storageRegistry.upload(profile, result.path(), remotePath, uploaded -> { });
            }

            Instant expires = Instant.now().plus(Math.max(1, properties.getMigration().getArtifactTtlHours()), ChronoUnit.HOURS);
            long artifactId = artifacts.insert(new MigrationArtifact(
                    0, fileName, result.path().toAbsolutePath().toString(),
                    storageProfileId, remotePath, result.fileSize(), result.fileSha256(),
                    sourceDialect.code(), plan.sourceSchema().sourceVersion(), sourceSchema,
                    MddPackageFormat.FORMAT_VERSION, result.manifest().tableCount(),
                    result.manifest().totalRows(), result.manifest().totalRawBytes(),
                    objectMapper.writeValueAsString(result.manifest()), ownerUserId, Instant.now(), expires
            ));
            jobs.updateOutputArtifactId(jobId, artifactId);
            jobSourceChecksums.put(jobId, result.checksums());
            jobs.updateProgress(jobId, "SUCCESS", "COMPLETED", totalItems, completed.get(), 0,
                    result.manifest().totalRows(), result.manifest().totalRows(), result.fileSize(),
                    "标准制品导出完成：" + fileName + "（共 " + result.manifest().tableCount() + " 张表，" + result.manifest().totalRows() + " 行）",
                    startedAt, Instant.now());
            audit.onConnection(actor, "MIGRATION_EXPORT_SUCCESS", sourceConnMeta.id(), "artifact:" + fileName,
                    "job=" + jobId + "; tables=" + result.manifest().tableCount() + "; rows=" + result.manifest().totalRows());
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt();
            jobs.updateProgress(jobId, "CANCELLED", "CANCELLED", totalItems, completed.get(), failed.get(),
                    job.totalRows(), migratedRows.get(), migratedBytes.get(), "导出任务已取消。", startedAt, Instant.now());
            audit.onConnection(actor, "MIGRATION_CANCELLED", sourceConnMeta.id(), "job:" + jobId, "mode=EXPORT_ARTIFACT");
        } catch (Exception error) {
            log.warn("迁移导出任务 {} 失败: {}", jobId, error.getMessage());
            jobs.updateProgress(jobId, "FAILED", "FAILED", totalItems, completed.get(), Math.max(1, failed.get()),
                    job.totalRows(), migratedRows.get(), migratedBytes.get(),
                    "导出失败：" + rootMessage(error), startedAt, Instant.now());
            audit.onConnection(actor, "MIGRATION_FAILED", sourceConnMeta.id(), "job:" + jobId, rootMessage(error));
        }
    }

    private void executeTargetLoad(
            long jobId,
            MigrationJob job,
            MigrationPlan plan,
            Map<String, MigrationItem> tableItemById,
            Map<String, MigrationItem> seqItemByName,
            Map<String, MigrationItem> progItemByKey,
            Instant startedAt,
            String actor,
            String opKey
    ) {
        boolean isImport = "IMPORT_ARTIFACT".equalsIgnoreCase(plan.mode());
        DbConnection targetConnMeta = connections.require(job.targetConnectionId());
        DatabaseDialect targetJdbcDialect = dialectRegistry.dialectFor(targetConnMeta);
        DbTargetDialect targetDialect = plan.targetDialect();
        String targetSchema = plan.targetSchema();
        MigrationOptions options = plan.options();

        DbConnection sourceConnMeta = isImport ? null : connections.require(job.sourceConnectionId());
        DatabaseDialect sourceJdbcDialect = isImport ? null : dialectRegistry.dialectFor(sourceConnMeta);

        int totalItems = tableItemById.size() + seqItemByName.size() + progItemByKey.size();
        AtomicInteger completedItems = new AtomicInteger(0);
        AtomicInteger failedItems = new AtomicInteger(0);
        AtomicLong migratedRows = new AtomicLong(0);
        AtomicLong migratedBytes = new AtomicLong(0);
        Map<String, TableChecksum> baselineChecksums = jobSourceChecksums.computeIfAbsent(jobId, k -> new ConcurrentHashMap<>());

        // 打开制品读取器（如为导入模式）
        MddPackageReader artifactReader = null;
        Map<String, ArtifactTableEntry> artifactTableEntries = new LinkedHashMap<>();
        try {
            if (isImport) {
                MigrationArtifact artifact = artifacts.findById(job.sourceArtifactId())
                        .orElseThrow(() -> new IllegalArgumentException("标准制品不存在：" + job.sourceArtifactId()));
                artifactReader = new MddPackageReader(Path.of(artifact.filePath()), objectMapper);
                var manifest = artifactReader.readManifest();
                for (ArtifactTableEntry ate : manifest.tables()) {
                    artifactTableEntries.put(ate.tableId(), ate);
                }
                var checksums = artifactReader.readChecksums();
                if (checksums != null && checksums.tables() != null) {
                    baselineChecksums.putAll(checksums.tables());
                }
            }

            // Stage 0: 目标 Schema / 数据库不存在时自动创建（必须在 openScoped 切换命名空间之前用默认连接执行）
            SchemaInitResult schemaInit = ensureTargetSchemaExists(
                    jobId, targetConnMeta, targetJdbcDialect, targetDialect, targetSchema,
                    options, startedAt, totalItems, job.totalRows(), actor
            );

            // Stage 1: 建基础表 + 主键 + 注释（批量预加载已有表清单，万表场景省去万次 SELECT 1 探测）
            AtomicLong lastJobUpdate = new AtomicLong(0);
            jobs.updateProgress(jobId, "RUNNING", "STAGE_1_CREATE_TABLES", totalItems, 0, 0,
                    job.totalRows(), 0, 0, "阶段 1/6：创建目标基础表结构与注释…", startedAt, null);
            List<TableExecutionPlan> readyForData = new ArrayList<>();
            try (Connection ddlConn = openScoped(targetConnMeta.id(), targetSchema)) {
                Set<String> existingTablesLower = schemaInit.freshlyCreated()
                        ? Set.of()
                        : loadExistingTargetTables(ddlConn, targetJdbcDialect, targetDialect, schemaInit.resolvedSchema());
                int preparedCount = 0;
                int totalTables = plan.tables().size();
                for (TableExecutionPlan tp : plan.tables()) {
                    checkCancelled(jobId, opKey);
                    MigrationItem item = tableItemById.get(tp.sourceTable().tableId());
                    if (isAlreadyDone(item)) {
                        completedItems.incrementAndGet();
                        migratedRows.addAndGet(item.migratedRows());
                        migratedBytes.addAndGet(item.migratedBytes());
                        preparedCount++;
                        continue;
                    }
                    Instant itemStart = Instant.now();
                    try {
                        boolean skipped = prepareTargetTable(ddlConn, targetDialect, targetSchema, tp, item, options, isImport, existingTablesLower);
                        if (skipped) {
                            items.updateProgress(item.id(), "SKIPPED", item.sourceRows(), 0, 0, 0, 0, null,
                                    "目标表已存在，按 SKIP 策略跳过", itemStart, Instant.now());
                            completedItems.incrementAndGet();
                            preparedCount++;
                            continue;
                        }
                        items.updateProgress(item.id(), "DDL_DONE", item.sourceRows(), item.migratedRows(), item.migratedBytes(),
                                item.totalParts(), item.completedParts(), item.checkpointJson(), null, itemStart, null);
                        readyForData.add(tp);
                    } catch (Exception error) {
                        failedItems.incrementAndGet();
                        items.updateProgress(item.id(), "FAILED", item.sourceRows(), item.migratedRows(), item.migratedBytes(),
                                item.totalParts(), item.completedParts(), item.checkpointJson(),
                                "建表失败：" + rootMessage(error), itemStart, Instant.now());
                    }
                    preparedCount++;
                    maybeUpdateJobProgress(lastJobUpdate, jobId, "STAGE_1_CREATE_TABLES",
                            totalItems, completedItems.get(), failedItems.get(), job.totalRows(),
                            migratedRows.get(), migratedBytes.get(),
                            "阶段 1/6：已准备基础表 " + preparedCount + "/" + totalTables + "…", startedAt);
                }
            }

            // Stage 2: 受控并发流式搬运数据（制品导入走统一分片级并发 + 连接复用；在线直迁走工作窃取 + 长连接复用）
            List<TableExecutionPlan> dataSucceeded = Collections.synchronizedList(new ArrayList<>());
            if (options.dataEnabled() && !readyForData.isEmpty()) {
                int stage2Concurrency = isImport
                        ? effectiveConcurrency(options, targetDialect)
                        : effectiveConcurrency(options, plan.sourceDialect(), targetDialect);
                jobs.updateProgress(jobId, "RUNNING", "STAGE_2_LOAD_DATA", totalItems, completedItems.get(), failedItems.get(),
                        job.totalRows(), migratedRows.get(), migratedBytes.get(),
                        "阶段 2/6：并行流式迁移全量数据（并发度 " + stage2Concurrency + "）…", startedAt, null);
                if (isImport) {
                    loadPartsFromArtifactParallel(
                            jobId, readyForData, tableItemById, artifactReader, artifactTableEntries,
                            targetConnMeta, targetDialect, targetSchema, options, stage2Concurrency,
                            totalItems, job.totalRows(), completedItems, failedItems, migratedRows, migratedBytes,
                            lastJobUpdate, startedAt, opKey, dataSucceeded
                    );
                } else {
                    loadTablesDirectParallel(
                            jobId, readyForData, tableItemById,
                            sourceConnMeta, sourceJdbcDialect, plan.sourceDialect(),
                            plan.sourceSchema().sourceSchema(), plan.sourceSchema().snapshotToken(),
                            targetConnMeta, targetDialect, targetSchema, options, baselineChecksums, stage2Concurrency,
                            totalItems, job.totalRows(), completedItems, failedItems, migratedRows, migratedBytes,
                            lastJobUpdate, startedAt, opKey, dataSucceeded
                    );
                }
                checkCancelled(jobId, opKey);
            } else {
                dataSucceeded.addAll(readyForData);
            }

            // Stage 3: 数据灌完后统一补建二级索引、外键、CHECK 约束（3a 并行建索引/CHECK/对齐自增，3b 串行建外键避免 MDL 死锁）
            jobs.updateProgress(jobId, "RUNNING", "STAGE_3_INDEXES_CONSTRAINTS", totalItems, completedItems.get(), failedItems.get(),
                    job.totalRows(), migratedRows.get(), migratedBytes.get(),
                    "阶段 3/6：并行创建二级索引、约束与外键…", startedAt, null);
            Map<String, MigrationItem> latestTableItems = new LinkedHashMap<>();
            for (MigrationItem it : items.findByJobId(jobId)) {
                if ("TABLE".equals(it.itemKind())) latestTableItems.put(it.tableId(), it);
            }
            Map<String, List<String>> tablePostErrors = new ConcurrentHashMap<>();
            if (!dataSucceeded.isEmpty()) {
                runPostDataIndexesParallel(
                        jobId, dataSucceeded, targetConnMeta, targetDialect, targetSchema, options,
                        totalItems, job.totalRows(), completedItems, failedItems, migratedRows, migratedBytes,
                        lastJobUpdate, startedAt, opKey, tablePostErrors
                );
                checkCancelled(jobId, opKey);
            }

            try (Connection postConn = openScoped(targetConnMeta.id(), targetSchema)) {
                // Stage 3b: 串行下发外键并落盘各表最终状态（此时所有父表索引已就绪，且无跨表 MDL 死锁）
                for (TableExecutionPlan tp : dataSucceeded) {
                    checkCancelled(jobId, opKey);
                    String tableId = tp.sourceTable().tableId();
                    MigrationItem item = latestTableItems.getOrDefault(tableId, tableItemById.get(tableId));
                    for (String sql : tp.ddl().postDataStatements()) {
                        if (!isForeignKeyStatement(sql)) continue;
                        try (Statement st = postConn.createStatement()) {
                            st.setQueryTimeout(properties.getMigration().getStatementTimeoutSeconds());
                            st.execute(sql);
                        } catch (Exception error) {
                            tablePostErrors.computeIfAbsent(tableId, k -> Collections.synchronizedList(new ArrayList<>()))
                                    .add(rootMessage(error));
                        }
                    }
                    List<String> postErrors = tablePostErrors.getOrDefault(tableId, List.of());
                    if (postErrors.isEmpty()) {
                        int done = completedItems.incrementAndGet();
                        items.updateProgress(item.id(), "DONE", item.sourceRows(), item.migratedRows(), item.migratedBytes(),
                                item.totalParts(), item.completedParts(), item.checkpointJson(), null, item.startedAt(), Instant.now());
                        maybeUpdateJobProgress(lastJobUpdate, jobId, "STAGE_3_INDEXES_CONSTRAINTS",
                                totalItems, done, failedItems.get(), job.totalRows(),
                                migratedRows.get(), migratedBytes.get(),
                                "阶段 3/6：已完成索引与约束 " + done + "/" + totalItems + "…", startedAt);
                    } else {
                        failedItems.incrementAndGet();
                        items.updateProgress(item.id(), "FAILED", item.sourceRows(), item.migratedRows(), item.migratedBytes(),
                                item.totalParts(), item.completedParts(), item.checkpointJson(),
                                "数据已写入，但部分索引/约束创建失败：" + String.join("；", postErrors), item.startedAt(), Instant.now());
                    }
                }

                // Stage 4b: 创建独立序列
                if (options.sequencesEnabled() && !plan.sequences().isEmpty()) {
                    jobs.updateProgress(jobId, "RUNNING", "STAGE_4_SEQUENCES", totalItems, completedItems.get(), failedItems.get(),
                            job.totalRows(), migratedRows.get(), migratedBytes.get(),
                            "阶段 4/6：创建并同步独立序列…", startedAt, null);
                    for (SequenceExecutionPlan sp : plan.sequences()) {
                        checkCancelled(jobId, opKey);
                        MigrationItem item = seqItemByName.get(sp.sourceSequence().name());
                        if (item == null || isAlreadyDone(item)) continue;
                        if (sp.createSql() == null || sp.createSql().isBlank()) {
                            items.updateProgress(item.id(), "SKIPPED", 0, 0, 0, 0, 0, null, "目标库不支持独立 SEQUENCE", Instant.now(), Instant.now());
                            completedItems.incrementAndGet();
                            continue;
                        }
                        try (Statement st = postConn.createStatement()) {
                            st.execute(sp.createSql());
                            completedItems.incrementAndGet();
                            items.updateProgress(item.id(), "DONE", 0, 0, 0, 0, 0, null, null, Instant.now(), Instant.now());
                        } catch (Exception error) {
                            failedItems.incrementAndGet();
                            items.updateProgress(item.id(), "FAILED", 0, 0, 0, 0, 0, null,
                                    "序列创建失败：" + rootMessage(error), Instant.now(), Instant.now());
                        }
                    }
                }

                // Stage 5: 创建已启用的可编程对象（按拓扑序）
                if (options.programObjectsEnabled() && !plan.programObjects().isEmpty()) {
                    jobs.updateProgress(jobId, "RUNNING", "STAGE_5_PROGRAM_OBJECTS", totalItems, completedItems.get(), failedItems.get(),
                            job.totalRows(), migratedRows.get(), migratedBytes.get(),
                            "阶段 5/6：创建视图/触发器/存储过程/函数…", startedAt, null);
                    for (ProgramExecutionPlan pp : plan.programObjects()) {
                        checkCancelled(jobId, opKey);
                        MigrationItem item = progItemByKey.get(pp.key());
                        if (item == null || isAlreadyDone(item)) continue;
                        if (pp.targetSql() == null || pp.targetSql().isBlank()) {
                            items.updateProgress(item.id(), "SKIPPED", 0, 0, 0, 0, 0, null,
                                    "跨方言对象暂无可执行目标脚本（需人工审阅或 AI 转译后补充）", Instant.now(), Instant.now());
                            completedItems.incrementAndGet();
                            continue;
                        }
                        try (Statement st = postConn.createStatement()) {
                            st.execute(pp.targetSql());
                            var compErrors = CompilationDiagnostics.read(postConn, targetJdbcDialect, pp.targetSql(), targetSchema, 10);
                            if (!compErrors.isEmpty()) {
                                String msg = compErrors.stream()
                                        .map(e -> "L" + e.line() + ":" + e.text())
                                        .limit(5)
                                        .reduce((a, b) -> a + "；" + b).orElse("编译存在错误");
                                failedItems.incrementAndGet();
                                items.updateProgress(item.id(), "FAILED", 0, 0, 0, 0, 0, null,
                                        "对象已创建但存在编译错误：" + msg, Instant.now(), Instant.now());
                            } else {
                                completedItems.incrementAndGet();
                                items.updateProgress(item.id(), "DONE", 0, 0, 0, 0, 0, null, null, Instant.now(), Instant.now());
                            }
                        } catch (Exception error) {
                            failedItems.incrementAndGet();
                            items.updateProgress(item.id(), "FAILED", 0, 0, 0, 0, 0, null,
                                    "对象创建失败：" + rootMessage(error), Instant.now(), Instant.now());
                        }
                    }
                }
            }

            // 刷新目标连接元数据缓存，保证资源树立刻看见新表
            metadataCache.evictConnection(targetConnMeta.id());

            // Stage 6: 自动一致性校验
            if (options.verifyAfterMigrationEnabled() && options.dataEnabled() && !dataSucceeded.isEmpty()) {
                jobs.updateProgress(jobId, "RUNNING", "STAGE_6_VERIFYING", totalItems, completedItems.get(), failedItems.get(),
                        migratedRows.get(), migratedRows.get(), migratedBytes.get(),
                        "阶段 6/6：正在执行 " + options.verifyLevel() + " 数据一致性校验…", startedAt, null);
                runVerificationInternal(jobId, plan, targetConnMeta, targetJdbcDialect, baselineChecksums,
                        options.verifyLevel(), List.of(), opKey);
            }

            String finalStatus = failedItems.get() > 0 ? "PARTIAL_FAILED" : "SUCCESS";
            String summaryMsg = failedItems.get() > 0
                    ? "迁移部分完成：成功 " + completedItems.get() + " 项，失败 " + failedItems.get() + " 项（可点击「失败项重试」断点续传）"
                    : "迁移完成：共 " + completedItems.get() + " 项，" + migratedRows.get() + " 行数据全部写入";
            jobs.updateProgress(jobId, finalStatus, "COMPLETED", totalItems, completedItems.get(), failedItems.get(),
                    migratedRows.get(), migratedRows.get(), migratedBytes.get(), summaryMsg, startedAt, Instant.now());
            audit.onConnection(actor, "MIGRATION_COMPLETE", targetConnMeta.id(), "job:" + jobId,
                    "mode=" + plan.mode() + "; status=" + finalStatus + "; rows=" + migratedRows.get());
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt();
            jobs.updateProgress(jobId, "CANCELLED", "CANCELLED", totalItems, completedItems.get(), failedItems.get(),
                    job.totalRows(), migratedRows.get(), migratedBytes.get(), "迁移任务已取消（已完成表可断点续传）。", startedAt, Instant.now());
            audit.onConnection(actor, "MIGRATION_CANCELLED", targetConnMeta.id(), "job:" + jobId, "mode=" + plan.mode());
        } catch (Exception error) {
            log.warn("迁移任务 {} 失败: {}", jobId, error.getMessage());
            jobs.updateProgress(jobId, "FAILED", "FAILED", totalItems, completedItems.get(), Math.max(1, failedItems.get()),
                    job.totalRows(), migratedRows.get(), migratedBytes.get(),
                    "迁移失败：" + rootMessage(error), startedAt, Instant.now());
            audit.onConnection(actor, "MIGRATION_FAILED", targetConnMeta.id(), "job:" + jobId, rootMessage(error));
        } finally {
            if (artifactReader != null) {
                try { artifactReader.close(); } catch (Exception ignored) { }
            }
        }
    }

    /** 手动或自动执行 L1/L2/L3 一致性校验并落盘报告（并发工作窃取 + 连接复用）。 */
    public MigrationVerificationReport runVerificationInternal(
            long jobId,
            MigrationPlan plan,
            DbConnection targetConnMeta,
            DatabaseDialect targetJdbcDialect,
            Map<String, TableChecksum> baselineChecksums,
            String verifyLevel,
            List<String> filterTables,
            String opKey
    ) throws Exception {
        String level = verifyLevel == null || verifyLevel.isBlank() ? "L3_FULL_HASH" : verifyLevel.toUpperCase(Locale.ROOT);
        boolean isImport = "IMPORT_ARTIFACT".equalsIgnoreCase(plan.mode());
        List<MigrationItem> jobItems = items.findByJobId(jobId);
        Map<String, MigrationItem> itemByTableId = new LinkedHashMap<>();
        for (MigrationItem it : jobItems) {
            if ("TABLE".equals(it.itemKind())) itemByTableId.put(it.tableId(), it);
        }

        Set<String> filter = new LinkedHashSet<>();
        if (filterTables != null) {
            for (String t : filterTables) if (t != null && !t.isBlank()) filter.add(t.trim().toLowerCase(Locale.ROOT));
        }

        List<TableExecutionPlan> targetTables = new ArrayList<>();
        for (TableExecutionPlan tp : plan.tables()) {
            if (!filter.isEmpty() && !filter.contains(tp.sourceTable().name().toLowerCase(Locale.ROOT))) continue;
            targetTables.add(tp);
        }

        List<String> notes = new ArrayList<>();
        if (isImport) {
            notes.add("离线校验模式：以标准制品 (.mddpkg) 内嵌的 verify/checksums.json 作为基线进行全量核验。");
        } else {
            notes.add("在线直迁校验模式：对比源表与目标表数据完整性（含全部 BLOB/CLOB 字节摘要）。");
        }

        List<TableVerificationResult> results;
        if (targetTables.isEmpty()) {
            results = List.of();
        } else {
            boolean needsSourceRescan = false;
            if (!isImport) {
                for (TableExecutionPlan tp : targetTables) {
                    if (baselineChecksums == null || baselineChecksums.get(tp.sourceTable().tableId()) == null) {
                        needsSourceRescan = true;
                        break;
                    }
                }
            }
            MigrationJob rescanJob = needsSourceRescan ? jobs.findById(jobId).orElseThrow() : null;
            DbConnection sourceMeta = needsSourceRescan ? connections.require(rescanJob.sourceConnectionId()) : null;
            DatabaseDialect sourceJdbcDialect = needsSourceRescan ? dialectRegistry.dialectFor(sourceMeta) : null;
            int rescanFspCap = Math.min(plan.sourceDialect().maxTemporalFsp(), plan.targetDialect().maxTemporalFsp());

            TableVerificationResult[] orderedResults = new TableVerificationResult[targetTables.size()];
            List<VerifyWorkUnit> workUnits = new ArrayList<>(targetTables.size());
            for (int i = 0; i < targetTables.size(); i++) {
                workUnits.add(new VerifyWorkUnit(i, targetTables.get(i)));
            }
            // 大表优先启动校验，避免单张大表最后才开始拖长整体耗时
            workUnits.sort(Comparator.comparingLong((VerifyWorkUnit u) -> estimatedTableRows(u.plan())).reversed());
            ConcurrentLinkedQueue<VerifyWorkUnit> queue = new ConcurrentLinkedQueue<>(workUnits);

            int poolSize = Math.min(
                    effectiveConcurrency(plan.options(), plan.targetDialect(), isImport ? null : plan.sourceDialect()),
                    targetTables.size()
            );
            AtomicBoolean stopRequested = new AtomicBoolean(false);
            AtomicReference<Throwable> firstFailure = new AtomicReference<>();

            ExecutorService verifyPool = Executors.newFixedThreadPool(poolSize, r -> {
                Thread t = new Thread(r, "dbadmin-mig-verify-" + jobId);
                t.setDaemon(true);
                return t;
            });
            List<Future<?>> futures = new ArrayList<>(poolSize);
            for (int w = 0; w < poolSize; w++) {
                futures.add(verifyPool.submit(() -> {
                    Connection srcConn = null;
                    ReadOnlyQueryScope srcScope = null;
                    try (Connection targetConn = openScoped(targetConnMeta.id(), plan.targetSchema())) {
                        VerifyWorkUnit unit;
                        while (!stopRequested.get() && (unit = queue.poll()) != null) {
                            if (opKey != null && isCancelled(jobId, opKey)) {
                                throw new InterruptedException("数据校验已取消。");
                            }
                            TableExecutionPlan tp = unit.plan();
                            TableChecksum expected = baselineChecksums == null ? null : baselineChecksums.get(tp.sourceTable().tableId());
                            if (expected == null && !isImport && sourceMeta != null) {
                                if (srcConn == null) {
                                    srcConn = openScoped(sourceMeta.id(), plan.sourceSchema().sourceSchema());
                                    srcScope = ReadOnlyQueryScope.begin(srcConn, true);
                                }
                                expected = verifier.scanSourceChecksum(
                                        srcConn, sourceJdbcDialect, plan.sourceDialect(), plan.sourceSchema().sourceSchema(),
                                        tp, rescanJob.snapshotToken(), rescanFspCap, plan.options().timezonePolicy(),
                                        () -> stopRequested.get() || (opKey != null && isCancelled(jobId, opKey))
                                );
                            }
                            TableVerificationResult tableResult = verifier.verifyTable(
                                    tp, expected, targetConn, targetJdbcDialect, plan.targetDialect(),
                                    plan.targetSchema(), level, plan.options(),
                                    () -> stopRequested.get() || (opKey != null && isCancelled(jobId, opKey))
                            );
                            orderedResults[unit.index()] = tableResult;
                            MigrationItem item = itemByTableId.get(tp.sourceTable().tableId());
                            if (item != null) {
                                items.updateVerification(item.id(), tableResult.status(), objectMapper.writeValueAsString(tableResult));
                            }
                        }
                    } catch (InterruptedException cancelled) {
                        stopRequested.set(true);
                        firstFailure.compareAndSet(null, cancelled);
                        Thread.currentThread().interrupt();
                    } catch (Throwable error) {
                        stopRequested.set(true);
                        firstFailure.compareAndSet(null, error);
                    } finally {
                        closeQuietly(srcScope);
                        closeQuietly(srcConn);
                    }
                }));
            }
            verifyPool.shutdown();
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (Exception ignored) {
                }
            }
            if ((opKey != null && isCancelled(jobId, opKey)) || firstFailure.get() instanceof InterruptedException) {
                throw new InterruptedException("数据校验已取消。");
            }
            if (firstFailure.get() != null) {
                Throwable err = firstFailure.get();
                if (err instanceof Exception ex) throw ex;
                throw new RuntimeException(err);
            }
            results = Arrays.asList(orderedResults);
        }

        MigrationVerificationReport report = verifier.assembleReport(
                jobId, isImport ? "ARTIFACT_OFFLINE" : "ONLINE_SOURCE", level, results, notes
        );
        jobs.updateVerification(jobId, report.overallStatus(), objectMapper.writeValueAsString(report));
        return report;
    }

    private boolean prepareTargetTable(
            Connection ddlConn,
            DbTargetDialect targetDialect,
            String targetSchema,
            TableExecutionPlan tp,
            MigrationItem item,
            MigrationOptions options,
            boolean isImport,
            Set<String> existingTablesLower
    ) throws Exception {
        // 断点重试时若本表已经建过表并跑过部分分片，不重建表直接续传
        if (isImport && item.completedParts() > 0) {
            return false;
        }
        boolean mightExist = existingTablesLower == null
                || existingTablesLower.contains(tp.targetTableName().toLowerCase(Locale.ROOT));
        boolean exists = mightExist && targetTableExists(ddlConn, targetDialect, targetSchema, tp.targetTableName());
        if (exists) {
            if ("SKIP".equalsIgnoreCase(options.conflictMode())) {
                return true;
            }
            if ("FAIL".equalsIgnoreCase(options.conflictMode())) {
                if (item.completedParts() > 0) return false; // 分片断点重试复用既有表
                throw new IllegalStateException("目标表 " + targetDialect.qualifiedName(targetSchema, tp.targetTableName())
                        + " 已存在（当前冲突策略为 FAIL）。");
            }
            if ("TRUNCATE_AND_LOAD".equalsIgnoreCase(options.conflictMode())) {
                if (item.completedParts() == 0) {
                    try (Statement st = ddlConn.createStatement()) {
                        st.execute(ddlEmitter.emitTruncateTable(targetDialect, targetSchema, tp.targetTableName()));
                    }
                }
                return false;
            }
            if ("DROP_AND_RECREATE".equalsIgnoreCase(options.conflictMode())) {
                try (Statement st = ddlConn.createStatement()) {
                    st.execute(ddlEmitter.emitDropTable(targetDialect, targetSchema, tp.targetTableName()));
                }
            }
        }
        for (String sql : tp.ddl().createTableStatements()) {
            try (Statement st = ddlConn.createStatement()) {
                st.setQueryTimeout(properties.getMigration().getStatementTimeoutSeconds());
                st.execute(sql);
            }
        }
        return false;
    }

    /**
     * 统一分片级并发导入：同时实现「多表并发」与「百 GB 单张巨表多分片并行写入」，
     * 每条工作线程持有 1 条持久化目标连接复用跑完全部分片，0 行空表零连接开销瞬间放行。
     */
    private void loadPartsFromArtifactParallel(
            long jobId,
            List<TableExecutionPlan> readyForData,
            Map<String, MigrationItem> tableItemById,
            MddPackageReader reader,
            Map<String, ArtifactTableEntry> artifactTableEntries,
            DbConnection targetConnMeta,
            DbTargetDialect targetDialect,
            String targetSchema,
            MigrationOptions options,
            int concurrency,
            int totalItems,
            long jobTotalRows,
            AtomicInteger completedItems,
            AtomicInteger failedItems,
            AtomicLong jobMigratedRows,
            AtomicLong jobMigratedBytes,
            AtomicLong lastJobUpdate,
            Instant jobStart,
            String opKey,
            List<TableExecutionPlan> dataSucceeded
    ) {
        List<PartWorkUnit> pendingUnits = new ArrayList<>();
        List<TableImportState> activeStates = new ArrayList<>();

        for (TableExecutionPlan tp : readyForData) {
            MigrationItem item = tableItemById.get(tp.sourceTable().tableId());
            ArtifactTableEntry ate = artifactTableEntries.get(tp.sourceTable().tableId());
            Instant itemStart = item.startedAt() != null ? item.startedAt() : Instant.now();
            if (ate == null || ate.parts().isEmpty()) {
                items.updateProgress(item.id(), "DATA_DONE", 0, 0, 0, 0, 0, null, null, itemStart, null);
                dataSucceeded.add(tp);
                continue;
            }
            Set<Integer> completedPartSet = parseCompletedParts(item.checkpointJson());
            List<ArtifactPartEntry> remaining = new ArrayList<>();
            for (ArtifactPartEntry part : ate.parts()) {
                if (!completedPartSet.contains(part.partIndex())) {
                    remaining.add(part);
                }
            }
            if (remaining.isEmpty()) {
                items.updateProgress(item.id(), "DATA_DONE", ate.rowCount(), item.migratedRows(), item.migratedBytes(),
                        ate.parts().size(), completedPartSet.size(), encodeCompletedParts(completedPartSet), null, itemStart, null);
                dataSucceeded.add(tp);
                continue;
            }
            TableImportState state = new TableImportState(tp, item, ate, itemStart, completedPartSet, remaining.size());
            activeStates.add(state);
            for (ArtifactPartEntry part : remaining) {
                pendingUnits.add(new PartWorkUnit(state, part));
            }
        }

        if (pendingUnits.isEmpty()) return;

        // 按分片字节数降序调度，优先让多线程并行吃掉百 GB 大表分片
        pendingUnits.sort(Comparator.comparingLong((PartWorkUnit u) -> u.part().rawBytes()).reversed());
        ConcurrentLinkedQueue<PartWorkUnit> partQueue = new ConcurrentLinkedQueue<>(pendingUnits);
        int poolSize = Math.min(concurrency, pendingUnits.size());

        ExecutorService pool = Executors.newFixedThreadPool(poolSize, r -> {
            Thread t = new Thread(r, "dbadmin-mig-import-" + jobId);
            t.setDaemon(true);
            return t;
        });
        List<Future<?>> futures = new ArrayList<>(poolSize);
        for (int w = 0; w < poolSize; w++) {
            futures.add(pool.submit(() -> {
                Connection targetConn = null;
                try {
                    PartWorkUnit unit;
                    while ((unit = partQueue.poll()) != null) {
                        TableImportState state = unit.state();
                        ArtifactPartEntry part = unit.part();

                        if (isCancelled(jobId, opKey)) {
                            finishPartCancelled(state, failedItems);
                            break;
                        }
                        if (state.isAborted()) {
                            finishSkippedPart(state, failedItems);
                            continue;
                        }

                        long[] prevRows = {0L};
                        long[] prevBytes = {0L};
                        try {
                            if (targetConn == null || targetConn.isClosed()) {
                                targetConn = openScoped(targetConnMeta.id(), targetSchema);
                            }
                            JdbcDataCopier.CopyStats stats = dataCopier.importPartFromArtifact(
                                    reader, part, targetConn, targetDialect, targetSchema, state.tp, options,
                                    () -> isCancelled(jobId, opKey) || state.isAborted(),
                                    (rows, bytes) -> {
                                        long dr = rows - prevRows[0];
                                        long db = bytes - prevBytes[0];
                                        prevRows[0] = rows;
                                        prevBytes[0] = bytes;
                                        long tableLiveRows = state.liveRows.addAndGet(dr);
                                        long tableLiveBytes = state.liveBytes.addAndGet(db);
                                        long jobLiveRows = jobMigratedRows.addAndGet(dr);
                                        long jobLiveBytes = jobMigratedBytes.addAndGet(db);
                                        maybeUpdateTableImportProgress(state, tableLiveRows, tableLiveBytes);
                                        maybeUpdateJobProgress(lastJobUpdate, jobId, "STAGE_2_LOAD_DATA",
                                                totalItems, completedItems.get(), failedItems.get(), jobTotalRows,
                                                jobLiveRows, jobLiveBytes,
                                                "正在并行导入（" + poolSize + " 线程）：表 " + state.tp.targetTableName()
                                                        + "（分片 " + part.partIndex() + "/" + state.totalParts + "）…",
                                                jobStart);
                                    }
                            );
                            long remRows = stats.rows() - prevRows[0];
                            long remBytes = stats.bytes() - prevBytes[0];
                            if (remRows != 0) {
                                state.liveRows.addAndGet(remRows);
                                jobMigratedRows.addAndGet(remRows);
                            }
                            if (remBytes != 0) {
                                state.liveBytes.addAndGet(remBytes);
                                jobMigratedBytes.addAndGet(remBytes);
                            }
                            finishPartSuccess(state, part.partIndex(), stats.rows(), stats.bytes(), failedItems, dataSucceeded);
                        } catch (InterruptedException cancelled) {
                            rollbackLiveDelta(state, jobMigratedRows, jobMigratedBytes, prevRows[0], prevBytes[0]);
                            Thread.currentThread().interrupt();
                            finishPartCancelled(state, failedItems);
                            break;
                        } catch (Exception error) {
                            rollbackLiveDelta(state, jobMigratedRows, jobMigratedBytes, prevRows[0], prevBytes[0]);
                            if (error instanceof SQLException) {
                                closeQuietly(targetConn);
                                targetConn = null;
                            }
                            log.warn("迁移任务 {} 表 {} 分片 {} 写入失败: {}",
                                    jobId, state.tp.sourceTable().name(), part.partIndex(), error.getMessage());
                            finishPartFailed(state, rootMessage(error), failedItems);
                        }
                    }
                } finally {
                    closeQuietly(targetConn);
                }
            }));
        }
        pool.shutdown();
        for (Future<?> f : futures) {
            try {
                f.get();
            } catch (Exception ignored) {
            }
        }
        // 任务被中途取消时，为仍残留未调度分片的表落盘 CANCELLED 与已完成分片检查点
        for (TableImportState state : activeStates) {
            synchronized (state) {
                if (state.remainingParts > 0 && !state.terminalRecorded && (state.cancelled || isCancelled(jobId, opKey))) {
                    state.terminalRecorded = true;
                    items.updateProgress(state.item.id(), "CANCELLED", state.artifactTable.rowCount(),
                            state.committedRows, state.committedBytes, state.totalParts, state.completedParts.size(),
                            encodeCompletedParts(state.completedParts), "任务已取消", state.itemStart, Instant.now());
                }
            }
        }
    }

    /** 在线直迁阶段 2：工作窃取 + 每条线程复用 1 条源连接与 1 条目标连接，避免万表场景连接池抖动。 */
    private void loadTablesDirectParallel(
            long jobId,
            List<TableExecutionPlan> readyForData,
            Map<String, MigrationItem> tableItemById,
            DbConnection sourceConnMeta,
            DatabaseDialect sourceJdbcDialect,
            DbTargetDialect sourceDialect,
            String sourceSchema,
            String snapshotToken,
            DbConnection targetConnMeta,
            DbTargetDialect targetDialect,
            String targetSchema,
            MigrationOptions options,
            Map<String, TableChecksum> baselineChecksums,
            int concurrency,
            int totalItems,
            long jobTotalRows,
            AtomicInteger completedItems,
            AtomicInteger failedItems,
            AtomicLong jobMigratedRows,
            AtomicLong jobMigratedBytes,
            AtomicLong lastJobUpdate,
            Instant jobStart,
            String opKey,
            List<TableExecutionPlan> dataSucceeded
    ) {
        List<TableExecutionPlan> sortedTables = new ArrayList<>(readyForData);
        sortedTables.sort(Comparator.comparingLong(MigrationOrchestrator::estimatedTableRows).reversed());
        ConcurrentLinkedQueue<TableExecutionPlan> tableQueue = new ConcurrentLinkedQueue<>(sortedTables);
        int poolSize = Math.min(concurrency, sortedTables.size());

        ExecutorService pool = Executors.newFixedThreadPool(poolSize, r -> {
            Thread t = new Thread(r, "dbadmin-mig-direct-" + jobId);
            t.setDaemon(true);
            return t;
        });
        List<Future<?>> futures = new ArrayList<>(poolSize);
        for (int w = 0; w < poolSize; w++) {
            futures.add(pool.submit(() -> {
                Connection sourceConn = null;
                ReadOnlyQueryScope sourceScope = null;
                Connection targetConn = null;
                try {
                    TableExecutionPlan tp;
                    while ((tp = tableQueue.poll()) != null) {
                        MigrationItem item = tableItemById.get(tp.sourceTable().tableId());
                        Instant itemStart = Instant.now();
                        long[] prevRows = {0L};
                        long[] prevBytes = {0L};
                        try {
                            checkCancelled(jobId, opKey);
                            if (sourceConn == null || sourceConn.isClosed()) {
                                closeQuietly(sourceScope);
                                closeQuietly(sourceConn);
                                sourceConn = openScoped(sourceConnMeta.id(), sourceSchema);
                                sourceScope = ReadOnlyQueryScope.begin(sourceConn, true);
                            }
                            if (targetConn == null || targetConn.isClosed()) {
                                closeQuietly(targetConn);
                                targetConn = openScoped(targetConnMeta.id(), targetSchema);
                            }
                            final String srcName = tp.sourceTable().name();
                            final String tgtName = tp.targetTableName();
                            JdbcDataCopier.CopyStats stats = dataCopier.copyTableDirect(
                                    sourceConn, sourceJdbcDialect, sourceDialect, sourceSchema,
                                    targetConn, targetDialect, targetSchema, tp, snapshotToken, options,
                                    () -> isCancelled(jobId, opKey),
                                    (rows, bytes) -> {
                                        long dr = rows - prevRows[0];
                                        long db = bytes - prevBytes[0];
                                        prevRows[0] = rows;
                                        prevBytes[0] = bytes;
                                        long curJobRows = jobMigratedRows.addAndGet(dr);
                                        long curJobBytes = jobMigratedBytes.addAndGet(db);
                                        items.updateProgress(item.id(), "DATA_RUNNING", rows, rows, bytes, 1, 0, null, null, itemStart, null);
                                        maybeUpdateJobProgress(lastJobUpdate, jobId, "STAGE_2_LOAD_DATA",
                                                totalItems, completedItems.get(), failedItems.get(), jobTotalRows,
                                                curJobRows, curJobBytes,
                                                "正在直迁表 " + srcName + " → " + tgtName + "（" + rows + " 行）…",
                                                jobStart);
                                    }
                            );
                            long remRows = stats.rows() - prevRows[0];
                            long remBytes = stats.bytes() - prevBytes[0];
                            if (remRows != 0) jobMigratedRows.addAndGet(remRows);
                            if (remBytes != 0) jobMigratedBytes.addAndGet(remBytes);
                            if (stats.sourceChecksum() != null) {
                                baselineChecksums.put(tp.sourceTable().tableId(), stats.sourceChecksum());
                            }
                            items.updateProgress(item.id(), "DATA_DONE", stats.rows(), stats.rows(), stats.bytes(), 1, 1, null, null, itemStart, null);
                            dataSucceeded.add(tp);
                        } catch (InterruptedException cancelled) {
                            if (prevRows[0] != 0) jobMigratedRows.addAndGet(-prevRows[0]);
                            if (prevBytes[0] != 0) jobMigratedBytes.addAndGet(-prevBytes[0]);
                            Thread.currentThread().interrupt();
                            items.updateProgress(item.id(), "CANCELLED", item.sourceRows(), item.migratedRows(), item.migratedBytes(),
                                    item.totalParts(), item.completedParts(), item.checkpointJson(), "任务已取消", itemStart, Instant.now());
                            break;
                        } catch (Exception error) {
                            if (prevRows[0] != 0) jobMigratedRows.addAndGet(-prevRows[0]);
                            if (prevBytes[0] != 0) jobMigratedBytes.addAndGet(-prevBytes[0]);
                            if (error instanceof SQLException) {
                                closeQuietly(sourceScope);
                                closeQuietly(sourceConn);
                                closeQuietly(targetConn);
                                sourceScope = null;
                                sourceConn = null;
                                targetConn = null;
                            }
                            failedItems.incrementAndGet();
                            log.warn("迁移任务 {} 表 {} 数据写入失败: {}", jobId, tp.sourceTable().name(), error.getMessage());
                            items.updateProgress(item.id(), "FAILED", item.sourceRows(), item.migratedRows(), item.migratedBytes(),
                                    item.totalParts(), item.completedParts(), item.checkpointJson(),
                                    "数据写入失败：" + rootMessage(error), itemStart, Instant.now());
                        }
                    }
                } finally {
                    closeQuietly(sourceScope);
                    closeQuietly(sourceConn);
                    closeQuietly(targetConn);
                }
            }));
        }
        pool.shutdown();
        for (Future<?> f : futures) {
            try {
                f.get();
            } catch (Exception ignored) {
            }
        }
    }

    /** 阶段 3a：并行构建二级索引、CHECK 约束并对齐自增列（每张表只操作自身对象，无跨表锁竞争）。 */
    private void runPostDataIndexesParallel(
            long jobId,
            List<TableExecutionPlan> dataSucceeded,
            DbConnection targetConnMeta,
            DbTargetDialect targetDialect,
            String targetSchema,
            MigrationOptions options,
            int totalItems,
            long jobTotalRows,
            AtomicInteger completedItems,
            AtomicInteger failedItems,
            AtomicLong migratedRows,
            AtomicLong migratedBytes,
            AtomicLong lastJobUpdate,
            Instant jobStart,
            String opKey,
            Map<String, List<String>> tablePostErrors
    ) {
        List<TableExecutionPlan> sorted = new ArrayList<>(dataSucceeded);
        sorted.sort(Comparator.comparingLong(MigrationOrchestrator::estimatedTableRows).reversed());
        ConcurrentLinkedQueue<TableExecutionPlan> queue = new ConcurrentLinkedQueue<>(sorted);
        int poolSize = Math.min(effectiveConcurrency(options, targetDialect), sorted.size());
        AtomicInteger indexedTables = new AtomicInteger(0);

        ExecutorService pool = Executors.newFixedThreadPool(poolSize, r -> {
            Thread t = new Thread(r, "dbadmin-mig-index-" + jobId);
            t.setDaemon(true);
            return t;
        });
        List<Future<?>> futures = new ArrayList<>(poolSize);
        for (int w = 0; w < poolSize; w++) {
            futures.add(pool.submit(() -> {
                Connection postConn = null;
                try {
                    TableExecutionPlan tp;
                    while ((tp = queue.poll()) != null) {
                        if (isCancelled(jobId, opKey)) break;
                        String tableId = tp.sourceTable().tableId();
                        boolean hasWork = false;
                        for (String sql : tp.ddl().postDataStatements()) {
                            if (!isForeignKeyStatement(sql)) {
                                hasWork = true;
                                break;
                            }
                        }
                        if (!hasWork) {
                            for (var mc : tp.columns()) {
                                if (mc.source().identity() != null) {
                                    hasWork = true;
                                    break;
                                }
                            }
                        }
                        if (hasWork) {
                            try {
                                if (postConn == null || postConn.isClosed()) {
                                    postConn = openScoped(targetConnMeta.id(), targetSchema);
                                }
                            } catch (Exception connErr) {
                                tablePostErrors.computeIfAbsent(tableId, k -> Collections.synchronizedList(new ArrayList<>()))
                                        .add(rootMessage(connErr));
                                continue;
                            }
                            for (String sql : tp.ddl().postDataStatements()) {
                                if (isForeignKeyStatement(sql)) continue;
                                if (isCancelled(jobId, opKey)) break;
                                try (Statement st = postConn.createStatement()) {
                                    st.setQueryTimeout(properties.getMigration().getStatementTimeoutSeconds());
                                    st.execute(sql);
                                } catch (Exception error) {
                                    tablePostErrors.computeIfAbsent(tableId, k -> Collections.synchronizedList(new ArrayList<>()))
                                            .add(rootMessage(error));
                                }
                            }
                            for (var mc : tp.columns()) {
                                if (mc.source().identity() != null) {
                                    syncIdentityColumn(postConn, targetDialect, targetSchema, tp.targetTableName(), mc.targetColumnName());
                                }
                            }
                        }
                        int doneIdx = indexedTables.incrementAndGet();
                        maybeUpdateJobProgress(lastJobUpdate, jobId, "STAGE_3_INDEXES_CONSTRAINTS",
                                totalItems, completedItems.get(), failedItems.get(), jobTotalRows,
                                migratedRows.get(), migratedBytes.get(),
                                "阶段 3/6：并行构建索引与自增对齐 " + doneIdx + "/" + sorted.size() + "…", jobStart);
                    }
                } finally {
                    closeQuietly(postConn);
                }
            }));
        }
        pool.shutdown();
        for (Future<?> f : futures) {
            try {
                f.get();
            } catch (Exception ignored) {
            }
        }
    }

    private void maybeUpdateTableImportProgress(TableImportState state, long liveRows, long liveBytes) {
        long now = System.nanoTime();
        long prev = state.lastItemUpdateNanos.get();
        if (now - prev >= JOB_PROGRESS_THROTTLE_NANOS && state.lastItemUpdateNanos.compareAndSet(prev, now)) {
            synchronized (state) {
                if (state.terminalRecorded) return;
                items.updateProgress(state.item.id(), "DATA_RUNNING", state.artifactTable.rowCount(),
                        liveRows, liveBytes, state.totalParts, state.completedParts.size(),
                        encodeCompletedParts(state.completedParts), null, state.itemStart, null);
            }
        }
    }

    private void finishPartSuccess(
            TableImportState state,
            int partIndex,
            long partRows,
            long partBytes,
            AtomicInteger failedItems,
            List<TableExecutionPlan> dataSucceeded
    ) {
        synchronized (state) {
            state.completedParts.add(partIndex);
            state.committedRows += partRows;
            state.committedBytes += partBytes;
            state.remainingParts--;
            if (state.remainingParts == 0) {
                if (!state.failed && !state.cancelled) {
                    state.terminalRecorded = true;
                    items.updateProgress(state.item.id(), "DATA_DONE", state.artifactTable.rowCount(),
                            state.committedRows, state.committedBytes, state.totalParts, state.completedParts.size(),
                            encodeCompletedParts(state.completedParts), null, state.itemStart, null);
                    dataSucceeded.add(state.tp);
                } else {
                    recordTableTerminalLocked(state, failedItems);
                }
            } else if (!state.failed && !state.cancelled) {
                // 每完成一个分片立刻持久化断点检查点
                items.updateProgress(state.item.id(), "DATA_RUNNING", state.artifactTable.rowCount(),
                        state.committedRows, state.committedBytes, state.totalParts, state.completedParts.size(),
                        encodeCompletedParts(state.completedParts), null, state.itemStart, null);
            }
        }
    }

    private void finishPartFailed(TableImportState state, String errorMessage, AtomicInteger failedItems) {
        synchronized (state) {
            state.failed = true;
            if (state.firstError == null) state.firstError = errorMessage;
            state.remainingParts--;
            if (state.remainingParts == 0) {
                recordTableTerminalLocked(state, failedItems);
            }
        }
    }

    private void finishPartCancelled(TableImportState state, AtomicInteger failedItems) {
        synchronized (state) {
            state.cancelled = true;
            state.remainingParts--;
            if (state.remainingParts == 0) {
                recordTableTerminalLocked(state, failedItems);
            }
        }
    }

    private void finishSkippedPart(TableImportState state, AtomicInteger failedItems) {
        synchronized (state) {
            state.remainingParts--;
            if (state.remainingParts == 0) {
                recordTableTerminalLocked(state, failedItems);
            }
        }
    }

    private void recordTableTerminalLocked(TableImportState state, AtomicInteger failedItems) {
        if (state.terminalRecorded) return;
        state.terminalRecorded = true;
        if (state.failed) {
            failedItems.incrementAndGet();
            items.updateProgress(state.item.id(), "FAILED", state.artifactTable.rowCount(),
                    state.committedRows, state.committedBytes, state.totalParts, state.completedParts.size(),
                    encodeCompletedParts(state.completedParts), "数据写入失败：" + state.firstError,
                    state.itemStart, Instant.now());
        } else if (state.cancelled) {
            items.updateProgress(state.item.id(), "CANCELLED", state.artifactTable.rowCount(),
                    state.committedRows, state.committedBytes, state.totalParts, state.completedParts.size(),
                    encodeCompletedParts(state.completedParts), "任务已取消",
                    state.itemStart, Instant.now());
        }
    }

    private static void rollbackLiveDelta(
            TableImportState state,
            AtomicLong jobMigratedRows,
            AtomicLong jobMigratedBytes,
            long partRowsDelta,
            long partBytesDelta
    ) {
        if (partRowsDelta != 0) {
            state.liveRows.addAndGet(-partRowsDelta);
            jobMigratedRows.addAndGet(-partRowsDelta);
        }
        if (partBytesDelta != 0) {
            state.liveBytes.addAndGet(-partBytesDelta);
            jobMigratedBytes.addAndGet(-partBytesDelta);
        }
    }

    /** 一次性批量预加载目标 Schema/库下的已有表名集合（小写）；若驱动不支持则返回 null 回退到逐表探测。 */
    private Set<String> loadExistingTargetTables(
            Connection conn,
            DatabaseDialect targetJdbcDialect,
            DbTargetDialect targetDialect,
            String resolvedSchema
    ) {
        try {
            DatabaseMetaData meta = conn.getMetaData();
            String catalog = null;
            String schemaPattern = null;
            boolean isCatalog = targetJdbcDialect.namespaceKind() == DatabaseDialect.NamespaceKind.CATALOG;
            if (targetDialect != DbTargetDialect.SQLITE && resolvedSchema != null && !resolvedSchema.isBlank()) {
                if (isCatalog) catalog = resolvedSchema;
                else schemaPattern = resolvedSchema;
            }
            String[] tableTypes = targetDialect.isOracleFamily()
                    ? new String[]{"TABLE"}
                    : new String[]{"TABLE", "BASE TABLE"};
            Set<String> existing = new HashSet<>();
            try (ResultSet rs = meta.getTables(catalog, schemaPattern, "%", tableTypes)) {
                while (rs.next()) {
                    if (schemaPattern != null) {
                        String s = rs.getString("TABLE_SCHEM");
                        if (s != null && !s.equalsIgnoreCase(schemaPattern)) continue;
                    }
                    if (catalog != null) {
                        String c = rs.getString("TABLE_CAT");
                        if (c != null && !c.equalsIgnoreCase(catalog)) continue;
                    }
                    String tableName = rs.getString("TABLE_NAME");
                    if (tableName != null && !tableName.isBlank()) {
                        existing.add(tableName.toLowerCase(Locale.ROOT));
                    }
                }
            }
            return existing;
        } catch (Exception error) {
            log.debug("批量预加载目标已有表清单失败，回退逐表探测: {}", rootMessage(error));
            return null;
        }
    }

    private void syncIdentityColumn(Connection conn, DbTargetDialect target, String schema, String table, String col) {
        String maxSql = "SELECT MAX(" + target.quoteIdentifier(col) + ") FROM " + target.qualifiedName(schema, table);
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(maxSql)) {
            if (rs.next()) {
                long maxVal = rs.getLong(1);
                if (!rs.wasNull() && maxVal > 0) {
                    String syncSql = ddlEmitter.emitIdentitySyncSql(target, schema, table, col, maxVal + 1L);
                    if (syncSql != null) {
                        try (Statement syncSt = conn.createStatement()) {
                            syncSt.execute(syncSql);
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            // 部分只读系统列或低版本方言不支持重设自增，不阻断主流程
        }
    }

    private boolean targetTableExists(Connection conn, DbTargetDialect dialect, String schema, String tableName) {
        try {
            String sql = "SELECT 1 FROM " + dialect.qualifiedName(schema, tableName) + " WHERE 1 = 0";
            try (Statement st = conn.createStatement()) {
                st.setMaxRows(1);
                try (ResultSet ignored = st.executeQuery(sql)) {
                    return true;
                }
            }
        } catch (Exception ignored) {
            return false;
        }
    }

    private SchemaInitResult ensureTargetSchemaExists(
            long jobId,
            DbConnection targetConnMeta,
            DatabaseDialect targetJdbcDialect,
            DbTargetDialect targetDialect,
            String targetSchema,
            MigrationOptions options,
            Instant startedAt,
            int totalItems,
            long totalRows,
            String actor
    ) throws Exception {
        if (targetDialect == DbTargetDialect.SQLITE || targetSchema == null || targetSchema.isBlank()) {
            return new SchemaInitResult(false, targetSchema);
        }
        try (Connection adminConn = connections.open(targetConnMeta.id())) {
            var lookup = MigrationService.resolveAndCheckTargetSchema(adminConn, targetDialect, targetJdbcDialect, targetSchema);
            if (lookup.exists()) {
                return new SchemaInitResult(false, lookup.resolvedSchema());
            }
            if (!options.createTargetSchemaEnabled()) {
                throw new IllegalStateException("目标 Schema / 数据库「" + targetSchema
                        + "」不存在，且未开启「自动创建不存在的目标 Schema/库」，请先创建或开启自动创建后重试。");
            }
            jobs.updateProgress(jobId, "RUNNING", "STAGE_0_CREATE_SCHEMA", totalItems, 0, 0,
                    totalRows, 0, 0, "阶段 0/6：自动创建目标 Schema / 数据库「" + targetSchema + "」…", startedAt, null);
            List<String> stmts = ddlEmitter.emitCreateSchemaStatements(targetDialect, targetSchema, options);
            if (stmts.isEmpty()) {
                return new SchemaInitResult(false, targetSchema);
            }
            boolean alreadyExisted = false;
            for (int i = 0; i < stmts.size(); i++) {
                String sql = stmts.get(i);
                try (Statement st = adminConn.createStatement()) {
                    st.execute(sql);
                } catch (Exception error) {
                    String msg = rootMessage(error);
                    String lower = msg.toLowerCase(Locale.ROOT);
                    // 并发创建或方言已存在错误按幂等跳过
                    if (lower.contains("already exists") || lower.contains("已存在")
                            || lower.contains("ora-01920") || lower.contains("database exists")) {
                        alreadyExisted = true;
                        continue;
                    }
                    // Oracle 表空间配额授权失败时尝试降级到 ALTER USER ... QUOTA UNLIMITED ON USERS，仍失败则留日志不阻断
                    if ((targetDialect == DbTargetDialect.ORACLE || targetDialect == DbTargetDialect.OCEANBASE_ORACLE) && i > 0) {
                        try (Statement fallbackSt = adminConn.createStatement()) {
                            fallbackSt.execute("ALTER USER " + targetDialect.quoteIdentifier(targetSchema.trim()) + " QUOTA UNLIMITED ON USERS");
                        } catch (Exception fallbackError) {
                            log.warn("自动创建 Oracle Schema「{}」后授予表空间配额失败（将继续尝试建表）：{}", targetSchema, rootMessage(fallbackError));
                        }
                        continue;
                    }
                    if ((targetDialect == DbTargetDialect.ORACLE || targetDialect == DbTargetDialect.OCEANBASE_ORACLE)
                            && (lower.contains("ora-01031") || lower.contains("insufficient privileges") || lower.contains("权限不足"))) {
                        throw new IllegalStateException("自动创建目标 Oracle Schema（用户）「" + targetSchema
                                + "」失败：当前连接账号缺少 CREATE USER 权限，请使用 DBA 账号或先在目标库建好该用户（原始错误：" + msg + "）。", error);
                    }
                    throw new IllegalStateException("自动创建目标 Schema / 数据库「" + targetSchema + "」失败：" + msg, error);
                }
            }
            metadataCache.evictConnection(targetConnMeta.id());
            audit.onConnection(actor, "MIGRATION_CREATE_SCHEMA", targetConnMeta.id(), targetSchema,
                    "job=" + jobId + "; dialect=" + targetDialect.code());
            return new SchemaInitResult(!alreadyExisted, targetSchema.trim());
        }
    }

    private void maybeUpdateJobProgress(
            AtomicLong lastUpdateNanos,
            long jobId,
            String phase,
            int totalItems,
            int completedItems,
            int failedItems,
            long totalRows,
            long migratedRows,
            long migratedBytes,
            String message,
            Instant startedAt
    ) {
        long now = System.nanoTime();
        long prev = lastUpdateNanos.get();
        if (now - prev >= JOB_PROGRESS_THROTTLE_NANOS && lastUpdateNanos.compareAndSet(prev, now)) {
            jobs.updateProgress(jobId, "RUNNING", phase, totalItems, completedItems, failedItems,
                    totalRows, migratedRows, migratedBytes, message, startedAt, null);
        }
    }

    private int effectiveConcurrency(MigrationOptions options, DbTargetDialect... dialects) {
        for (DbTargetDialect d : dialects) {
            if (d == DbTargetDialect.SQLITE) return 1;
        }
        int requested = Math.max(1, options.concurrency());
        int maxAllowed = Math.max(1, properties.getMigration().getMaxConcurrency());
        return Math.min(requested, maxAllowed);
    }

    private static long estimatedTableRows(TableExecutionPlan tp) {
        Long est = tp.sourceTable().estimatedRows();
        return est == null ? 0L : Math.max(0L, est);
    }

    private static boolean isForeignKeyStatement(String sql) {
        return sql != null && sql.toUpperCase(Locale.ROOT).contains(" FOREIGN KEY");
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (Exception ignored) {
        }
    }

    private record SchemaInitResult(boolean freshlyCreated, String resolvedSchema) {
    }

    private record VerifyWorkUnit(int index, TableExecutionPlan plan) {
    }

    private record PartWorkUnit(TableImportState state, ArtifactPartEntry part) {
    }

    private static final class TableImportState {
        final TableExecutionPlan tp;
        final MigrationItem item;
        final ArtifactTableEntry artifactTable;
        final Instant itemStart;
        final int totalParts;
        final Set<Integer> completedParts;
        final AtomicLong liveRows;
        final AtomicLong liveBytes;
        final AtomicLong lastItemUpdateNanos = new AtomicLong(0);

        int remainingParts;
        long committedRows;
        long committedBytes;
        volatile boolean failed;
        volatile boolean cancelled;
        String firstError;
        boolean terminalRecorded;

        TableImportState(
                TableExecutionPlan tp,
                MigrationItem item,
                ArtifactTableEntry artifactTable,
                Instant itemStart,
                Set<Integer> completedParts,
                int remainingParts
        ) {
            this.tp = tp;
            this.item = item;
            this.artifactTable = artifactTable;
            this.itemStart = itemStart;
            this.totalParts = artifactTable.parts().size();
            this.completedParts = completedParts;
            this.remainingParts = remainingParts;
            this.committedRows = item.migratedRows();
            this.committedBytes = item.migratedBytes();
            this.liveRows = new AtomicLong(item.migratedRows());
            this.liveBytes = new AtomicLong(item.migratedBytes());
        }

        boolean isAborted() {
            return failed || cancelled;
        }
    }

    private Connection openScoped(long connectionId, String schema) throws Exception {
        return schema == null || schema.isBlank() ? connections.open(connectionId) : connections.open(connectionId, schema);
    }

    private boolean isCancelled(long jobId, String opKey) {
        return taskControl.isCancelled(opKey, () -> jobs.isCancelRequested(jobId));
    }

    private void checkCancelled(long jobId, String opKey) throws InterruptedException {
        if (isCancelled(jobId, opKey)) {
            throw new InterruptedException("任务已取消。");
        }
    }

    private static boolean isAlreadyDone(MigrationItem item) {
        return item != null && ("DONE".equals(item.status()) || "SKIPPED".equals(item.status()));
    }

    private Set<Integer> parseCompletedParts(String json) {
        if (json == null || json.isBlank()) return new LinkedHashSet<>();
        try {
            List<Integer> list = objectMapper.readValue(json, new TypeReference<>() { });
            return new LinkedHashSet<>(list);
        } catch (Exception ignored) {
            return new LinkedHashSet<>();
        }
    }

    private String encodeCompletedParts(Set<Integer> parts) {
        try {
            return objectMapper.writeValueAsString(parts);
        } catch (Exception ignored) {
            return "[]";
        }
    }

    private static String buildArtifactFileName(String connName, String schema, String customName) {
        String base;
        if (customName != null && !customName.isBlank()) {
            base = customName.trim();
            if (base.toLowerCase(Locale.ROOT).endsWith(MddPackageFormat.EXTENSION)) {
                base = base.substring(0, base.length() - MddPackageFormat.EXTENSION.length());
            }
        } else {
            String ts = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                    .withZone(java.time.ZoneId.systemDefault()).format(Instant.now());
            base = connName + (schema == null || schema.isBlank() ? "" : "-" + schema) + "-" + ts;
        }
        String safe = base.replaceAll("[\\\\/:*?\"<>|\\x00\\s]+", "_");
        if (safe.length() > 160) safe = safe.substring(0, 160);
        return safe + MddPackageFormat.EXTENSION;
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) cur = cur.getCause();
        String msg = cur.getMessage();
        return msg == null || msg.isBlank() ? cur.getClass().getSimpleName() : msg.replaceAll("\\R", " ").trim();
    }
}
