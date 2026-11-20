package com.example.dbadmin.service.migration;

import com.example.dbadmin.api.ApiProblemException;
import com.example.dbadmin.config.AppProperties;
import com.example.dbadmin.core.DatabaseDialect;
import com.example.dbadmin.core.DialectRegistry;
import com.example.dbadmin.dto.MigrationDtos.AiTranslateObjectRequest;
import com.example.dbadmin.dto.MigrationDtos.AiTranslateObjectResponse;
import com.example.dbadmin.dto.MigrationDtos.DialectSupportInfo;
import com.example.dbadmin.dto.MigrationDtos.MigrationArtifactPage;
import com.example.dbadmin.dto.MigrationDtos.MigrationAssessRequest;
import com.example.dbadmin.dto.MigrationDtos.MigrationAssessmentResponse;
import com.example.dbadmin.dto.MigrationDtos.MigrationJobDetail;
import com.example.dbadmin.dto.MigrationDtos.MigrationJobPage;
import com.example.dbadmin.dto.MigrationDtos.MigrationRetryRequest;
import com.example.dbadmin.dto.MigrationDtos.MigrationStartRequest;
import com.example.dbadmin.dto.MigrationDtos.MigrationVerificationReport;
import com.example.dbadmin.dto.MigrationDtos.MigrationVerifyRequest;
import com.example.dbadmin.dto.MigrationDtos.RegisterStorageArtifactRequest;
import com.example.dbadmin.dto.MigrationDtos.SupportedDialectMatrix;
import com.example.dbadmin.dto.MigrationDtos.TableAssessment;
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
import com.example.dbadmin.service.ConnectionService;
import com.example.dbadmin.service.ExecutionGuard;
import com.example.dbadmin.service.LargeFileUploadGuard;
import com.example.dbadmin.service.ReadOnlyQueryScope;
import com.example.dbadmin.service.SqlStatementClassifier;
import com.example.dbadmin.service.migration.artifact.MddPackageFormat;
import com.example.dbadmin.service.migration.artifact.MddPackageReader;
import com.example.dbadmin.service.migration.introspect.SchemaIntrospectorRegistry;
import com.example.dbadmin.service.migration.mapping.MigrationAssessor;
import com.example.dbadmin.service.migration.mapping.MigrationAssessor.MigrationPlan;
import com.example.dbadmin.service.migration.mapping.MigrationAssessor.ProgramExecutionPlan;
import com.example.dbadmin.service.migration.mapping.MigrationAssessor.SequenceExecutionPlan;
import com.example.dbadmin.service.migration.mapping.MigrationAssessor.TableExecutionPlan;
import com.example.dbadmin.service.migration.mapping.ProgramObjectTranslator;
import com.example.dbadmin.service.migration.model.CanonicalSchema;
import com.example.dbadmin.service.migration.model.DbTargetDialect;
import com.example.dbadmin.service.migration.model.MigrationOptions;
import com.example.dbadmin.service.migration.verify.MigrationVerifier;
import com.example.dbadmin.service.migration.verify.RowDigestAccumulator;
import com.example.dbadmin.storage.BackupStorageRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.sql.Connection;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;

/**
 * 异构数据库完整迁移与标准制品（.mddpkg）业务入口服务。
 *
 * <p>负责：评估规划、制品上传/拉取/管理、权限与生产确认门禁（{@link ExecutionGuard}）、
 * 并发锁申请（{@link BackgroundTaskControl}）、任务持久化与审计记录。</p>
 */
@Service
public class MigrationService {
    private static final int PLAN_TTL_MINUTES = 30;

    private final AppProperties properties;
    private final ConnectionService connections;
    private final DialectRegistry dialectRegistry;
    private final ExecutionGuard executionGuard;
    private final BackgroundTaskControl taskControl;
    private final LargeFileUploadGuard uploadGuard;
    private final BackupStorageRegistry storageRegistry;
    private final SchemaIntrospectorRegistry introspectors;
    private final MigrationAssessor assessor;
    private final ProgramObjectTranslator programTranslator;
    private final MigrationOrchestrator orchestrator;
    private final MigrationVerifier verifier;
    private final MigrationArtifactRepository artifacts;
    private final MigrationJobRepository jobs;
    private final MigrationItemRepository items;
    private final AuditRepository audit;
    private final ObjectMapper objectMapper;

    private final Map<String, PreparedPlan> preparedPlans = new ConcurrentHashMap<>();

    public MigrationService(
            AppProperties properties,
            ConnectionService connections,
            DialectRegistry dialectRegistry,
            ExecutionGuard executionGuard,
            BackgroundTaskControl taskControl,
            LargeFileUploadGuard uploadGuard,
            BackupStorageRegistry storageRegistry,
            SchemaIntrospectorRegistry introspectors,
            MigrationAssessor assessor,
            ProgramObjectTranslator programTranslator,
            MigrationOrchestrator orchestrator,
            MigrationVerifier verifier,
            MigrationArtifactRepository artifacts,
            MigrationJobRepository jobs,
            MigrationItemRepository items,
            AuditRepository audit,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.connections = connections;
        this.dialectRegistry = dialectRegistry;
        this.executionGuard = executionGuard;
        this.taskControl = taskControl;
        this.uploadGuard = uploadGuard;
        this.storageRegistry = storageRegistry;
        this.introspectors = introspectors;
        this.assessor = assessor;
        this.programTranslator = programTranslator;
        this.orchestrator = orchestrator;
        this.verifier = verifier;
        this.artifacts = artifacts;
        this.jobs = jobs;
        this.items = items;
        this.audit = audit;
        this.objectMapper = objectMapper;
    }

