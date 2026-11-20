package com.example.dbadmin.service.migration.mapping;

import com.example.dbadmin.dto.MigrationDtos.AssessmentFinding;
import com.example.dbadmin.dto.MigrationDtos.AssessmentSummary;
import com.example.dbadmin.dto.MigrationDtos.ColumnMappingPreview;
import com.example.dbadmin.dto.MigrationDtos.MigrationAssessmentResponse;
import com.example.dbadmin.dto.MigrationDtos.ProgramObjectAssessment;
import com.example.dbadmin.dto.MigrationDtos.TableAssessment;
import com.example.dbadmin.service.SchemaExportOrdering;
import com.example.dbadmin.service.SchemaExportScript;
import com.example.dbadmin.service.migration.mapping.TypeMappingRegistry.MappedColumn;
import com.example.dbadmin.service.migration.model.CanonicalSchema;
import com.example.dbadmin.service.migration.model.CanonicalSchema.CanonicalProgramObject;
import com.example.dbadmin.service.migration.model.CanonicalSchema.CanonicalSequence;
import com.example.dbadmin.service.migration.model.CanonicalSchema.CanonicalTable;
import com.example.dbadmin.service.migration.model.DbTargetDialect;
import com.example.dbadmin.service.migration.model.MigrationOptions;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 异构迁移评估与执行计划生成器。
 *
 * <p>把源 {@link CanonicalSchema} 与目标方言、用户选项组装为完整的 {@link MigrationPlan}：
 * <ul>
 *   <li>全部表名/列名映射表与冲突检测；</li>
 *   <li>每列的精准目标类型与行宽/精度/时区风险提示；</li>
 *   <li>分阶段目标 DDL（建表 / 索引外键约束 / 序列对齐 / 可编程对象拓扑序）；</li>
 *   <li>整库兼容性统计与容量估算。</li>
 * </ul></p>
 */
@Component
public class MigrationAssessor {
    private final TypeMappingRegistry typeMapper;
    private final TargetDdlEmitter ddlEmitter;
    private final ProgramObjectTranslator programTranslator;

    public MigrationAssessor(
            TypeMappingRegistry typeMapper,
            TargetDdlEmitter ddlEmitter,
            ProgramObjectTranslator programTranslator
    ) {
        this.typeMapper = typeMapper;
        this.ddlEmitter = ddlEmitter;
        this.programTranslator = programTranslator;
    }

    public record TableExecutionPlan(
            CanonicalTable sourceTable,
            String targetTableName,
            List<MappedColumn> columns,
            TargetDdlEmitter.TableDdlBundle ddl,
            List<AssessmentFinding> findings
    ) {
        public int effectiveTemporalFspCap(DbTargetDialect target) {
            return target == null ? 6 : target.maxTemporalFsp();
        }
    }

    public record SequenceExecutionPlan(
            CanonicalSequence sourceSequence,
            String targetName,
            String createSql,
            List<AssessmentFinding> findings
    ) {
    }

    public record ProgramExecutionPlan(
            CanonicalProgramObject sourceObject,
            String key,
            String targetName,
            String translationMode,
            String targetSql,
            List<String> dependencies,
            List<AssessmentFinding> findings
    ) {
    }

    private static final int INLINE_DETAIL_TABLE_THRESHOLD = 200;
    private static final int INLINE_DETAIL_FIRST_COUNT = 80;
    private static final int INLINE_DETAIL_MAX_COUNT = 150;

    public record MigrationPlan(
            String mode,
            CanonicalSchema sourceSchema,
            DbTargetDialect sourceDialect,
            DbTargetDialect targetDialect,
            String targetSchema,
            Boolean targetSchemaExists,
            List<String> createSchemaSql,
            MigrationOptions options,
            List<TableExecutionPlan> tables,
            List<SequenceExecutionPlan> sequences,
            List<ProgramExecutionPlan> programObjects,
            List<AssessmentFinding> globalFindings,
            Map<String, TableAssessment> fullTableAssessments,
            MigrationAssessmentResponse response
    ) {
    }

    public MigrationPlan assess(
            String planToken,
            String mode,
            CanonicalSchema schema,
            DbTargetDialect targetDialect,
            String targetSchema,
            MigrationOptions options,
            boolean aiTranslationAvailable
    ) {
        return assess(planToken, mode, schema, targetDialect, targetSchema, options, aiTranslationAvailable, null);
    }

