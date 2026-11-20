package com.example.dbadmin.service.migration.mapping;

import com.example.dbadmin.dto.MigrationDtos.AssessmentFinding;
import com.example.dbadmin.service.migration.mapping.TypeMappingRegistry.MappedColumn;
import com.example.dbadmin.service.migration.model.CanonicalSchema.CanonicalCheckConstraint;
import com.example.dbadmin.service.migration.model.CanonicalSchema.CanonicalForeignKey;
import com.example.dbadmin.service.migration.model.CanonicalSchema.CanonicalIndex;
import com.example.dbadmin.service.migration.model.CanonicalSchema.CanonicalSequence;
import com.example.dbadmin.service.migration.model.CanonicalSchema.CanonicalTable;
import com.example.dbadmin.service.migration.model.CanonicalSchema.IndexColumn;
import com.example.dbadmin.service.migration.model.CanonicalSchema.IndexKind;
import com.example.dbadmin.service.migration.model.DbTargetDialect;
import com.example.dbadmin.service.migration.model.MigrationOptions;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 11 种目标方言的分阶段 DDL 生成器。
 *
 * <p>严格拆分阶段：
 * <ol>
 *   <li>基础建表（仅含列、主键、内联注释与方言引擎子句，不含二级索引与外键；SQLite 外键除外）；</li>
 *   <li>数据迁完后的二级索引、唯一索引、外键、CHECK 约束；</li>
 *   <li>独立序列创建与自增/序列当前值对齐语句。</li>
 * </ol></p>
 */
@Component
public class TargetDdlEmitter {

    public record TableDdlBundle(
            List<String> createTableStatements,
            List<String> postDataStatements,
            List<String> sequenceSyncStatements,
            List<AssessmentFinding> findings
    ) {
    }