    public SupportedDialectMatrix supportedDialects() {
        List<DialectSupportInfo> list = Arrays.stream(DbTargetDialect.values())
                .map(d -> new DialectSupportInfo(
                        d.code(),
                        d.family().name(),
                        d.maxIdentifierLength(),
                        d.supportsSequences(),
                        d.supportsForeignKeys(),
                        d.supportsSecondaryIndexes(),
                        d.supportsColumnComments(),
                        d.supportsCheckConstraints(),
                        d.maxTemporalFsp(),
                        Map.of(
                                "namespace", d.catalogNamespace() ? "CATALOG" : "SCHEMA",
                                "family", d.family().name()
                        )
                )).toList();
        return new SupportedDialectMatrix(list);
    }

    /** 流式上传外部交付的 {@code .mddpkg} 标准制品并读取 manifest 建档。 */
    public MigrationArtifact uploadArtifactStream(
            InputStream input,
            String originalFilename,
            long declaredSize,
            Long ownerUserId,
            String actor
    ) throws Exception {
        if (input == null) throw new IllegalArgumentException("请选择要上传的 .mddpkg 标准制品文件。");
        long maxBytes = properties.getMigration().getMaxUploadBytes();
        if (declaredSize > maxBytes) {
            throw new IllegalArgumentException("制品文件超过允许大小上限（" + (maxBytes / (1024 * 1024 * 1024)) + " GB）。");
        }
        Path root = artifactRoot();
        Files.createDirectories(root);
        if (declaredSize > 0) {
            FileStore store = Files.getFileStore(root);
            if (store.getUsableSpace() < declaredSize + 256L * 1024 * 1024) {
                throw new IllegalStateException("服务器迁移制品目录剩余磁盘空间不足。");
            }
        }
        String safeName = sanitizeArtifactName(originalFilename);
        Path target = root.resolve("upload-" + UUID.randomUUID() + "-" + safeName).normalize();
        if (!target.startsWith(root)) throw new IllegalArgumentException("非法文件名。");
        if (!uploadGuard.tryAcquire()) {
            throw new ApiProblemException(HttpStatus.TOO_MANY_REQUESTS, "UPLOAD_BUSY", "当前大文件上传并发数已达上限，请稍后重试。");
        }

        MessageDigest digest = RowDigestAccumulator.sha256();
        long size = 0;
        try (OutputStream out = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
            byte[] buf = new byte[128 * 1024];
            int n;
            while ((n = input.read(buf)) >= 0) {
                if (n == 0) continue;
                size += n;
                if (size > maxBytes) throw new IllegalArgumentException("制品文件超过允许大小上限。");
                digest.update(buf, 0, n);
                out.write(buf, 0, n);
            }
        } catch (Exception error) {
            Files.deleteIfExists(target);
            throw error;
        } finally {
            uploadGuard.release();
        }
        if (size == 0) {
            Files.deleteIfExists(target);
            throw new IllegalArgumentException("上传的制品文件为空。");
        }
        return registerLocalArtifact(target, safeName, null, null, size, HexFormat.of().formatHex(digest.digest()), ownerUserId, actor);
    }

    /** 从远端文件服务（SMB / NFS / FTP / SFTP）拉取并登记 {@code .mddpkg} 制品。 */
    public MigrationArtifact registerFromStorage(RegisterStorageArtifactRequest request, Long ownerUserId, String actor) throws Exception {
        StorageProfile profile = storageRegistry.requireProfile(request.storageProfileId());
        String remotePath = request.remotePath().trim();
        Path root = artifactRoot();
        Files.createDirectories(root);
        String baseName = sanitizeArtifactName(Path.of(remotePath).getFileName().toString());
        Path target = root.resolve("remote-" + UUID.randomUUID() + "-" + baseName).normalize();

        MessageDigest digest = RowDigestAccumulator.sha256();
        long size;
        try (OutputStream out = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
            storageRegistry.download(profile, remotePath, new OutputStream() {
                @Override public void write(int b) throws java.io.IOException { digest.update((byte) b); out.write(b); }
                @Override public void write(byte[] b, int off, int len) throws java.io.IOException { digest.update(b, off, len); out.write(b, off, len); }
            }, downloaded -> { });
            size = Files.size(target);
        } catch (Exception error) {
            Files.deleteIfExists(target);
            throw error;
        }
        return registerLocalArtifact(target, baseName, profile.id(), remotePath, size, HexFormat.of().formatHex(digest.digest()), ownerUserId, actor);
    }

