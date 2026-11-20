package com.example.dbadmin.service.migration;

import com.example.dbadmin.service.migration.introspect.CanonicalTypeNormalizer;
import com.example.dbadmin.service.migration.mapping.IdentifierMapper;
import com.example.dbadmin.service.migration.mapping.MigrationAssessor;
import com.example.dbadmin.service.migration.mapping.ProgramObjectTranslator;
import com.example.dbadmin.service.migration.mapping.TargetDdlEmitter;
import com.example.dbadmin.service.migration.mapping.TypeMappingRegistry;
import com.example.dbadmin.service.migration.model.CanonicalSchema;
import com.example.dbadmin.service.migration.model.CanonicalSchema.*;
import com.example.dbadmin.service.migration.model.CanonicalTypeKind;
import com.example.dbadmin.service.migration.model.DbTargetDialect;
import com.example.dbadmin.service.migration.model.MigrationOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TypeMappingAndDdlTest {
    private final TypeMappingRegistry typeMapper = new TypeMappingRegistry();
    private final TargetDdlEmitter ddlEmitter = new TargetDdlEmitter();
    private final ProgramObjectTranslator programTranslator = new ProgramObjectTranslator(null, null, null, null);
    private final MigrationAssessor assessor = new MigrationAssessor(typeMapper, ddlEmitter, programTranslator);

    @Test
    void oracleDateAndNumberEdgeCasesMapWithoutDataLoss() {
        // Oracle DATE 含时分秒 → 归一成 DATETIME(0)，绝不丢时间
        var dateNorm = CanonicalTypeNormalizer.normalize(DbTargetDialect.ORACLE, "DATE", null, null, LengthUnit.NONE, null, null, false, false);
        assertThat(dateNorm.kind()).isEqualTo(CanonicalTypeKind.DATETIME);
        assertThat(dateNorm.fractionalSeconds()).isEqualTo(0);

        // Oracle NUMBER(10,0) 超出有符号 INT 上限，必须归一为 INT64
        var num10 = CanonicalTypeNormalizer.normalize(DbTargetDialect.ORACLE, "NUMBER", null, null, LengthUnit.NONE, 10, 0, false, false);
        assertThat(num10.kind()).isEqualTo(CanonicalTypeKind.INT64);

        // Oracle 裸 NUMBER（precision=0, scale=-127）标记 unboundedDecimal
        var bareNum = CanonicalTypeNormalizer.normalize(DbTargetDialect.ORACLE, "NUMBER", null, null, LengthUnit.NONE, 0, -127, true, false);
        assertThat(bareNum.kind()).isEqualTo(CanonicalTypeKind.DECIMAL);
        assertThat(bareNum.unboundedDecimal()).isTrue();

        CanonicalTable table = sampleOracleTable();
        // 映射到 MySQL / OceanBase MySQL
        for (DbTargetDialect mysqlTarget : List.of(DbTargetDialect.MYSQL, DbTargetDialect.OCEANBASE_MYSQL)) {
            var cols = typeMapper.mapTableColumns(DbTargetDialect.ORACLE, mysqlTarget, table, MigrationOptions.defaults());
            Map<String, TypeMappingRegistry.MappedColumn> byName = cols.stream()
                    .collect(java.util.stream.Collectors.toMap(c -> c.source().name(), c -> c));

            assertThat(byName.get("CREATED_AT").targetSqlType()).isEqualTo("DATETIME");
            assertThat(byName.get("BIG_SEQ").targetSqlType()).isEqualTo("BIGINT");
            assertThat(byName.get("AMOUNT_UNBOUNDED").targetSqlType()).isEqualTo("DECIMAL(38,10)");
            assertThat(byName.get("AMOUNT_UNBOUNDED").severity()).isEqualTo("WARNING");
            assertThat(byName.get("ODD_SCALE").targetSqlType()).isEqualTo("DECIMAL(5,5)");
            assertThat(byName.get("DOC_CLOB").targetSqlType()).isEqualTo("LONGTEXT");
            assertThat(byName.get("DOC_BLOB").targetSqlType()).isEqualTo("LONGBLOB");
            assertThat(byName.get("TS_NANO").targetSqlType()).isEqualTo("DATETIME(6)");
            assertThat(byName.get("TS_NANO").severity()).isEqualTo("WARNING");
            assertThat(byName.get("TS_TZ").targetSqlType()).isEqualTo("DATETIME(6)");
            assertThat(byName.get("TS_TZ").findingCode()).isEqualTo("TIMESTAMP_TZ_DOWNGRADED");
        }

        // 映射到 PostgreSQL：裸 NUMBER 无损映射 NUMERIC，BLOB -> BYTEA
        var pgCols = typeMapper.mapTableColumns(DbTargetDialect.ORACLE, DbTargetDialect.POSTGRESQL, table, MigrationOptions.defaults());
        Map<String, TypeMappingRegistry.MappedColumn> pgMap = pgCols.stream()
                .collect(java.util.stream.Collectors.toMap(c -> c.source().name(), c -> c));
        assertThat(pgMap.get("AMOUNT_UNBOUNDED").targetSqlType()).isEqualTo("NUMERIC");
        assertThat(pgMap.get("AMOUNT_UNBOUNDED").severity()).isEqualTo("OK");
        assertThat(pgMap.get("DOC_BLOB").targetSqlType()).isEqualTo("BYTEA");
        assertThat(pgMap.get("TS_TZ").targetSqlType()).isEqualTo("TIMESTAMPTZ(6)");

        // 同家族 Oracle -> OceanBase Oracle 高保真保留原生类型
        var obOraCols = typeMapper.mapTableColumns(DbTargetDialect.ORACLE, DbTargetDialect.OCEANBASE_ORACLE, table, MigrationOptions.defaults());
        Map<String, TypeMappingRegistry.MappedColumn> obOraMap = obOraCols.stream()
                .collect(java.util.stream.Collectors.toMap(c -> c.source().name(), c -> c));
        assertThat(obOraMap.get("CREATED_AT").targetSqlType()).isEqualTo("DATE");
        assertThat(obOraMap.get("DOC_BLOB").targetSqlType()).isEqualTo("BLOB");
        assertThat(obOraMap.get("AMOUNT_UNBOUNDED").targetSqlType()).isEqualTo("NUMBER");
    }

    @Test
    void mysqlRowWidthGuardPromotesWideVarcharsAndAddsIndexPrefix() {
        // 6 个 VARCHAR2(4000) 在 utf8mb4 下估算 6 * 16002 = 96012 > 58000，应自动升级部分非主键列为 TEXT
        List<CanonicalColumn> cols = new java.util.ArrayList<>();
        cols.add(col("ID", CanonicalTypeKind.INT64, "NUMBER(18,0)", null, 18, 0, false, false));
        for (int i = 1; i <= 6; i++) {
            cols.add(col("WIDE_" + i, CanonicalTypeKind.VARCHAR, "VARCHAR2(4000 CHAR)", 4000, null, null, false, true));
        }
        CanonicalTable wideTable = new CanonicalTable(
                "t0001_wide", "WIDE_TBL", "宽表测试", cols, "PK_WIDE", List.of("ID"),
                List.of(new CanonicalIndex("IDX_WIDE_1", List.of(new IndexColumn("WIDE_1", false, null, null)), false, IndexKind.BTREE, null)),
                List.of(), List.of(), null, 100L, 1024L
        );

        var mapped = typeMapper.mapTableColumns(DbTargetDialect.ORACLE, DbTargetDialect.MYSQL, wideTable, MigrationOptions.defaults());
        long promotedCount = mapped.stream().filter(c -> "MYSQL_ROW_WIDTH_PROMOTED".equals(c.findingCode())).count();
        assertThat(promotedCount).isGreaterThanOrEqualTo(3);

        var ddl = ddlEmitter.emitTableDdl(DbTargetDialect.ORACLE, DbTargetDialect.MYSQL, "demo", wideTable, "WIDE_TBL",
                mapped, Map.of("wide_tbl", "WIDE_TBL"), Map.of(), MigrationOptions.defaults());
        assertThat(ddl.createTableStatements().get(0)).contains("COLLATE=utf8mb4_bin");
        assertThat(ddl.postDataStatements()).anySatisfy(sql -> assertThat(sql).contains("CREATE INDEX").contains("`WIDE_1`("));
    }

    @ParameterizedTest
    @EnumSource(DbTargetDialect.class)
    void all11TargetDialectsAssessAndEmitValidDdlFromOracleSchema(DbTargetDialect target) {
        CanonicalSchema schema = new CanonicalSchema(
                1, "oracle", "ORACLE", "Oracle 19c", "AL32UTF8", "HR", "SCN:123456",
                List.of(sampleOracleTable()),
                List.of(new CanonicalSequence("SEQ_ORDERS", 100, 1, 1L, 9999999L, false, 20L, null, null)),
                List.of(new CanonicalProgramObject("VIEW", "V_ACTIVE_DOCS",
                        "CREATE OR REPLACE VIEW HR.V_ACTIVE_DOCS AS SELECT ID, NVL(TITLE, 'N/A') AS TITLE, SYSDATE AS NOW_TS FROM HR.DOCS",
                        List.of("DOCS"), "VALID"))
        );

        MigrationOptions opts = new MigrationOptions(
                "LOWER", "FAIL", true, true, true, true, true, true, true, true,
                true, "UTC", "FAIL", "FAIL", 38, 10, true, true, "L3_FULL_HASH",
                2, List.of(), List.of(), Map.of(), Map.of(), Map.of(), null, null
        );
        var plan = assessor.assess("tok-1", "ONLINE_DIRECT", schema, target, target == DbTargetDialect.SQLITE ? "" : "target_db", opts, false);

        assertThat(plan.response().canExecute()).isTrue();
        assertThat(plan.tables()).hasSize(1);
        var tbl = plan.tables().get(0);
        assertThat(tbl.targetTableName()).isEqualTo("docs");
        assertThat(tbl.ddl().createTableStatements()).isNotEmpty();
        String createSql = tbl.ddl().createTableStatements().get(0);
        assertThat(createSql).startsWith("CREATE TABLE ");
        if (target == DbTargetDialect.CLICKHOUSE) {
            assertThat(createSql).contains("ENGINE = MergeTree()");
            assertThat(createSql).contains("Nullable(");
        }
        if (target.isMySqlFamily()) {
            assertThat(createSql).contains("LONGTEXT").contains("LONGBLOB");
        }
        // 视图应生成翻译结果
        assertThat(plan.programObjects()).hasSize(1);
        assertThat(plan.programObjects().get(0).targetSql()).isNotBlank();
    }

    @Test
    void identifierMapperHandlesLengthTruncationDeterministically() {
        String longName = "VERY_LONG_ORACLE_TABLE_NAME_EXCEEDING_SIXTY_FOUR_CHARACTERS_LIMIT_BY_A_LOT_12345";
        var a = IdentifierMapper.map(longName, DbTargetDialect.MYSQL, "LOWER");
        var b = IdentifierMapper.map(longName, DbTargetDialect.MYSQL, "LOWER");
        assertThat(a.truncated()).isTrue();
        assertThat(a.targetName().length()).isLessThanOrEqualTo(64);
        assertThat(a.targetName()).isEqualTo(b.targetName());
        assertThat(a.targetName()).isEqualTo(a.targetName().toLowerCase());
    }

    @Test
    void oracleIdentityBeforeNotNullAndBooleanDefaultTranslatedForPostgres() {
        var identityCol = new CanonicalColumn(
                "id", CanonicalTypeKind.INT64, "BIGINT", null, LengthUnit.NONE,
                null, null, false, false, false, null, false,
                DefaultExprKind.NONE, null, new IdentitySpec(false, 1, 1), null, null
        );
        var boolCol = new CanonicalColumn(
                "enabled", CanonicalTypeKind.BOOLEAN, "TINYINT(1)", null, LengthUnit.NONE,
                null, null, false, false, false, null, false,
                DefaultExprKind.LITERAL_NUMBER, "1", null, null, null
        );
        var tbl = new CanonicalTable(
                "t0001_orgs", "orgs", null, List.of(identityCol, boolCol),
                "PRIMARY", List.of("id"), List.of(), List.of(), List.of(), null, 10L, null
        );
        var schema = new CanonicalSchema(1, "mysql", "MYSQL", "8.4", "utf8mb4", "src", null,
                List.of(tbl),
                List.of(new CanonicalSequence("SEQ_HUGE", 10L, 1L, 1L, Long.MAX_VALUE, false, 20L, null, null)),
                List.of());

        var toOra = assessor.assess("tok-ora", "ONLINE_DIRECT", schema, DbTargetDialect.ORACLE, "APP",
                new MigrationOptions("UPPER", "FAIL", true, true, true, true, true, true, false, true,
                        true, "UTC", "ALLOW_NULL", "FAIL", 38, 10, false, true, "L3_FULL_HASH",
                        2, List.of(), List.of(), Map.of(), Map.of(), Map.of(), null, null), false);
        String oraDdl = toOra.tables().get(0).ddl().createTableStatements().get(0);
        assertThat(oraDdl).contains("GENERATED BY DEFAULT ON NULL AS IDENTITY NOT NULL");

        var toPg = assessor.assess("tok-pg", "ONLINE_DIRECT", schema, DbTargetDialect.POSTGRESQL, "public",
                MigrationOptions.defaults(), false);
        String pgDdl = toPg.tables().get(0).ddl().createTableStatements().get(0);
        assertThat(pgDdl).contains("\"enabled\" BOOLEAN DEFAULT TRUE NOT NULL");

        // 超大 MAXVALUE 应省略以兼容 MariaDB/PostgreSQL 序列上限
        String seqDdl = ddlEmitter.emitSequenceCreateSql(DbTargetDialect.MARIADB, "mdd", schema.sequences().get(0), MigrationOptions.defaults());
        assertThat(seqDdl).doesNotContain("MAXVALUE");

        assertThat(CanonicalTypeNormalizer.normalizeDefault("b'1'").kind()).isEqualTo(DefaultExprKind.LITERAL_BOOLEAN);
        assertThat(CanonicalTypeNormalizer.normalizeDefault("b'1'").raw()).isEqualTo("TRUE");
    }

    @Test
    void crossFamilyViewRewritesReferencedTableNamesByCasePolicy() {
        var view = new CanonicalProgramObject("VIEW", "V_ORA_ACTIVE",
                "CREATE OR REPLACE VIEW \"MDD_ORA\".\"V_ORA_ACTIVE\" (\"ID\", \"BALANCE\") AS SELECT ID, BALANCE FROM ORA_ACCOUNTS WHERE BALANCE > 0",
                List.of("ORA_ACCOUNTS"), "VALID");
        var res = programTranslator.translateDeterministic(
                DbTargetDialect.ORACLE, DbTargetDialect.MYSQL, "MDD_ORA", "tgt_db", view,
                new MigrationOptions("LOWER", "FAIL", true, true, true, true, true, true, true, true,
                        true, "UTC", "FAIL", "FAIL", 38, 10, false, true, "L3_FULL_HASH",
                        2, List.of(), List.of(), Map.of(), Map.of(), Map.of(), null, null),
                Map.of("ora_accounts", "ora_accounts")
        );
        assertThat(res.targetName()).isEqualTo("v_ora_active");
        assertThat(res.targetSql()).contains("v_ora_active");
        assertThat(res.targetSql()).contains("ora_accounts");
        assertThat(res.targetSql()).doesNotContain("ORA_ACCOUNTS");
    }

    @Test
    void createTargetSchemaDdlCoversAllDialectsAndHonorsToggle() {
        assertThat(ddlEmitter.emitCreateSchemaStatements(DbTargetDialect.MYSQL, "biz_new", MigrationOptions.defaults()))
                .containsExactly("CREATE DATABASE IF NOT EXISTS `biz_new` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin");
        assertThat(ddlEmitter.emitCreateSchemaStatements(DbTargetDialect.POSTGRESQL, "biz_new", MigrationOptions.defaults()))
                .containsExactly("CREATE SCHEMA IF NOT EXISTS \"biz_new\"");
        assertThat(ddlEmitter.emitCreateSchemaStatements(DbTargetDialect.CLICKHOUSE, "biz_new", MigrationOptions.defaults()))
                .containsExactly("CREATE DATABASE IF NOT EXISTS `biz_new`");
        assertThat(ddlEmitter.emitCreateSchemaStatements(DbTargetDialect.SQLSERVER, "biz_new", MigrationOptions.defaults()))
                .singleElement().asString().contains("sys.schemas").contains("CREATE SCHEMA [biz_new]");
        assertThat(ddlEmitter.emitCreateSchemaStatements(DbTargetDialect.ORACLE, "BIZ_NEW", MigrationOptions.defaults()))
                .containsExactly("CREATE USER \"BIZ_NEW\" NO AUTHENTICATION", "GRANT UNLIMITED TABLESPACE TO \"BIZ_NEW\"");
        assertThat(ddlEmitter.emitCreateSchemaStatements(DbTargetDialect.DAMENG, "BIZ_NEW", MigrationOptions.defaults()))
                .containsExactly("CREATE SCHEMA \"BIZ_NEW\"");
        assertThat(ddlEmitter.emitCreateSchemaStatements(DbTargetDialect.SQLITE, "main", MigrationOptions.defaults()))
                .isEmpty();
    }

    private static CanonicalTable sampleOracleTable() {
        return new CanonicalTable(
                "t0001_docs", "DOCS", "文档主表",
                List.of(
                        col("ID", CanonicalTypeKind.INT64, "NUMBER(18,0)", null, 18, 0, false, false),
                        col("BIG_SEQ", CanonicalTypeKind.INT64, "NUMBER(10,0)", null, 10, 0, false, false),
                        col("TITLE", CanonicalTypeKind.VARCHAR, "VARCHAR2(200 CHAR)", 200, null, null, false, true),
                        col("AMOUNT_UNBOUNDED", CanonicalTypeKind.DECIMAL, "NUMBER", null, null, null, true, true),
                        col("ODD_SCALE", CanonicalTypeKind.DECIMAL, "NUMBER(2,5)", null, 2, 5, false, true),
                        col("CREATED_AT", CanonicalTypeKind.DATETIME, "DATE", null, null, null, false, false, 0),
                        col("TS_NANO", CanonicalTypeKind.DATETIME, "TIMESTAMP(9)", null, null, null, false, true, 9),
                        col("TS_TZ", CanonicalTypeKind.TIMESTAMP_TZ, "TIMESTAMP(6) WITH TIME ZONE", null, null, null, false, true, 6),
                        col("DOC_CLOB", CanonicalTypeKind.CLOB, "CLOB", null, null, null, false, true),
                        col("DOC_BLOB", CanonicalTypeKind.BLOB, "BLOB", null, null, null, false, true)
                ),
                "PK_DOCS", List.of("ID"),
                List.of(new CanonicalIndex("IDX_DOCS_TITLE", List.of(new IndexColumn("TITLE", false, null, null)), false, IndexKind.BTREE, null)),
                List.of(), List.of(), null, 5000L, 1024L * 1024L * 20
        );
    }

    private static CanonicalColumn col(String name, CanonicalTypeKind kind, String nativeType,
                                       Integer len, Integer prec, Integer scale, boolean unbounded, boolean nullable) {
        return col(name, kind, nativeType, len, prec, scale, unbounded, nullable, null);
    }

    private static CanonicalColumn col(String name, CanonicalTypeKind kind, String nativeType,
                                       Integer len, Integer prec, Integer scale, boolean unbounded, boolean nullable, Integer fsp) {
        return new CanonicalColumn(
                name, kind, nativeType, len, len == null ? LengthUnit.NONE : LengthUnit.CHAR,
                prec, scale, unbounded, false, false, fsp, nullable,
                DefaultExprKind.NONE, null, null, null, "注释-" + name
        );
    }
}