    public MigrationPlan assess(
            String planToken,
            String mode,
            CanonicalSchema schema,
            DbTargetDialect targetDialect,
            String targetSchema,
            MigrationOptions options,
            boolean aiTranslationAvailable,
            Boolean targetSchemaExists
    ) {
        DbTargetDialect sourceDialect = DbTargetDialect.fromDbType(schema.sourceDbType());
        boolean isExportOnly = "EXPORT_ARTIFACT".equalsIgnoreCase(mode);
        DbTargetDialect effectiveTarget = targetDialect == null ? sourceDialect : targetDialect;
        String effectiveTargetSchema = targetSchema == null ? "" : targetSchema.trim();

        List<AssessmentFinding> globalFindings = new ArrayList<>();
        if (schema.tables().isEmpty()) {
            globalFindings.add(new AssessmentFinding("BLOCKER", "NO_TABLES_FOUND", "SCHEMA", schema.sourceSchema(), null,
                    "所选 Schema 中未发现可迁移的数据表", "请确认 Schema 名称与连接权限"));
        }

        // 目标 Schema / Database 自动创建预检
        List<String> createSchemaSql = List.of();
        if (!isExportOnly && effectiveTarget != DbTargetDialect.SQLITE && !effectiveTargetSchema.isBlank()) {
            if (Boolean.FALSE.equals(targetSchemaExists)) {
                if (options.createTargetSchemaEnabled()) {
                    createSchemaSql = ddlEmitter.emitCreateSchemaStatements(effectiveTarget, effectiveTargetSchema, options);
                    if (effectiveTarget == DbTargetDialect.ORACLE || effectiveTarget == DbTargetDialect.OCEANBASE_ORACLE) {
                        globalFindings.add(new AssessmentFinding("WARNING", "TARGET_SCHEMA_AUTO_CREATE_ORACLE", "SCHEMA", effectiveTargetSchema, null,
                                "目标 Oracle Schema（用户）「" + effectiveTargetSchema + "」尚不存在，执行前将尝试以 CREATE USER ... NO AUTHENTICATION 自动创建",
                                "需要当前连接账号具备 CREATE USER 与表空间配额权限；如无 DBA 权限请先在目标库建好用户"));
                    } else {
                        globalFindings.add(new AssessmentFinding("OK", "TARGET_SCHEMA_AUTO_CREATE", "SCHEMA", effectiveTargetSchema, null,
                                "目标 Schema / 数据库「" + effectiveTargetSchema + "」尚不存在，执行前将自动创建",
                                null));
                    }
                } else {
                    globalFindings.add(new AssessmentFinding("BLOCKER", "TARGET_SCHEMA_MISSING", "SCHEMA", effectiveTargetSchema, null,
                            "目标 Schema / 数据库「" + effectiveTargetSchema + "」不存在，且未开启自动创建",
                            "请开启「目标 Schema / 库不存在时自动创建」，或先在目标库手动创建后再评估"));
                }
            } else if (targetSchemaExists == null && options.createTargetSchemaEnabled()) {
                createSchemaSql = ddlEmitter.emitCreateSchemaStatements(effectiveTarget, effectiveTargetSchema, options);
            }
        }

        // 全局性跨库语义提醒
        if (!isExportOnly && sourceDialect.isOracleFamily() && effectiveTarget.isMySqlFamily()) {
            globalFindings.add(new AssessmentFinding("OK", "MYSQL_COLLATION_POLICY", "SCHEMA", effectiveTargetSchema, null,
                    options.caseSensitiveCollationEnabled()
                            ? "已启用 utf8mb4_bin 大小写敏感排序规则，可避免 Oracle 大小写敏感唯一键在 MySQL 冲突"
                            : "已关闭大小写敏感排序规则：若源表唯一键/主键含仅大小写不同的字符值，迁入 MySQL 可能报唯一键冲突",
                    "默认推荐保持大小写敏感排序规则"));
        }
        if (!isExportOnly && !sourceDialect.isOracleFamily() && effectiveTarget.isOracleFamily()) {
            globalFindings.add(new AssessmentFinding("WARNING", "ORACLE_EMPTY_STRING_NULL", "SCHEMA", effectiveTargetSchema, null,
                    "Oracle 中空字符串 '' 等价于 NULL，源库中 NOT NULL 文本列的空字符串值将按「" + options.emptyStringToOraclePolicy() + "」策略处理",
                    "如源库存在大量 NOT NULL 空串，建议将策略设为 ALLOW_NULL 或 SPACE_PLACEHOLDER"));
        }
        if (!isExportOnly && effectiveTarget == DbTargetDialect.POSTGRESQL && sourceDialect != DbTargetDialect.POSTGRESQL) {
            globalFindings.add(new AssessmentFinding("WARNING", "PG_NUL_BYTE_GUARD", "SCHEMA", effectiveTargetSchema, null,
                    "PostgreSQL UTF-8 文本列禁止包含 NUL(\\0) 字节，执行时将按「" + options.pgNulBytePolicy() + "」策略检测处理", null));
        }
        if (!isExportOnly && effectiveTarget == DbTargetDialect.CLICKHOUSE) {
            globalFindings.add(new AssessmentFinding("WARNING", "CLICKHOUSE_OLAP_TARGET", "SCHEMA", effectiveTargetSchema, null,
                    "ClickHouse 为 OLAP 列存引擎：建表自动使用 MergeTree()，传统外键、二级索引与 CHECK 约束将按预期跳过", null));
        }

        // 1. 预计算所有表名与列名映射（外键互引用依赖这张全表映射）
        Map<String, String> sourceToTargetTable = new LinkedHashMap<>();
        Map<String, Map<String, String>> sourceToTargetCols = new LinkedHashMap<>();
        Map<String, List<MappedColumn>> mappedColsByTable = new LinkedHashMap<>();
        Set<String> targetTableCollisionCheck = new LinkedHashSet<>();

        for (CanonicalTable table : schema.tables()) {
            String customTableName = findCustomTableName(options.tableNameMapping(), table.name());
            String rawTargetName = customTableName != null ? customTableName : table.name();
            var tableIdMapped = IdentifierMapper.map(rawTargetName, effectiveTarget, options.identifierCasePolicy());
            if (tableIdMapped.truncated()) {
                globalFindings.add(new AssessmentFinding("WARNING", "TABLE_NAME_TRUNCATED", "TABLE", table.name(), null,
                        "表名超过目标库 " + effectiveTarget.maxIdentifierLength() + " 字符上限，已确定性截断为 " + tableIdMapped.targetName(), null));
            }
            if (!targetTableCollisionCheck.add(tableIdMapped.targetName().toLowerCase(Locale.ROOT))) {
                globalFindings.add(new AssessmentFinding("BLOCKER", "TABLE_NAME_COLLISION", "TABLE", table.name(), null,
                        "应用大小写/重命名策略后目标表名发生冲突：" + tableIdMapped.targetName(), "请调整标识符大小写策略或配置表名映射"));
            }
            sourceToTargetTable.put(table.name().toLowerCase(Locale.ROOT), tableIdMapped.targetName());

            List<MappedColumn> mappedCols = isExportOnly
                    ? identityMappedColumns(table)
                    : typeMapper.mapTableColumns(sourceDialect, effectiveTarget, table, options);
            mappedColsByTable.put(table.tableId(), mappedCols);
            Map<String, String> colNames = new LinkedHashMap<>();
            for (MappedColumn mc : mappedCols) {
                colNames.put(mc.source().name().toLowerCase(Locale.ROOT), mc.targetColumnName());
            }
            sourceToTargetCols.put(table.name().toLowerCase(Locale.ROOT), colNames);
        }

        // 2. 逐表生成分阶段 DDL 与评估项
        List<TableExecutionPlan> tablePlans = new ArrayList<>();
        List<TableAssessment> tableAssessments = new ArrayList<>();
        long totalEstRows = 0;
        long totalEstBytes = 0;
        boolean hasAnyLob = false;

        for (CanonicalTable table : schema.tables()) {
            String targetName = sourceToTargetTable.get(table.name().toLowerCase(Locale.ROOT));
            List<MappedColumn> cols = mappedColsByTable.get(table.tableId());
            List<AssessmentFinding> tableFindings = new ArrayList<>();
            List<ColumnMappingPreview> colPreviews = new ArrayList<>();
            boolean tableHasLob = false;
            for (MappedColumn mc : cols) {
                if (mc.source().canonicalType().isLob() || mc.source().canonicalType().isBinary()) {
                    tableHasLob = true;
                    hasAnyLob = true;
                }
                if (mc.nameTruncated()) {
                    tableFindings.add(new AssessmentFinding("WARNING", "COLUMN_NAME_TRUNCATED", "COLUMN", table.name(), mc.source().name(),
                            "列名超过目标库长度上限，截断为 " + mc.targetColumnName(), null));
                }
                if (!"OK".equals(mc.severity()) && mc.note() != null) {
                    tableFindings.add(new AssessmentFinding(mc.severity(), mc.findingCode(), "COLUMN", table.name(), mc.source().name(),
                            mc.note(), mc.overridden() ? null : "可在步骤 3 中针对该列手工覆写目标类型"));
                }
                colPreviews.add(new ColumnMappingPreview(
                        mc.source().name(), mc.targetColumnName(), mc.source().sourceNativeType(),
                        mc.source().canonicalType().name(), mc.targetSqlType(), mc.nullable(),
                        mc.overridden(), mc.source().canonicalType().isLob() || mc.source().canonicalType().isBinary(),
                        mc.severity(), mc.note()
                ));
            }
            if (table.partitionSummary() != null && !isExportOnly && !sourceDialect.sameFamily(effectiveTarget)) {
                tableFindings.add(new AssessmentFinding("WARNING", "PARTITION_FLATTENED", "TABLE", table.name(), null,
                        "源表为分区表（" + table.partitionSummary() + "），跨家族迁移将先以普通表承载全量数据，如需分区建议迁移后按目标方言语法重建", null));
            }

            TargetDdlEmitter.TableDdlBundle ddl = isExportOnly
                    ? new TargetDdlEmitter.TableDdlBundle(List.of(), List.of(), List.of(), List.of())
                    : ddlEmitter.emitTableDdl(sourceDialect, effectiveTarget, effectiveTargetSchema, table, targetName,
                            cols, sourceToTargetTable, sourceToTargetCols, options);
            tableFindings.addAll(ddl.findings());

            tablePlans.add(new TableExecutionPlan(table, targetName, cols, ddl, List.copyOf(tableFindings)));
            if (table.estimatedRows() != null) totalEstRows += Math.max(0L, table.estimatedRows());
            if (table.estimatedBytes() != null) totalEstBytes += Math.max(0L, table.estimatedBytes());

            tableAssessments.add(new TableAssessment(
                    table.tableId(), table.name(), targetName, table.estimatedRows(), table.estimatedBytes(),
                    colPreviews.size(), false,
                    tableHasLob, !table.primaryKeyColumns().isEmpty(), table.primaryKeyColumns(),
                    table.indexes().size(), table.foreignKeys().size(), table.checkConstraints().size(),
                    List.copyOf(colPreviews), ddl.createTableStatements(), ddl.postDataStatements(), ddl.sequenceSyncStatements(),
                    List.copyOf(tableFindings)
            ));
        }

        // 3. 序列规划
        List<SequenceExecutionPlan> sequencePlans = new ArrayList<>();
        if (!isExportOnly && options.sequencesEnabled() && !schema.sequences().isEmpty()) {
            for (CanonicalSequence seq : schema.sequences()) {
                List<AssessmentFinding> seqFindings = new ArrayList<>();
                String targetSeqName = IdentifierMapper.map(seq.name(), effectiveTarget, options.identifierCasePolicy()).targetName();
                String sql = ddlEmitter.emitSequenceCreateSql(effectiveTarget, effectiveTargetSchema, seq, options);
                if (sql == null) {
                    seqFindings.add(new AssessmentFinding("WARNING", "SEQUENCE_UNSUPPORTED_TARGET", "SEQUENCE", seq.name(), null,
                            "目标库 " + effectiveTarget.code() + " 不支持独立 SEQUENCE 对象（" + seq.name() + "，当前值 " + seq.currentValue() + "）",
                            "若仅服务单表主键请改用目标表 AUTO_INCREMENT；若多表共享请在应用侧或序列表改造"));
                    globalFindings.addAll(seqFindings);
                }
                sequencePlans.add(new SequenceExecutionPlan(seq, targetSeqName, sql, List.copyOf(seqFindings)));
            }
        }

        // 4. 可编程对象拓扑排序 + 翻译预览
        List<ProgramExecutionPlan> programPlans = new ArrayList<>();
        List<ProgramObjectAssessment> programAssessments = new ArrayList<>();
        if (!schema.programObjects().isEmpty()) {
            List<CanonicalProgramObject> ordered = orderProgramObjects(schema.programObjects());
            for (CanonicalProgramObject obj : ordered) {
                String key = obj.kind() + ":" + obj.name();
                if (isExportOnly) {
                    programPlans.add(new ProgramExecutionPlan(obj, key, obj.name(), "EXPORT_RAW", obj.sourceSql(), obj.dependencies(), List.of()));
                    programAssessments.add(new ProgramObjectAssessment(key, obj.kind(), obj.name(), obj.name(),
                            "EXPORT_RAW", obj.sourceSql(), obj.sourceSql(), false, obj.dependencies(), List.of()));
                } else {
                    var tr = programTranslator.translateDeterministic(sourceDialect, effectiveTarget,
                            schema.sourceSchema(), effectiveTargetSchema, obj, options, sourceToTargetTable);
                    programPlans.add(new ProgramExecutionPlan(obj, key, tr.targetName(), tr.translationMode(),
                            tr.targetSql(), obj.dependencies(), tr.findings()));
                    programAssessments.add(new ProgramObjectAssessment(key, obj.kind(), obj.name(), tr.targetName(),
                            tr.translationMode(), obj.sourceSql(), tr.targetSql(), false, obj.dependencies(), tr.findings()));
                }
            }
        }

        // 汇总严重度计数
        int okCount = 0;
        int warningCount = 0;
        int blockerCount = 0;
        for (AssessmentFinding f : globalFindings) {
            if ("BLOCKER".equals(f.severity())) blockerCount++;
            else if ("WARNING".equals(f.severity())) warningCount++;
            else okCount++;
        }
        for (TableExecutionPlan tp : tablePlans) {
            boolean hasBlocker = false;
            boolean hasWarning = false;
            for (AssessmentFinding f : tp.findings()) {
                if ("BLOCKER".equals(f.severity())) { blockerCount++; hasBlocker = true; }
                else if ("WARNING".equals(f.severity())) { warningCount++; hasWarning = true; }
            }
            if (!hasBlocker && !hasWarning) okCount++;
        }
        for (ProgramExecutionPlan pp : programPlans) {
            for (AssessmentFinding f : pp.findings()) {
                if ("BLOCKER".equals(f.severity())) blockerCount++;
                else if ("WARNING".equals(f.severity())) warningCount++;
            }
        }

        AssessmentSummary summary = new AssessmentSummary(
                tablePlans.size(),
                schema.sequences().size(),
                programPlans.size(),
                totalEstRows,
                totalEstBytes,
                okCount,
                warningCount,
                blockerCount,
                hasAnyLob
        );

        // 超大型库（>200 张表）的响应瘦身：完整列映射与 DDL 留在服务端计划池中，前端按需拉取展开表，防止 80MB JSON 拖垮界面
        Map<String, TableAssessment> fullTableMap = new LinkedHashMap<>(tableAssessments.size());
        for (TableAssessment ta : tableAssessments) {
            fullTableMap.put(ta.tableId(), ta);
        }
        List<TableAssessment> responseTables = compactTableAssessmentsIfLarge(tableAssessments);

        MigrationAssessmentResponse response = new MigrationAssessmentResponse(
                planToken,
                mode,
                sourceDialect.code(),
                schema.sourceVersion(),
                schema.sourceSchema(),
                isExportOnly ? null : effectiveTarget.code(),
                isExportOnly ? null : effectiveTargetSchema,
                isExportOnly ? null : targetSchemaExists,
                isExportOnly ? List.of() : List.copyOf(createSchemaSql),
                !isExportOnly && sourceDialect.sameFamily(effectiveTarget),
                blockerCount == 0,
                aiTranslationAvailable,
                summary,
                responseTables,
                List.copyOf(programAssessments),
                List.copyOf(globalFindings),
                options
        );

        return new MigrationPlan(
                mode, schema, sourceDialect, isExportOnly ? null : effectiveTarget,
                effectiveTargetSchema, isExportOnly ? null : targetSchemaExists,
                isExportOnly ? List.of() : List.copyOf(createSchemaSql),
                options,
                List.copyOf(tablePlans), List.copyOf(sequencePlans), List.copyOf(programPlans),
                List.copyOf(globalFindings), Map.copyOf(fullTableMap), response
        );
    }