    private MigrationArtifact registerLocalArtifact(
            Path file,
            String displayName,
            Long storageProfileId,
            String remotePath,
            long size,
            String sha256,
            Long ownerUserId,
            String actor
    ) throws Exception {
        CanonicalSchema.ArtifactManifest manifest;
        try (MddPackageReader reader = new MddPackageReader(file, objectMapper)) {
            manifest = reader.readManifest();
            reader.readSchema(); // 校验 schema.json 可解析
        } catch (Exception error) {
            Files.deleteIfExists(file);
            throw new IllegalArgumentException("无效的 MyDataDev 标准制品（.mddpkg）：" + error.getMessage(), error);
        }
        Instant expires = Instant.now().plus(Math.max(1, properties.getMigration().getArtifactTtlHours()), ChronoUnit.HOURS);
        long id = artifacts.insert(new MigrationArtifact(
                0, displayName, file.toAbsolutePath().toString(),
                storageProfileId, remotePath, size, sha256,
                manifest.sourceDbType(), manifest.sourceVersion(), manifest.sourceSchema(),
                manifest.formatVersion(), manifest.tableCount(), manifest.totalRows(), manifest.totalRawBytes(),
                objectMapper.writeValueAsString(manifest), ownerUserId, Instant.now(), expires
        ));
        audit.global(actor, "MIGRATION_ARTIFACT_UPLOAD", displayName,
                "id=" + id + "; source=" + manifest.sourceDbType() + "; tables=" + manifest.tableCount() + "; rows=" + manifest.totalRows());
        return artifacts.findById(id).orElseThrow();
    }

    public MigrationArtifactPage listArtifacts(Long ownerUserId, Integer page, Integer pageSize) {
        int safePage = Math.max(page == null ? 0 : page, 0);
        int size = Math.min(Math.max(pageSize == null ? 20 : pageSize, 1), 100);
        List<MigrationArtifact> rows = artifacts.findAll(ownerUserId, size + 1, (long) safePage * size);
        boolean hasMore = rows.size() > size;
        return new MigrationArtifactPage(hasMore ? rows.subList(0, size) : rows, safePage, size, hasMore);
    }

    public MigrationArtifact requireArtifact(long id) {
        return artifacts.findById(id).orElseThrow(() -> new IllegalArgumentException("标准制品不存在：" + id));
    }

    public void deleteArtifact(long id, String actor) throws Exception {
        MigrationArtifact artifact = requireArtifact(id);
        if (jobs.countActiveByArtifactId(id) > 0) {
            throw new ApiProblemException(HttpStatus.CONFLICT, "ARTIFACT_IN_USE", "该制品正被执行中的迁移任务使用，无法删除。");
        }
        Files.deleteIfExists(Path.of(artifact.filePath()));
        artifacts.delete(id);
        audit.global(actor, "MIGRATION_ARTIFACT_DELETE", artifact.name(), "id=" + id);
    }

    /** 采集源端结构（在线连接或 .mddpkg 制品）并生成评估报告与 planToken。 */
    public MigrationAssessmentResponse assess(MigrationAssessRequest request, String actor) throws Exception {
        evictExpiredPlans();
        String mode = normalizeMode(request.mode());
        MigrationOptions options = (request.options() == null ? MigrationOptions.defaults() : request.options())
                .normalized(properties.getMigration().getDefaultConcurrency(), properties.getMigration().getMaxConcurrency());

        CanonicalSchema schema;
        Long sourceConnId = request.sourceConnectionId();
        Long sourceArtifactId = request.sourceArtifactId();
        List<String> introspectWarnings = new ArrayList<>();

        if ("IMPORT_ARTIFACT".equals(mode)) {
            if (sourceArtifactId == null) throw new IllegalArgumentException("导入标准制品必须指定 sourceArtifactId。");
            MigrationArtifact artifact = requireArtifact(sourceArtifactId);
            try (MddPackageReader reader = new MddPackageReader(Path.of(artifact.filePath()), objectMapper)) {
                schema = reader.readSchema();
            }
            if (options.selectedTables() != null && !options.selectedTables().isEmpty()) {
                var selectedLower = options.selectedTables().stream().map(s -> s.toLowerCase(Locale.ROOT)).collect(java.util.stream.Collectors.toSet());
                var filteredTables = schema.tables().stream().filter(t -> selectedLower.contains(t.name().toLowerCase(Locale.ROOT))).toList();
                schema = new CanonicalSchema(schema.formatVersion(), schema.sourceDbType(), schema.sourceFamily(),
                        schema.sourceVersion(), schema.sourceCharset(), schema.sourceSchema(), schema.snapshotToken(),
                        filteredTables, schema.sequences(), schema.programObjects());
            }
        } else {
            if (sourceConnId == null) throw new IllegalArgumentException("请指定源数据库连接。");
            DbConnection sourceConnMeta = connections.require(sourceConnId);
            DatabaseDialect sourceJdbcDialect = dialectRegistry.dialectFor(sourceConnMeta);
            DbTargetDialect sourceDialect = DbTargetDialect.fromDbType(sourceConnMeta.dbType());
            var introspector = introspectors.forDialect(sourceDialect);
            try (Connection conn = request.sourceSchema() == null || request.sourceSchema().isBlank()
                    ? connections.open(sourceConnId) : connections.open(sourceConnId, request.sourceSchema());
                 ReadOnlyQueryScope ignored = ReadOnlyQueryScope.begin(conn, true)) {
                schema = introspector.introspect(conn, sourceConnMeta, sourceJdbcDialect, sourceDialect,
                        request.sourceSchema(), options, introspectWarnings);
            }
        }

        DbTargetDialect targetDialect = null;
        String resolvedTargetSchema = request.targetSchema() == null ? null : request.targetSchema().trim();
        Boolean targetSchemaExists = null;
        if (!"EXPORT_ARTIFACT".equals(mode)) {
            if (request.targetConnectionId() != null) {
                DbConnection targetConnMeta = connections.require(request.targetConnectionId());
                targetDialect = DbTargetDialect.fromDbType(targetConnMeta.dbType());
                DatabaseDialect td = dialectRegistry.dialectFor(targetConnMeta);
                try (Connection tc = connections.open(targetConnMeta.id())) {
                    if (resolvedTargetSchema == null || resolvedTargetSchema.isBlank()) {
                        resolvedTargetSchema = td.currentSchema(tc);
                        if (resolvedTargetSchema != null && !resolvedTargetSchema.isBlank()) {
                            targetSchemaExists = Boolean.TRUE;
                        }
                    } else {
                        SchemaLookupResult lookup = resolveAndCheckTargetSchema(tc, targetDialect, td, resolvedTargetSchema);
                        resolvedTargetSchema = lookup.resolvedSchema();
                        targetSchemaExists = lookup.exists();
                    }
                } catch (Exception ignored) {
                }
            } else if (request.targetDbType() != null && !request.targetDbType().isBlank()) {
                targetDialect = DbTargetDialect.fromDbType(request.targetDbType());
            } else {
                throw new IllegalArgumentException("请指定目标数据库连接或目标数据库类型。");
            }
        }

        String planToken = UUID.randomUUID().toString();
        boolean aiAvail = !"EXPORT_ARTIFACT".equals(mode)
                && programTranslator.isAiAvailable(sourceConnId, request.targetConnectionId(), actor);
        MigrationPlan plan = assessor.assess(planToken, mode, schema, targetDialect, resolvedTargetSchema, options, aiAvail, targetSchemaExists);

        preparedPlans.put(planToken, new PreparedPlan(
                planToken, Instant.now().plus(PLAN_TTL_MINUTES, ChronoUnit.MINUTES),
                mode, sourceConnId, sourceArtifactId, request.targetConnectionId(),
                targetSchemaExists, plan
        ));
        if (sourceConnId != null) {
            audit.onConnection(actor, "MIGRATION_ASSESS", sourceConnId,
                    "mode=" + mode + (targetDialect == null ? "" : "; target=" + targetDialect.code()),
                    "tables=" + schema.tables().size() + "; warnings=" + plan.response().summary().warningCount());
        } else {
            audit.global(actor, "MIGRATION_ASSESS", "artifact:" + sourceArtifactId,
                    "target=" + (targetDialect == null ? "" : targetDialect.code()) + "; tables=" + schema.tables().size());
        }
        return plan.response();
    }