    /** 生成目标库创建不存在 Schema / Database 的 DDL 语句（覆盖全部 11 种数据库方言）。 */
    public List<String> emitCreateSchemaStatements(DbTargetDialect target, String targetSchema, MigrationOptions options) {
        if (target == null || target == DbTargetDialect.SQLITE || targetSchema == null || targetSchema.isBlank()) {
            return List.of();
        }
        String trimmed = targetSchema.trim();
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c == '\0' || c == '\r' || c == '\n') {
                throw new IllegalArgumentException("目标 Schema/数据库名包含非法控制字符。");
            }
        }
        boolean caseSensitive = options == null || options.caseSensitiveCollationEnabled();
        return switch (target) {
            case MYSQL, MARIADB, OCEANBASE_MYSQL -> {
                StringBuilder sb = new StringBuilder("CREATE DATABASE IF NOT EXISTS ")
                        .append(target.quoteIdentifier(trimmed))
                        .append(" DEFAULT CHARACTER SET utf8mb4");
                if (caseSensitive) {
                    sb.append(" COLLATE utf8mb4_bin");
                }
                yield List.of(sb.toString());
            }
            case POSTGRESQL, H2 -> List.of("CREATE SCHEMA IF NOT EXISTS " + target.quoteIdentifier(trimmed));
            case CLICKHOUSE -> List.of("CREATE DATABASE IF NOT EXISTS " + target.quoteIdentifier(trimmed));
            case SQLSERVER -> {
                String escapedLit = trimmed.replace("'", "''");
                String quotedIdent = target.quoteIdentifier(trimmed).replace("'", "''");
                yield List.of("IF NOT EXISTS (SELECT 1 FROM sys.schemas WHERE name = N'" + escapedLit
                        + "') EXEC(N'CREATE SCHEMA " + quotedIdent + "')");
            }
            case DAMENG -> List.of("CREATE SCHEMA " + target.quoteIdentifier(trimmed));
            case ORACLE, OCEANBASE_ORACLE -> List.of(
                    "CREATE USER " + target.quoteIdentifier(trimmed) + " NO AUTHENTICATION",
                    "GRANT UNLIMITED TABLESPACE TO " + target.quoteIdentifier(trimmed)
            );
            case SQLITE -> List.of();
        };
    }

    public String emitDropTable(DbTargetDialect target, String schema, String targetTableName) {
        String qualified = target.qualifiedName(schema, targetTableName);
        if (target.isOracleFamily()) {
            return "DROP TABLE " + qualified + " CASCADE CONSTRAINTS";
        }
        if (target == DbTargetDialect.POSTGRESQL || target == DbTargetDialect.H2) {
            return "DROP TABLE IF EXISTS " + qualified + " CASCADE";
        }
        if (target == DbTargetDialect.SQLSERVER) {
            return "DROP TABLE IF EXISTS " + qualified;
        }
        return "DROP TABLE IF EXISTS " + qualified;
    }

    public String emitTruncateTable(DbTargetDialect target, String schema, String targetTableName) {
        String qualified = target.qualifiedName(schema, targetTableName);
        if (target == DbTargetDialect.SQLITE) {
            return "DELETE FROM " + qualified;
        }
        return "TRUNCATE TABLE " + qualified;
    }

    public TableDdlBundle emitTableDdl(
            DbTargetDialect source,
            DbTargetDialect target,
            String targetSchema,
            CanonicalTable sourceTable,
            String targetTableName,
            List<MappedColumn> columns,
            Map<String, String> sourceToTargetTableNames,
            Map<String, Map<String, String>> sourceToTargetColumnNames,
            MigrationOptions options
    ) {
        List<String> createStmts = new ArrayList<>();
        List<String> postStmts = new ArrayList<>();
        List<String> syncStmts = new ArrayList<>();
        List<AssessmentFinding> findings = new ArrayList<>();

        Map<String, MappedColumn> colMap = new LinkedHashMap<>();
        for (MappedColumn mc : columns) {
            colMap.put(mc.source().name().toLowerCase(Locale.ROOT), mc);
        }

        String qualified = target.qualifiedName(targetSchema, targetTableName);
        List<String> pkTargetCols = new ArrayList<>();
        for (String pkSrc : sourceTable.primaryKeyColumns()) {
            MappedColumn mc = colMap.get(pkSrc.toLowerCase(Locale.ROOT));
            if (mc != null) pkTargetCols.add(mc.targetColumnName());
        }

        boolean sqliteInlineIdentityPk = target == DbTargetDialect.SQLITE
                && pkTargetCols.size() == 1
                && columns.stream().anyMatch(c -> c.targetColumnName().equals(pkTargetCols.get(0)) && c.source().identity() != null);

        List<String> definitions = new ArrayList<>();
        List<String> standaloneCommentStmts = new ArrayList<>();
        boolean clickHouseOrderHasNullable = false;

        for (MappedColumn mc : columns) {
            StringBuilder def = new StringBuilder();
            def.append(target.quoteIdentifier(mc.targetColumnName())).append(' ');

            if (sqliteInlineIdentityPk && mc.targetColumnName().equals(pkTargetCols.get(0))) {
                def.append("INTEGER PRIMARY KEY AUTOINCREMENT");
            } else {
                def.append(mc.targetSqlType());
                if (target != DbTargetDialect.CLICKHOUSE) {
                    var defaultRes = DefaultExprTranslator.translate(source, target, mc.source(), mc.targetSqlType());
                    if (defaultRes.warning() != null) {
                        findings.add(new AssessmentFinding("WARNING", "DEFAULT_SKIPPED", "TABLE", sourceTable.name(),
                                mc.source().name(), defaultRes.warning(), "如需默认值可在目标库建表后补充"));
                    }
                    // Oracle / DM8 / SQL Server 要求 DEFAULT 与 IDENTITY/GENERATED 子句写在 NOT NULL 之前
                    if (defaultRes.sql().isPresent() && mc.source().identity() == null) {
                        def.append(" DEFAULT ").append(defaultRes.sql().get());
                    }
                    if (mc.source().identity() != null && (target.isOracleFamily() || target == DbTargetDialect.SQLSERVER)) {
                        appendIdentityClause(def, target, mc);
                    }
                    if (!mc.nullable()) {
                        def.append(" NOT NULL");
                    }
                    if (mc.source().identity() != null && !target.isOracleFamily() && target != DbTargetDialect.SQLSERVER) {
                        appendIdentityClause(def, target, mc);
                    }
                    if (options.commentsEnabled() && mc.source().comment() != null && !mc.source().comment().isBlank()) {
                        if (target.isMySqlFamily()) {
                            def.append(" COMMENT ").append(quoteString(mc.source().comment(), target));
                        } else if (target.supportsColumnComments()) {
                            standaloneCommentStmts.add(columnCommentSql(target, targetSchema, targetTableName, mc.targetColumnName(), mc.source().comment()));
                        }
                    }
                } else {
                    if (options.commentsEnabled() && mc.source().comment() != null && !mc.source().comment().isBlank()) {
                        def.append(" COMMENT ").append(quoteString(mc.source().comment(), target));
                    }
                    if (pkTargetCols.contains(mc.targetColumnName()) && mc.nullable()) {
                        clickHouseOrderHasNullable = true;
                    }
                }
            }
            definitions.add(def.toString());
        }

        // 主键
        if (!pkTargetCols.isEmpty() && !sqliteInlineIdentityPk && target != DbTargetDialect.CLICKHOUSE) {
            String pkList = pkTargetCols.stream().map(target::quoteIdentifier).collect(Collectors.joining(", "));
            if (target.isMySqlFamily() && pkHasLob(columns, pkTargetCols)) {
                findings.add(new AssessmentFinding("WARNING", "MYSQL_PK_LOB", "TABLE", sourceTable.name(), null,
                        "主键包含 TEXT/BLOB 列，在 MySQL 上将添加前缀长度(191)", "建议改用明确长度的 VARCHAR 主键"));
                pkList = pkTargetCols.stream().map(c -> mySqlKeyExpr(c, columns, 191)).collect(Collectors.joining(", "));
            }
            definitions.add("PRIMARY KEY (" + pkList + ")");
        }

        // SQLite 不支持事后 ALTER TABLE ADD FOREIGN KEY，因此内联到 CREATE TABLE 中（写数据时关 PRAGMA foreign_keys）
        if (target == DbTargetDialect.SQLITE && options.foreignKeysEnabled()) {
            for (CanonicalForeignKey fk : sourceTable.foreignKeys()) {
                String inlineFk = buildInlineSqliteFk(fk, sourceTable.name(), colMap, sourceToTargetTableNames, sourceToTargetColumnNames, options, findings);
                if (inlineFk != null) definitions.add(inlineFk);
            }
        }

        StringBuilder createTable = new StringBuilder();
        createTable.append("CREATE TABLE ").append(qualified).append(" (\n  ")
                .append(String.join(",\n  ", definitions))
                .append("\n)");

        if (target.isMySqlFamily()) {
            if (target != DbTargetDialect.OCEANBASE_MYSQL) {
                createTable.append(" ENGINE=InnoDB");
            }
            createTable.append(" DEFAULT CHARSET=utf8mb4");
            if (options.caseSensitiveCollationEnabled()) {
                createTable.append(" COLLATE=utf8mb4_bin");
            }
            if (options.commentsEnabled() && sourceTable.comment() != null && !sourceTable.comment().isBlank()) {
                createTable.append(" COMMENT=").append(quoteString(sourceTable.comment(), target));
            }
        } else if (target == DbTargetDialect.CLICKHOUSE) {
            String orderBy = pkTargetCols.isEmpty()
                    ? "tuple()"
                    : "(" + pkTargetCols.stream().map(target::quoteIdentifier).collect(Collectors.joining(", ")) + ")";
            createTable.append(" ENGINE = MergeTree() ORDER BY ").append(orderBy);
            if (clickHouseOrderHasNullable) {
                createTable.append(" SETTINGS allow_nullable_key = 1");
            }
            if (options.commentsEnabled() && sourceTable.comment() != null && !sourceTable.comment().isBlank()) {
                createTable.append(" COMMENT ").append(quoteString(sourceTable.comment(), target));
            }
        }

        createStmts.add(createTable.toString());

        if (options.commentsEnabled() && sourceTable.comment() != null && !sourceTable.comment().isBlank()
                && target.supportsTableComments() && !target.isMySqlFamily() && target != DbTargetDialect.CLICKHOUSE) {
            createStmts.add(tableCommentSql(target, targetSchema, targetTableName, sourceTable.comment()));
        }
        createStmts.addAll(standaloneCommentStmts);

        // 阶段 3：二级索引（数据灌完后建）
        if (options.indexesEnabled() && !sourceTable.indexes().isEmpty()) {
            if (!target.supportsSecondaryIndexes()) {
                findings.add(new AssessmentFinding("WARNING", "INDEXES_SKIPPED_TARGET", "TABLE", sourceTable.name(), null,
                        "目标库 " + target.code() + " 不支持传统二级索引，已跳过 " + sourceTable.indexes().size() + " 个索引",
                        "ClickHouse 依赖 MergeTree ORDER BY 排序键优化查询"));
            } else {
                for (CanonicalIndex idx : sourceTable.indexes()) {
                    String idxSql = emitIndexSql(target, targetSchema, sourceTable.name(), targetTableName, idx, colMap, options, findings);
                    if (idxSql != null) postStmts.add(idxSql);
                }
            }
        }

        // 阶段 3：外键（SQLite 已内联，ClickHouse 不支持）
        if (options.foreignKeysEnabled() && !sourceTable.foreignKeys().isEmpty() && target != DbTargetDialect.SQLITE) {
            if (!target.supportsForeignKeys()) {
                findings.add(new AssessmentFinding("WARNING", "FK_SKIPPED_TARGET", "TABLE", sourceTable.name(), null,
                        "目标库 " + target.code() + " 不支持外键约束，已跳过 " + sourceTable.foreignKeys().size() + " 个外键", null));
            } else {
                for (CanonicalForeignKey fk : sourceTable.foreignKeys()) {
                    String fkSql = emitForeignKeySql(target, targetSchema, sourceTable.name(), targetTableName, fk, colMap,
                            sourceToTargetTableNames, sourceToTargetColumnNames, options, findings);
                    if (fkSql != null) postStmts.add(fkSql);
                }
            }
        }

        // 阶段 3：CHECK 约束
        if (options.checkConstraintsEnabled() && !sourceTable.checkConstraints().isEmpty()) {
            if (!target.supportsCheckConstraints()) {
                findings.add(new AssessmentFinding("WARNING", "CHECK_SKIPPED_TARGET", "TABLE", sourceTable.name(), null,
                        "目标库 " + target.code() + " 不支持 CHECK 约束，已跳过 " + sourceTable.checkConstraints().size() + " 条", null));
            } else if (source != null && source.sameFamily(target)) {
                for (CanonicalCheckConstraint chk : sourceTable.checkConstraints()) {
                    var chkId = IdentifierMapper.map(chk.name(), target, options.identifierCasePolicy());
                    postStmts.add("ALTER TABLE " + qualified + " ADD CONSTRAINT " + target.quoteIdentifier(chkId.targetName())
                            + " CHECK (" + chk.expression() + ")");
                }
            } else {
                findings.add(new AssessmentFinding("WARNING", "CHECK_CROSS_FAMILY_MANUAL", "TABLE", sourceTable.name(), null,
                        "表包含 " + sourceTable.checkConstraints().size() + " 条 CHECK 约束，跨方言表达式语法存在差异，默认不自动下发，请核对后补充",
                        "常见差异：Oracle/PG/MySQL 函数与引号写法不同"));
            }
        }

        // 阶段 4：自增列对齐提示模板（执行期由编排器根据实测 MAX(col)+1 生成精确同步 SQL）
        for (MappedColumn mc : columns) {
            if (mc.source().identity() != null) {
                String sync = emitIdentitySyncTemplate(target, targetSchema, targetTableName, mc.targetColumnName(), mc.source().identity().startWith());
                if (sync != null) syncStmts.add(sync);
            }
        }

        return new TableDdlBundle(List.copyOf(createStmts), List.copyOf(postStmts), List.copyOf(syncStmts), List.copyOf(findings));
    }

    public String emitSequenceCreateSql(DbTargetDialect target, String targetSchema, CanonicalSequence seq, MigrationOptions options) {
        if (!target.supportsSequences()) return null;
        var mapped = IdentifierMapper.map(seq.name(), target, options.identifierCasePolicy());
        String qualified = target.qualifiedName(targetSchema, mapped.targetName());
        long startWith = Math.max(1L, seq.currentValue() + Math.max(1L, seq.incrementBy()));
        long inc = seq.incrementBy() == 0 ? 1L : seq.incrementBy();
        StringBuilder sb = new StringBuilder("CREATE SEQUENCE ").append(qualified)
                .append(" START WITH ").append(startWith)
                .append(" INCREMENT BY ").append(inc);
        if (seq.minValue() != null) sb.append(" MINVALUE ").append(seq.minValue());
        if (seq.maxValue() != null && seq.maxValue() < Long.MAX_VALUE - 1 && target != DbTargetDialect.H2) {
            sb.append(" MAXVALUE ").append(seq.maxValue());
        }
        if (seq.cycle()) sb.append(" CYCLE");
        if (seq.cacheSize() != null && seq.cacheSize() > 1) sb.append(" CACHE ").append(seq.cacheSize());
        return sb.toString();
    }

    public String emitIdentitySyncSql(DbTargetDialect target, String targetSchema, String targetTable, String targetCol, long nextValue) {
        String qualified = target.qualifiedName(targetSchema, targetTable);
        long safeNext = Math.max(1L, nextValue);
        return switch (target) {
            case MYSQL, OCEANBASE_MYSQL, MARIADB -> "ALTER TABLE " + qualified + " AUTO_INCREMENT = " + safeNext;
            case POSTGRESQL -> "SELECT setval(pg_get_serial_sequence(" + quoteString(qualified, target) + ", "
                    + quoteString(targetCol, target) + "), " + Math.max(1L, safeNext - 1L) + ", true)";
            case SQLSERVER -> "DBCC CHECKIDENT (" + quoteString(qualified, target) + ", RESEED, " + Math.max(0L, safeNext - 1L) + ")";
            case H2 -> "ALTER TABLE " + qualified + " ALTER COLUMN " + target.quoteIdentifier(targetCol) + " RESTART WITH " + safeNext;
            case ORACLE, OCEANBASE_ORACLE ->
                    "ALTER TABLE " + qualified + " MODIFY (" + target.quoteIdentifier(targetCol) + " GENERATED BY DEFAULT ON NULL AS IDENTITY (START WITH " + safeNext + "))";
            default -> null;
        };
    }

    private String emitIdentitySyncTemplate(DbTargetDialect target, String schema, String table, String col, long startWith) {
        return emitIdentitySyncSql(target, schema, table, col, Math.max(1L, startWith));
    }

    private void appendIdentityClause(StringBuilder def, DbTargetDialect target, MappedColumn mc) {
        long seed = mc.source().identity().startWith();
        long inc = mc.source().identity().incrementBy() == 0 ? 1 : mc.source().identity().incrementBy();
        switch (target) {
            case MYSQL, OCEANBASE_MYSQL, MARIADB -> def.append(" AUTO_INCREMENT");
            case POSTGRESQL, H2 -> def.append(" GENERATED BY DEFAULT AS IDENTITY");
            case ORACLE, OCEANBASE_ORACLE -> def.append(" GENERATED BY DEFAULT ON NULL AS IDENTITY");
            case DAMENG -> def.append(" IDENTITY(").append(Math.max(1, seed)).append(", ").append(Math.max(1, inc)).append(")");
            case SQLSERVER -> def.append(" IDENTITY(").append(Math.max(1, seed)).append(", ").append(Math.max(1, inc)).append(")");
            default -> { }
        }
    }

    private String emitIndexSql(
            DbTargetDialect target,
            String targetSchema,
            String sourceTableName,
            String targetTableName,
            CanonicalIndex idx,
            Map<String, MappedColumn> colMap,
            MigrationOptions options,
            List<AssessmentFinding> findings
    ) {
        if (idx.kind() == IndexKind.BITMAP && !target.isOracleFamily()) {
            findings.add(new AssessmentFinding("WARNING", "BITMAP_INDEX_CONVERTED", "INDEX", sourceTableName, idx.name(),
                    "源位图索引 " + idx.name() + " 在目标库 " + target.code() + " 不受支持，已自动降级为普通 B-Tree 索引", null));
        }
        if (idx.kind() == IndexKind.EXPRESSION || idx.columns().stream().anyMatch(c -> c.expression() != null && !c.expression().isBlank())) {
            findings.add(new AssessmentFinding("WARNING", "EXPRESSION_INDEX_SKIPPED", "INDEX", sourceTableName, idx.name(),
                    "函数/表达式索引 " + idx.name() + " 跨方言语法不通用，已跳过，请在迁移后按目标方言语法重建", null));
            return null;
        }
        List<String> colParts = new ArrayList<>();
        int totalKeyCols = idx.columns().size();
        for (IndexColumn ic : idx.columns()) {
            if (ic.name() == null) continue;
            MappedColumn mc = colMap.get(ic.name().toLowerCase(Locale.ROOT));
            if (mc == null) continue;
            String colExpr = target.quoteIdentifier(mc.targetColumnName());
            if (target.isMySqlFamily()) {
                int prefix = resolveMySqlIndexPrefix(mc, ic.prefixLength(), totalKeyCols);
                if (prefix > 0) {
                    colExpr = colExpr + "(" + prefix + ")";
                    if (ic.prefixLength() == null) {
                        findings.add(new AssessmentFinding("WARNING", "MYSQL_INDEX_PREFIX_ADDED", "INDEX", sourceTableName, idx.name(),
                                "索引 " + idx.name() + " 包含宽字符/TEXT/BLOB 列 " + mc.targetColumnName() + "，已自动补前缀长度(" + prefix + ")", null));
                    }
                }
            }
            if (ic.descending()) {
                colExpr = colExpr + " DESC";
            }
            colParts.add(colExpr);
        }
        if (colParts.isEmpty()) return null;

        // Oracle/PG/SQLite/H2 索引名在 Schema 内全局唯一，MySQL/MSSQL 在表内唯一；截断并保持确定性
        String rawIdxName = (target.isMySqlFamily() || target == DbTargetDialect.SQLSERVER)
                ? idx.name()
                : idx.name();
        var mappedIdx = IdentifierMapper.map(rawIdxName, target, options.identifierCasePolicy());
        String qualifiedTable = target.qualifiedName(targetSchema, targetTableName);
        String idxNameSql = (target.isOracleFamily() && targetSchema != null && !targetSchema.isBlank())
                ? target.qualifiedName(targetSchema, mappedIdx.targetName())
                : target.quoteIdentifier(mappedIdx.targetName());

        return "CREATE " + (idx.unique() ? "UNIQUE " : "") + "INDEX " + idxNameSql
                + " ON " + qualifiedTable + " (" + String.join(", ", colParts) + ")";
    }

    private String emitForeignKeySql(
            DbTargetDialect target,
            String targetSchema,
            String sourceTableName,
            String targetTableName,
            CanonicalForeignKey fk,
            Map<String, MappedColumn> colMap,
            Map<String, String> sourceToTargetTableNames,
            Map<String, Map<String, String>> sourceToTargetColumnNames,
            MigrationOptions options,
            List<AssessmentFinding> findings
    ) {
        String refTargetTable = sourceToTargetTableNames.get(fk.refTable().toLowerCase(Locale.ROOT));
        if (refTargetTable == null) {
            findings.add(new AssessmentFinding("WARNING", "FK_REF_OUT_OF_SCOPE", "FOREIGN_KEY", sourceTableName, fk.name(),
                    "外键 " + fk.name() + " 引用表 " + fk.refTable() + " 不在本次迁移表清单中，已跳过该外键", null));
            return null;
        }
        Map<String, String> refColMap = sourceToTargetColumnNames.getOrDefault(fk.refTable().toLowerCase(Locale.ROOT), Map.of());
        List<String> localCols = new ArrayList<>();
        for (String c : fk.localColumns()) {
            MappedColumn mc = colMap.get(c.toLowerCase(Locale.ROOT));
            if (mc == null) return null;
            localCols.add(target.quoteIdentifier(mc.targetColumnName()));
        }
        List<String> refCols = new ArrayList<>();
        for (String c : fk.refColumns()) {
            String mapped = refColMap.getOrDefault(c.toLowerCase(Locale.ROOT),
                    IdentifierMapper.map(c, target, options.identifierCasePolicy()).targetName());
            refCols.add(target.quoteIdentifier(mapped));
        }
        var fkId = IdentifierMapper.map(fk.name(), target, options.identifierCasePolicy());
        StringBuilder sb = new StringBuilder();
        sb.append("ALTER TABLE ").append(target.qualifiedName(targetSchema, targetTableName))
                .append(" ADD CONSTRAINT ").append(target.quoteIdentifier(fkId.targetName()))
                .append(" FOREIGN KEY (").append(String.join(", ", localCols)).append(")")
                .append(" REFERENCES ").append(target.qualifiedName(targetSchema, refTargetTable))
                .append(" (").append(String.join(", ", refCols)).append(")");
        if ("CASCADE".equalsIgnoreCase(fk.onDelete()) || "SET NULL".equalsIgnoreCase(fk.onDelete())) {
            sb.append(" ON DELETE ").append(fk.onDelete().toUpperCase(Locale.ROOT));
        }
        if (!target.isOracleFamily() && ("CASCADE".equalsIgnoreCase(fk.onUpdate()) || "SET NULL".equalsIgnoreCase(fk.onUpdate()))) {
            sb.append(" ON UPDATE ").append(fk.onUpdate().toUpperCase(Locale.ROOT));
        }
        return sb.toString();
    }

    private String buildInlineSqliteFk(
            CanonicalForeignKey fk,
            String sourceTableName,
            Map<String, MappedColumn> colMap,
            Map<String, String> sourceToTargetTableNames,
            Map<String, Map<String, String>> sourceToTargetColumnNames,
            MigrationOptions options,
            List<AssessmentFinding> findings
    ) {
        String refTargetTable = sourceToTargetTableNames.get(fk.refTable().toLowerCase(Locale.ROOT));
        if (refTargetTable == null) {
            findings.add(new AssessmentFinding("WARNING", "FK_REF_OUT_OF_SCOPE", "FOREIGN_KEY", sourceTableName, fk.name(),
                    "外键 " + fk.name() + " 引用表 " + fk.refTable() + " 不在本次迁移范围，已跳过", null));
            return null;
        }
        Map<String, String> refColMap = sourceToTargetColumnNames.getOrDefault(fk.refTable().toLowerCase(Locale.ROOT), Map.of());
        List<String> localCols = new ArrayList<>();
        for (String c : fk.localColumns()) {
            MappedColumn mc = colMap.get(c.toLowerCase(Locale.ROOT));
            if (mc == null) return null;
            localCols.add(DbTargetDialect.SQLITE.quoteIdentifier(mc.targetColumnName()));
        }
        List<String> refCols = new ArrayList<>();
        for (String c : fk.refColumns()) {
            String mapped = refColMap.getOrDefault(c.toLowerCase(Locale.ROOT),
                    IdentifierMapper.map(c, DbTargetDialect.SQLITE, options.identifierCasePolicy()).targetName());
            refCols.add(DbTargetDialect.SQLITE.quoteIdentifier(mapped));
        }
        StringBuilder sb = new StringBuilder();
        sb.append("FOREIGN KEY (").append(String.join(", ", localCols)).append(")")
                .append(" REFERENCES ").append(DbTargetDialect.SQLITE.quoteIdentifier(refTargetTable))
                .append(" (").append(String.join(", ", refCols)).append(")");
        if ("CASCADE".equalsIgnoreCase(fk.onDelete()) || "SET NULL".equalsIgnoreCase(fk.onDelete())) {
            sb.append(" ON DELETE ").append(fk.onDelete().toUpperCase(Locale.ROOT));
        }
        return sb.toString();
    }

    private static int resolveMySqlIndexPrefix(MappedColumn mc, Integer existingPrefix, int totalKeyCols) {
        if (existingPrefix != null && existingPrefix > 0) return existingPrefix;
        String u = mc.targetSqlType().toUpperCase(Locale.ROOT);
        int perColCharCap = Math.max(64, 700 / Math.max(1, totalKeyCols));
        if (u.contains("TEXT") || u.contains("BLOB")) {
            return Math.min(255, perColCharCap);
        }
        if (u.startsWith("VARCHAR(") || u.startsWith("CHAR(")) {
            int estBytes = TypeMappingRegistry.estimateMySqlColumnRowBytes(u);
            if (estBytes > perColCharCap * 4) {
                return perColCharCap;
            }
        }
        return 0;
    }

    private static boolean pkHasLob(List<MappedColumn> columns, List<String> pkCols) {
        for (MappedColumn mc : columns) {
            if (pkCols.contains(mc.targetColumnName())) {
                String u = mc.targetSqlType().toUpperCase(Locale.ROOT);
                if (u.contains("TEXT") || u.contains("BLOB")) return true;
            }
        }
        return false;
    }

    private static String mySqlKeyExpr(String colName, List<MappedColumn> columns, int prefix) {
        for (MappedColumn mc : columns) {
            if (mc.targetColumnName().equals(colName)) {
                String u = mc.targetSqlType().toUpperCase(Locale.ROOT);
                if (u.contains("TEXT") || u.contains("BLOB")) {
                    return DbTargetDialect.MYSQL.quoteIdentifier(colName) + "(" + prefix + ")";
                }
            }
        }
        return DbTargetDialect.MYSQL.quoteIdentifier(colName);
    }

    private static String tableCommentSql(DbTargetDialect target, String schema, String table, String comment) {
        if (target == DbTargetDialect.SQLSERVER) {
            return "EXEC sys.sp_addextendedproperty @name=N'MS_Description', @value=" + quoteString(comment, target)
                    + ", @level0type=N'SCHEMA', @level0name=" + quoteString(schema == null || schema.isBlank() ? "dbo" : schema, target)
                    + ", @level1type=N'TABLE', @level1name=" + quoteString(table, target);
        }
        return "COMMENT ON TABLE " + target.qualifiedName(schema, table) + " IS " + quoteString(comment, target);
    }

    private static String columnCommentSql(DbTargetDialect target, String schema, String table, String column, String comment) {
        if (target == DbTargetDialect.SQLSERVER) {
            return "EXEC sys.sp_addextendedproperty @name=N'MS_Description', @value=" + quoteString(comment, target)
                    + ", @level0type=N'SCHEMA', @level0name=" + quoteString(schema == null || schema.isBlank() ? "dbo" : schema, target)
                    + ", @level1type=N'TABLE', @level1name=" + quoteString(table, target)
                    + ", @level2type=N'COLUMN', @level2name=" + quoteString(column, target);
        }
        return "COMMENT ON COLUMN " + target.qualifiedName(schema, table) + "." + target.quoteIdentifier(column)
                + " IS " + quoteString(comment, target);
    }

    private static String quoteString(String text, DbTargetDialect target) {
        String escaped = text.replace("'", "''");
        if (target.isMySqlFamily() || target == DbTargetDialect.CLICKHOUSE) {
            escaped = escaped.replace("\\", "\\\\");
        }
        if (target == DbTargetDialect.SQLSERVER) {
            return "N'" + escaped + "'";
        }
        return "'" + escaped + "'";
    }
}