    private static List<TableAssessment> compactTableAssessmentsIfLarge(List<TableAssessment> all) {
        if (all.size() <= INLINE_DETAIL_TABLE_THRESHOLD) {
            return List.copyOf(all);
        }
        List<TableAssessment> out = new ArrayList<>(all.size());
        int inlineCount = 0;
        for (int i = 0; i < all.size(); i++) {
            TableAssessment ta = all.get(i);
            boolean hasIssue = ta.findings().stream().anyMatch(f -> "BLOCKER".equals(f.severity()) || "WARNING".equals(f.severity()));
            boolean keepInline = i < INLINE_DETAIL_FIRST_COUNT || (hasIssue && inlineCount < INLINE_DETAIL_MAX_COUNT);
            if (keepInline) {
                inlineCount++;
                out.add(ta);
            } else {
                out.add(new TableAssessment(
                        ta.tableId(), ta.sourceName(), ta.targetName(),
                        ta.estimatedRows(), ta.estimatedBytes(), ta.columnCount(), true,
                        ta.hasLobColumns(), ta.hasPrimaryKey(), ta.primaryKeyColumns(),
                        ta.indexCount(), ta.foreignKeyCount(), ta.checkConstraintCount(),
                        List.of(), List.of(), List.of(), List.of(), ta.findings()
                ));
            }
        }
        return List.copyOf(out);
    }