    public record PlanEndpoints(Long sourceConnectionId, Long sourceArtifactId, Long targetConnectionId) {
    }

    public PlanEndpoints inspectPlanEndpoints(String planToken) {
        PreparedPlan prepared = requireValidPlan(planToken);
        return new PlanEndpoints(prepared.sourceConnectionId(), prepared.sourceArtifactId(), prepared.targetConnectionId());
    }

    /** 按需返回大库评估计划中单张表的完整列类型映射与 DDL 详情。 */
    public TableAssessment getPlanTableAssessment(String planToken, String tableId) {
        PreparedPlan prepared = requireValidPlan(planToken);
        TableAssessment full = prepared.plan().fullTableAssessments().get(tableId);
        if (full == null) {
            throw new IllegalArgumentException("计划中未找到表：" + tableId);
        }
        return full;
    }

    /** 将完整计划的所有阶段 DDL 流式写出，支持万表级超大型库零内存堆积下载。 */
    public void writePlanDdl(String planToken, OutputStream out) throws Exception {
        PreparedPlan prepared = requireValidPlan(planToken);
        MigrationPlan plan = prepared.plan();
        var writer = new java.io.OutputStreamWriter(out, java.nio.charset.StandardCharsets.UTF_8);
        writer.write("-- MyDataDev 异构转换完整 DDL (" + plan.sourceDialect().code()
                + " -> " + (plan.targetDialect() == null ? "artifact" : plan.targetDialect().code()) + ")\n");
        if (plan.createSchemaSql() != null && !plan.createSchemaSql().isEmpty()) {
            writer.write("\n-- [阶段0 自动创建目标 Schema/数据库] " + plan.targetSchema() + "\n");
            for (String s : plan.createSchemaSql()) {
                writer.write(s + ";\n");
            }
        }
        for (TableAssessment ta : plan.fullTableAssessments().values()) {
            if (!ta.createTableSql().isEmpty()) {
                writer.write("\n-- [阶段1 基础表] " + ta.sourceName() + " -> " + ta.targetName() + "\n");
                for (String s : ta.createTableSql()) writer.write(s + ";\n");
            }
        }
        for (TableAssessment ta : plan.fullTableAssessments().values()) {
            if (!ta.postDataSql().isEmpty()) {
                writer.write("\n-- [阶段3 索引与约束] " + ta.targetName() + "\n");
                for (String s : ta.postDataSql()) writer.write(s + ";\n");
            }
        }
        for (TableAssessment ta : plan.fullTableAssessments().values()) {
            if (!ta.sequenceSyncSql().isEmpty()) {
                writer.write("\n-- [阶段4 自增/序列对齐] " + ta.targetName() + "\n");
                for (String s : ta.sequenceSyncSql()) writer.write(s + ";\n");
            }
        }
        writer.flush();
    }

    /** 按需调用既有 AI 框架转译单个跨方言可编程对象并试编译校验。 */
    public AiTranslateObjectResponse aiTranslateObject(AiTranslateObjectRequest request, String actor) throws Exception {
        PreparedPlan prepared = requireValidPlan(request.planToken());
        MigrationPlan plan = prepared.plan();
        if (plan.targetDialect() == null) {
            throw new IllegalArgumentException("仅导出模式不需要目标方言对象转译。");
        }
        ProgramExecutionPlan targetObj = plan.programObjects().stream()
                .filter(p -> p.key().equalsIgnoreCase(request.objectKey()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("计划中未找到对象：" + request.objectKey()));

        AiTranslateObjectResponse resp = programTranslator.translateWithAi(
                plan.sourceDialect(), plan.targetDialect(),
                prepared.sourceConnectionId(), prepared.targetConnectionId(),
                plan.targetSchema(), targetObj.sourceObject(), targetObj.targetName(), actor
        );
        if (prepared.targetConnectionId() != null) {
            audit.onConnection(actor, "MIGRATION_AI_TRANSLATE", prepared.targetConnectionId(), targetObj.key(),
                    "compiled=" + resp.compiled());
        } else {
            audit.global(actor, "MIGRATION_AI_TRANSLATE", targetObj.key(), "compiled=" + resp.compiled());
        }
        return resp;
    }

    /** 基于已评估的 {@code planToken} 启动后台迁移任务。 */
    public MigrationJob start(MigrationStartRequest request, String actor, Long ownerUserId) throws Exception {
        PreparedPlan prepared = requireValidPlan(request.planToken());
        MigrationPlan plan = prepared.plan();

        // 若用户在步骤 3 调整了选项（列类型覆写 / 对象源码覆写 / 冲突策略 / 并发度），重新生成精确执行计划
        if (request.options() != null) {
            MigrationOptions updatedOpts = request.options().normalized(
                    properties.getMigration().getDefaultConcurrency(), properties.getMigration().getMaxConcurrency());
            plan = assessor.assess(prepared.planToken(), plan.mode(), plan.sourceSchema(),
                    plan.targetDialect(), plan.targetSchema(), updatedOpts,
                    plan.response().aiTranslationAvailable(), prepared.targetSchemaExists());
        }
        if (!plan.response().canExecute()) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, "MIGRATION_BLOCKED",
                    "评估报告中存在阻断项（BLOCKER），请先处理冲突或调整配置后再启动。");
        }

        // 安全闸门校验：源端读生产确认 + 目标端可写与生产/覆盖强确认
        DbConnection sourceConn = null;
        if (prepared.sourceConnectionId() != null) {
            sourceConn = connections.require(prepared.sourceConnectionId());
            executionGuard.requireQueryAllowed(sourceConn, SqlStatementClassifier.Kind.QUERY, request.sourceProductionConfirmation());
        }
        DbConnection targetConn = null;
        if (!"EXPORT_ARTIFACT".equals(plan.mode())) {
            if (prepared.targetConnectionId() == null) {
                throw new IllegalArgumentException("启动迁移或导入任务必须指定目标数据库连接。");
            }
            targetConn = connections.require(prepared.targetConnectionId());
            executionGuard.requireWritableConnection(targetConn);
            executionGuard.requireMutationAllowed(targetConn, request.targetProductionConfirmation());
            if (("DROP_AND_RECREATE".equalsIgnoreCase(plan.options().conflictMode())
                    || "TRUNCATE_AND_LOAD".equalsIgnoreCase(plan.options().conflictMode()))
                    && !targetConn.name().equals(request.targetProductionConfirmation())) {
                throw new ApiProblemException(HttpStatus.CONFLICT, "PRODUCTION_CONFIRMATION_REQUIRED",
                        "使用「删除重建」或「清空覆盖」模式前，请输入目标连接名确认：" + targetConn.name(),
                        Map.of("confirmationText", targetConn.name()));
            }
        }

        long totalEstRows = plan.response().summary().estimatedTotalRows();
        int totalItems = plan.tables().size()
                + (plan.options().sequencesEnabled() ? plan.sequences().size() : 0)
                + (plan.options().programObjectsEnabled() ? plan.programObjects().size() : 0);

        long jobId = jobs.insert(new MigrationJob(
                0,
                plan.mode(),
                "IMPORT_ARTIFACT".equals(plan.mode()) ? "ARTIFACT" : "CONNECTION",
                prepared.sourceConnectionId(),
                prepared.sourceArtifactId(),
                plan.sourceDialect().code(),
                plan.sourceSchema().sourceSchema(),
                prepared.targetConnectionId(),
                plan.targetDialect() == null ? null : plan.targetDialect().code(),
                plan.targetSchema(),
                null,
                plan.options().conflictMode(),
                objectMapper.writeValueAsString(plan.options()),
                objectMapper.writeValueAsString(plan.response().summary()),
                plan.sourceSchema().snapshotToken(),
                "QUEUED",
                "QUEUED",
                totalItems, 0, 0,
                totalEstRows, 0, 0,
                "NOT_RUN", null,
                "迁移任务已进入后台队列。",
                false, actor, ownerUserId, null, null, Instant.now()
        ));