    private List<MappedColumn> identityMappedColumns(CanonicalTable table) {
        List<MappedColumn> list = new ArrayList<>(table.columns().size());
        for (var col : table.columns()) {
            list.add(new MappedColumn(col, col.name(), false, col.sourceNativeType(), false, col.nullable(),
                    col.fractionalSeconds() == null ? 6 : col.fractionalSeconds(), "OK", "EXPORT", null));
        }
        return list;
    }

    private List<CanonicalProgramObject> orderProgramObjects(List<CanonicalProgramObject> objects) {
        Map<String, CanonicalProgramObject> byKey = new LinkedHashMap<>();
        Map<String, List<String>> keysByName = new HashMap<>();
        List<SchemaExportOrdering.Node> nodes = new ArrayList<>();
        for (CanonicalProgramObject obj : objects) {
            String key = obj.kind() + ":" + obj.name();
            byKey.put(key, obj);
            keysByName.computeIfAbsent(obj.name().toUpperCase(Locale.ROOT), k -> new ArrayList<>()).add(key);
            nodes.add(new SchemaExportOrdering.Node(key, obj.kind(), obj.name()));
        }
        List<SchemaExportOrdering.Edge> edges = new ArrayList<>();
        for (CanonicalProgramObject obj : objects) {
            String key = obj.kind() + ":" + obj.name();
            Set<String> refs = new LinkedHashSet<>();
            for (String dep : obj.dependencies()) refs.add(dep.toUpperCase(Locale.ROOT));
            refs.addAll(SchemaExportScript.referencedIdentifiers(obj.sourceSql()));
            for (String ref : refs) {
                for (String targetKey : keysByName.getOrDefault(ref, List.of())) {
                    if (!targetKey.equals(key)) edges.add(new SchemaExportOrdering.Edge(key, targetKey));
                }
            }
        }
        SchemaExportOrdering.Result res = SchemaExportOrdering.order(nodes, edges);
        List<CanonicalProgramObject> ordered = new ArrayList<>(res.ordered().size());
        for (var node : res.ordered()) {
            CanonicalProgramObject obj = byKey.get(node.key());
            if (obj != null) ordered.add(obj);
        }
        return ordered;
    }

    private static String findCustomTableName(Map<String, String> mapping, String sourceName) {
        if (mapping == null || mapping.isEmpty()) return null;
        String direct = mapping.get(sourceName);
        if (direct != null && !direct.isBlank()) return direct.trim();
        for (var e : mapping.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(sourceName) && e.getValue() != null && !e.getValue().isBlank()) {
                return e.getValue().trim();
            }
        }
        return null;
    }
}