        // 写入表/序列/可编程对象子项
        int order = 1;
        for (TableExecutionPlan tp : plan.tables()) {
            items.insert(new MigrationItem(
                    0, jobId, "TABLE", tp.sourceTable().name(), tp.targetTableName(), tp.sourceTable().tableId(),
                    order++, "PENDING", tp.sourceTable().estimatedRows() == null ? 0 : tp.sourceTable().estimatedRows(),
                    0, 0, 0, 0, null,
                    objectMapper.writeValueAsString(tp.ddl()),
                    objectMapper.writeValueAsString(tp.findings()),
                    "NOT_RUN", null, null, null, null
            ));
        }
        if (!"EXPORT_ARTIFACT".equals(plan.mode()) && plan.options().sequencesEnabled()) {
            for (SequenceExecutionPlan sp : plan.sequences()) {
                items.insert(new MigrationItem(
                        0, jobId, "SEQUENCE", sp.sourceSequence().name(), sp.targetName(), null,
                        order++, "PENDING", 0, 0, 0, 0, 0, null,
                        sp.createSql() == null ? null : objectMapper.writeValueAsString(List.of(sp.createSql())),
                        objectMapper.writeValueAsString(sp.findings()),
                        "NOT_RUN", null, null, null, null
                ));
            }
        }
        if (!"EXPORT_ARTIFACT".equals(plan.mode()) && plan.options().programObjectsEnabled()) {
            for (ProgramExecutionPlan pp : plan.programObjects()) {
                items.insert(new MigrationItem(
                        0, jobId, pp.sourceObject().kind(), pp.sourceObject().name(), pp.targetName(), null,
                        order++, "PENDING", 0, 0, 0, 0, 0, null,
                        pp.targetSql() == null ? null : objectMapper.writeValueAsString(List.of(pp.targetSql())),
                        objectMapper.writeValueAsString(pp.findings()),
                        "NOT_RUN", null, null, null, null
                ));
            }
        }

        // 获取连接级后台重任务许可（同时保护源连接与目标连接小连接池）
        String opKey = MigrationOrchestrator.operationKey(jobId);
        boolean srcLocked = false;
        boolean tgtLocked = false;
        try {
            if (prepared.sourceConnectionId() != null) {
                srcLocked = taskControl.tryAcquire(prepared.sourceConnectionId(), opKey);
                if (!srcLocked) {
                    throw new ApiProblemException(HttpStatus.CONFLICT, "CONNECTION_BACKGROUND_BUSY",
                            "源连接已有后台重任务正在执行，请等待完成后重试。");
                }
            }
            if (prepared.targetConnectionId() != null
                    && !prepared.targetConnectionId().equals(prepared.sourceConnectionId())) {
                tgtLocked = taskControl.tryAcquire(prepared.targetConnectionId(), opKey);
                if (!tgtLocked) {
                    throw new ApiProblemException(HttpStatus.CONFLICT, "CONNECTION_BACKGROUND_BUSY",
                            "目标连接已有后台重任务正在执行，请等待完成后重试。");
                }
            }
            preparedPlans.remove(request.planToken());
            orchestrator.submit(jobId, plan, prepared.sourceConnectionId(), prepared.targetConnectionId(), actor, ownerUserId);
        } catch (RejectedExecutionException full) {
            if (srcLocked) taskControl.releaseCompleted(prepared.sourceConnectionId(), opKey);
            if (tgtLocked) taskControl.releaseCompleted(prepared.targetConnectionId(), opKey);
            jobs.updateProgress(jobId, "FAILED", "QUEUE_FULL", totalItems, 0, 0, totalEstRows, 0, 0,
                    "迁移后台队列已满，请稍后重试。", null, Instant.now());
            throw new ApiProblemException(HttpStatus.TOO_MANY_REQUESTS, "MIGRATION_QUEUE_FULL", "迁移后台队列已满，请稍后重试。");
        } catch (RuntimeException error) {
            if (srcLocked) taskControl.releaseCompleted(prepared.sourceConnectionId(), opKey);
            if (tgtLocked) taskControl.releaseCompleted(prepared.targetConnectionId(), opKey);
            jobs.updateProgress(jobId, "FAILED", "FAILED", totalItems, 0, 0, totalEstRows, 0, 0,
                    error.getMessage(), null, Instant.now());
            throw error;
        }

        if (sourceConn != null) {
            audit.onConnection(actor, "MIGRATION_START", sourceConn.id(), "job:" + jobId,
                    "mode=" + plan.mode() + "; tables=" + plan.tables().size());
        }
        if (targetConn != null && (sourceConn == null || targetConn.id() != sourceConn.id())) {
            audit.onConnection(actor, "MIGRATION_START", targetConn.id(), "job:" + jobId,
                    "mode=" + plan.mode() + "; tables=" + plan.tables().size());
        }
        return jobs.findById(jobId).orElseThrow();
    }

    /** 断点重跑失败/取消的表和对象项（已完成表与已完成分片自动跳过）。 */
    public MigrationJob retryFailed(long jobId, MigrationRetryRequest request, String actor) throws Exception {
        MigrationJob job = requireJob(jobId);
        if ("QUEUED".equals(job.status()) || "RUNNING".equals(job.status())) {
            throw new ApiProblemException(HttpStatus.CONFLICT, "MIGRATION_RUNNING", "该迁移任务正在运行中，请勿重复重试。");
        }
        MigrationPlan plan = orchestrator.cachedPlan(jobId);
        if (plan == null) {
            plan = rebuildPlanFromJob(job, actor);
        }
        if (job.targetConnectionId() != null) {
            DbConnection targetConn = connections.require(job.targetConnectionId());
            executionGuard.requireWritableConnection(targetConn);
            executionGuard.requireMutationAllowed(targetConn, request == null ? null : request.targetProductionConfirmation());
        }

        items.resetFailedItemsForRetry(jobId);
        jobs.reopenForRetry(jobId, "已发起失败项断点重试，已完成项将自动跳过…");

        String opKey = MigrationOrchestrator.operationKey(jobId);
        boolean srcLocked = false;
        boolean tgtLocked = false;
        try {
            if (job.sourceConnectionId() != null) {
                srcLocked = taskControl.tryAcquire(job.sourceConnectionId(), opKey);
                if (!srcLocked) throw new ApiProblemException(HttpStatus.CONFLICT, "CONNECTION_BACKGROUND_BUSY", "源连接忙碌，请稍后重试。");
            }
            if (job.targetConnectionId() != null && !job.targetConnectionId().equals(job.sourceConnectionId())) {
                tgtLocked = taskControl.tryAcquire(job.targetConnectionId(), opKey);
                if (!tgtLocked) throw new ApiProblemException(HttpStatus.CONFLICT, "CONNECTION_BACKGROUND_BUSY", "目标连接忙碌，请稍后重试。");
            }
            orchestrator.submit(jobId, plan, job.sourceConnectionId(), job.targetConnectionId(), actor, job.ownerUserId());
        } catch (RuntimeException error) {
            if (srcLocked) taskControl.releaseCompleted(job.sourceConnectionId(), opKey);
            if (tgtLocked) taskControl.releaseCompleted(job.targetConnectionId(), opKey);
            throw error;
        }
        if (job.targetConnectionId() != null) {
            audit.onConnection(actor, "MIGRATION_RETRY", job.targetConnectionId(), "job:" + jobId, "retryFailed=true");
        }
        return jobs.findById(jobId).orElseThrow();
    }

    public MigrationJob cancel(long jobId, String actor) {
        MigrationJob job = requireJob(jobId);
        jobs.requestCancel(jobId);
        taskControl.requestCancel(MigrationOrchestrator.operationKey(jobId));
        if (job.targetConnectionId() != null) {
            audit.onConnection(actor, "MIGRATION_CANCEL_REQUEST", job.targetConnectionId(), "job:" + jobId, "cancelRequested");
        } else if (job.sourceConnectionId() != null) {
            audit.onConnection(actor, "MIGRATION_CANCEL_REQUEST", job.sourceConnectionId(), "job:" + jobId, "cancelRequested");
        }
        return jobs.findById(jobId).orElseThrow();
    }

    /** 手动触发对已完成迁移任务的数据一致性校验（L1 / L2 / L3）。 */
    public MigrationVerificationReport verify(long jobId, MigrationVerifyRequest request, String actor) throws Exception {
        MigrationJob job = requireJob(jobId);
        if ("EXPORT_ARTIFACT".equalsIgnoreCase(job.mode())) {
            throw new IllegalArgumentException("仅导出制品任务没有目标库，请在导入目标库后执行数据一致性校验。");
        }
        MigrationPlan plan = orchestrator.cachedPlan(jobId);
        if (plan == null) {
            plan = rebuildPlanFromJob(job, actor);
            orchestrator.rememberPlan(jobId, plan);
        }
        Map<String, CanonicalSchema.TableChecksum> baselines = orchestrator.cachedSourceChecksums(jobId);
        if ((baselines == null || baselines.isEmpty()) && "IMPORT_ARTIFACT".equalsIgnoreCase(job.mode()) && job.sourceArtifactId() != null) {
            MigrationArtifact artifact = requireArtifact(job.sourceArtifactId());
            try (MddPackageReader reader = new MddPackageReader(Path.of(artifact.filePath()), objectMapper)) {
                var cs = reader.readChecksums();
                baselines = cs == null ? Map.of() : cs.tables();
            }
        }
        DbConnection targetConnMeta = connections.require(job.targetConnectionId());
        DatabaseDialect targetJdbcDialect = dialectRegistry.dialectFor(targetConnMeta);
        String level = request == null || request.verifyLevel() == null || request.verifyLevel().isBlank()
                ? plan.options().verifyLevel() : request.verifyLevel();
        MigrationVerificationReport report = orchestrator.runVerificationInternal(
                jobId, plan, targetConnMeta, targetJdbcDialect, baselines, level,
                request == null ? List.of() : request.tables(), null
        );
        audit.onConnection(actor, "MIGRATION_VERIFY", targetConnMeta.id(), "job:" + jobId,
                "level=" + level + "; status=" + report.overallStatus() + "; passed=" + report.passedTables() + "/" + report.totalTables());
        return report;
    }

    public String renderVerificationMarkdown(long jobId) throws Exception {
        MigrationJob job = requireJob(jobId);
        if (job.verifyReportJson() == null || job.verifyReportJson().isBlank()) {
            throw new IllegalArgumentException("该迁移任务尚未生成一致性校验报告。");
        }
        MigrationVerificationReport report = objectMapper.readValue(job.verifyReportJson(), MigrationVerificationReport.class);
        return verifier.renderMarkdownReport(report);
    }

    public MigrationJobPage listJobs(Long connectionId, Integer page, Integer pageSize) {
        int safePage = Math.max(page == null ? 0 : page, 0);
        int size = Math.min(Math.max(pageSize == null ? 20 : pageSize, 1), 100);
        List<MigrationJob> rows = jobs.findPage(connectionId, size + 1, (long) safePage * size);
        boolean hasMore = rows.size() > size;
        return new MigrationJobPage(hasMore ? rows.subList(0, size) : rows, safePage, size, hasMore);
    }

    public MigrationJobDetail getJobDetail(long jobId) {
        MigrationJob job = requireJob(jobId);
        List<MigrationItem> jobItems = items.findByJobId(jobId);
        MigrationArtifact srcArt = job.sourceArtifactId() == null ? null : artifacts.findById(job.sourceArtifactId()).orElse(null);
        MigrationArtifact outArt = job.outputArtifactId() == null ? null : artifacts.findById(job.outputArtifactId()).orElse(null);
        return new MigrationJobDetail(job, jobItems, srcArt, outArt);
    }

    public MigrationJob requireJob(long jobId) {
        return jobs.findById(jobId).orElseThrow(() -> new IllegalArgumentException("迁移任务不存在：" + jobId));
    }

    private MigrationPlan rebuildPlanFromJob(MigrationJob job, String actor) throws Exception {
        MigrationOptions opts = job.optionsJson() == null || job.optionsJson().isBlank()
                ? MigrationOptions.defaults()
                : objectMapper.readValue(job.optionsJson(), MigrationOptions.class);
        MigrationAssessmentResponse resp = assess(new MigrationAssessRequest(
                job.mode(), job.sourceConnectionId(), job.sourceSchema(), job.sourceArtifactId(),
                job.targetConnectionId(), job.targetSchema(), job.targetDbType(), opts
        ), actor);
        PreparedPlan rebuilt = preparedPlans.remove(resp.planToken());
        return rebuilt.plan();
    }

    private PreparedPlan requireValidPlan(String planToken) {
        evictExpiredPlans();
        PreparedPlan plan = preparedPlans.get(planToken);
        if (plan == null || plan.expiresAt().isBefore(Instant.now())) {
            throw new IllegalArgumentException("迁移评估计划已过期，请重新执行预检评估。");
        }
        return plan;
    }

    private void evictExpiredPlans() {
        Instant now = Instant.now();
        preparedPlans.entrySet().removeIf(e -> e.getValue().expiresAt().isBefore(now));
    }

    private Path artifactRoot() {
        return Path.of(properties.getMigration().getDirectory()).toAbsolutePath().normalize();
    }

    private static String normalizeMode(String raw) {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("请指定迁移模式。");
        String u = raw.trim().toUpperCase(Locale.ROOT);
        if (!u.equals("ONLINE_DIRECT") && !u.equals("EXPORT_ARTIFACT") && !u.equals("IMPORT_ARTIFACT")) {
            throw new IllegalArgumentException("不支持的迁移模式：" + raw);
        }
        return u;
    }

    private static String sanitizeArtifactName(String raw) {
        String name = raw == null || raw.isBlank() ? "migration" + MddPackageFormat.EXTENSION : Path.of(raw).getFileName().toString();
        name = name.replaceAll("[\\\\/:*?\"<>|\\x00]", "_");
        if (!name.toLowerCase(Locale.ROOT).endsWith(MddPackageFormat.EXTENSION)) {
            name = name + MddPackageFormat.EXTENSION;
        }
        return name.length() > 200 ? name.substring(name.length() - 200) : name;
    }

    public record SchemaLookupResult(String resolvedSchema, boolean exists) {
    }

    /** 检查目标连接上指定的 Schema/Database 是否已存在（并规范化不区分大小写方言的已有名称大小写）。 */
    public static SchemaLookupResult resolveAndCheckTargetSchema(
            Connection conn,
            DbTargetDialect targetDialect,
            DatabaseDialect targetJdbcDialect,
            String requestedSchema
    ) throws Exception {
        String trimmed = requestedSchema == null ? "" : requestedSchema.trim();
        if (trimmed.isEmpty() || targetDialect == DbTargetDialect.SQLITE) {
            return new SchemaLookupResult(trimmed, true);
        }
        List<String> existing = new ArrayList<>();
        var meta = conn.getMetaData();
        boolean isCatalog = targetJdbcDialect.namespaceKind() == DatabaseDialect.NamespaceKind.CATALOG;
        try (var rs = isCatalog ? meta.getCatalogs() : meta.getSchemas()) {
            String col = isCatalog ? "TABLE_CAT" : "TABLE_SCHEM";
            while (rs.next()) {
                String name = rs.getString(col);
                if (name != null && !name.isBlank()) {
                    existing.add(name);
                }
            }
        }
        for (String name : existing) {
            if (name.equals(trimmed)) {
                return new SchemaLookupResult(name, true);
            }
        }
        // Oracle / DM8 / SQL Server / H2 默认不区分大小写，若仅存在一个大小写不同但同名的 Schema 则直接复用
        boolean foldCase = targetDialect.isOracleFamily()
                || targetDialect == DbTargetDialect.SQLSERVER
                || targetDialect == DbTargetDialect.H2;
        if (foldCase) {
            List<String> folded = existing.stream().filter(n -> n.equalsIgnoreCase(trimmed)).toList();
            if (folded.size() == 1) {
                return new SchemaLookupResult(folded.get(0), true);
            }
        }
        return new SchemaLookupResult(trimmed, false);
    }

    private record PreparedPlan(
            String planToken,
            Instant expiresAt,
            String mode,
            Long sourceConnectionId,
            Long sourceArtifactId,
            Long targetConnectionId,
            Boolean targetSchemaExists,
            MigrationPlan plan
    ) {
    }
}
