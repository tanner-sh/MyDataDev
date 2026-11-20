package com.example.dbadmin.service.migration;

import com.example.dbadmin.dto.ApiDtos.ConnectionRequest;
import com.example.dbadmin.dto.MigrationDtos.MigrationAssessRequest;
import com.example.dbadmin.dto.MigrationDtos.MigrationAssessmentResponse;
import com.example.dbadmin.dto.MigrationDtos.MigrationJobDetail;
import com.example.dbadmin.dto.MigrationDtos.MigrationStartRequest;
import com.example.dbadmin.dto.MigrationDtos.MigrationVerificationReport;
import com.example.dbadmin.dto.MigrationDtos.MigrationVerifyRequest;
import com.example.dbadmin.model.MigrationJob;
import com.example.dbadmin.service.ConnectionService;
import com.example.dbadmin.service.migration.model.MigrationOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 端到端全链路集成测试（真实 Spring Boot 上下文 + Flyway V31 + 真实 H2 ↔ SQLite 跨库迁移 + .mddpkg 标准制品 + L3 全字节校验）。
 */
@SpringBootTest
class MigrationEndToEndIntegrationTest {
    @TempDir
    static Path workDir;

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:h2:file:" + workDir.resolve("meta-db").toAbsolutePath() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE");
        registry.add("app.crypto-key-file", () -> workDir.resolve("master.key").toAbsolutePath().toString());
        registry.add("app.migration.directory", () -> workDir.resolve("migrations").toAbsolutePath().toString());
        registry.add("app.migration.part-max-rows", () -> "1000");
        registry.add("app.backup.directory", () -> workDir.resolve("backups").toAbsolutePath().toString());
        registry.add("app.sql-file.directory", () -> workDir.resolve("sql-files").toAbsolutePath().toString());
    }

    @Autowired
    private ConnectionService connections;
    @Autowired
    private MigrationService migrationService;

    @Test
    void fullCycleOnlineDirectAndStandardArtifactImportPassL3VerificationWithLargeLobs() throws Exception {
        String h2SourceUrl = "jdbc:h2:file:" + workDir.resolve("src-h2").toAbsolutePath() + ";DATABASE_TO_UPPER=TRUE";
        String sqliteDirectUrl = "jdbc:sqlite:" + workDir.resolve("tgt-direct.sqlite").toAbsolutePath();
        String sqliteArtifactUrl = "jdbc:sqlite:" + workDir.resolve("tgt-artifact.sqlite").toAbsolutePath();
        String h2BackUrl = "jdbc:h2:file:" + workDir.resolve("tgt-h2-back").toAbsolutePath() + ";DATABASE_TO_UPPER=TRUE";

        // 1. 播种源库 H2：父子表（含外键、二级索引、高精度小数、日期时间、布尔、2.5MB BLOB、2.5MB 中文 CLOB）
        byte[] bigBlob = new byte[2_500_000];
        new Random(2026L).nextBytes(bigBlob);
        String bigClob = "信创国产化数据库转换测试内容-含中文与换行\n".repeat(60_000);

        try (Connection conn = DriverManager.getConnection(h2SourceUrl, "sa", "");
             Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE DEPARTMENTS (ID BIGINT PRIMARY KEY, NAME VARCHAR(120) NOT NULL, ACTIVE BOOLEAN NOT NULL)");
            st.execute("""
                    CREATE TABLE DOCUMENTS (
                        ID BIGINT PRIMARY KEY,
                        DEPT_ID BIGINT NOT NULL,
                        TITLE VARCHAR(200) NOT NULL,
                        AMOUNT NUMERIC(24,6),
                        CREATED_AT TIMESTAMP(3) NOT NULL,
                        BODY_CLOB CLOB,
                        RAW_BLOB BLOB,
                        CONSTRAINT FK_DOC_DEPT FOREIGN KEY (DEPT_ID) REFERENCES DEPARTMENTS(ID)
                    )""");
            st.execute("CREATE INDEX IDX_DOC_TITLE ON DOCUMENTS(TITLE)");
            st.execute("CREATE TABLE EMPTY_AUDIT_LOG (ID BIGINT PRIMARY KEY, DETAIL VARCHAR(200))");
            st.execute("CREATE TABLE BATCH_EVENTS (ID BIGINT PRIMARY KEY, TAG VARCHAR(64) NOT NULL)");
            st.execute("CREATE INDEX IDX_BATCH_TAG ON BATCH_EVENTS(TAG)");

            try (PreparedStatement ps = conn.prepareStatement("INSERT INTO DEPARTMENTS VALUES (?, ?, ?)")) {
                ps.setLong(1, 10L); ps.setString(2, "核心交易部"); ps.setBoolean(3, true); ps.executeUpdate();
                ps.setLong(1, 20L); ps.setString(2, "风控合规部"); ps.setBoolean(3, false); ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement("INSERT INTO BATCH_EVENTS VALUES (?, ?)")) {
                for (long i = 1; i <= 2_500; i++) {
                    ps.setLong(1, i);
                    ps.setString(2, "evt-" + (i % 17));
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            try (PreparedStatement ps = conn.prepareStatement("INSERT INTO DOCUMENTS VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                ps.setLong(1, 1001L);
                ps.setLong(2, 10L);
                ps.setString(3, "年度核心账务归档");
                ps.setBigDecimal(4, new BigDecimal("1234567890123456.654321"));
                ps.setTimestamp(5, Timestamp.valueOf(LocalDateTime.of(2026, 11, 19, 10, 20, 30, 123_000_000)));
                ps.setCharacterStream(6, new StringReader(bigClob), bigClob.length());
                ps.setBinaryStream(7, new ByteArrayInputStream(bigBlob), bigBlob.length);
                ps.executeUpdate();

                ps.setLong(1, 1002L);
                ps.setLong(2, 20L);
                ps.setString(3, "空值与转义测试\\'\"");
                ps.setBigDecimal(4, null);
                ps.setTimestamp(5, Timestamp.valueOf(LocalDateTime.of(2025, 5, 1, 8, 0, 0)));
                ps.setNull(6, java.sql.Types.CLOB);
                ps.setNull(7, java.sql.Types.BLOB);
                ps.executeUpdate();
            }
        }

        // 注册源/目标连接
        long h2SrcId = connections.create(new ConnectionRequest("H2源库", "h2", h2SourceUrl, "sa", "", "TEST", false), "tester").id();
        long sqliteDirectId = connections.create(new ConnectionRequest("SQLite直迁库", "sqlite", sqliteDirectUrl, "", "", "TEST", false), "tester").id();
        long sqliteArtId = connections.create(new ConnectionRequest("SQLite制品导入库", "sqlite", sqliteArtifactUrl, "", "", "TEST", false), "tester").id();
        long h2BackId = connections.create(new ConnectionRequest("H2回迁库", "h2", h2BackUrl, "sa", "", "TEST", false), "tester").id();

        // 2. 路径 A：在线直迁（H2 -> SQLite）+ 自动 L3 哈希校验
        MigrationAssessmentResponse directAssess = migrationService.assess(new MigrationAssessRequest(
                "ONLINE_DIRECT", h2SrcId, "PUBLIC", null, sqliteDirectId, "", null, MigrationOptions.defaults()
        ), "tester");
        assertThat(directAssess.canExecute()).isTrue();
        assertThat(directAssess.summary().tableCount()).isEqualTo(4);
        assertThat(directAssess.summary().hasLobTables()).isTrue();

        MigrationJob directJob = migrationService.start(new MigrationStartRequest(
                directAssess.planToken(), null, null, null
        ), "tester", null);
        MigrationJobDetail directDetail = awaitCompletion(directJob.id());
        assertThat(directDetail.job().status()).isEqualTo("SUCCESS");
        assertThat(directDetail.job().verifyStatus()).isEqualTo("PASSED");
        assertThat(directDetail.job().migratedRows()).isEqualTo(2_504L);

        // 3. 路径 B：并行导出通用标准制品（.mddpkg，4 线程 + 0 行表零分片 + BATCH_EVENTS 自动切 3 分片） -> 导入至全新 SQLite -> 离线校验
        MigrationAssessmentResponse exportAssess = migrationService.assess(new MigrationAssessRequest(
                "EXPORT_ARTIFACT", h2SrcId, "PUBLIC", null, null, null, null, MigrationOptions.defaults()
        ), "tester");
        MigrationJob exportJob = migrationService.start(new MigrationStartRequest(
                exportAssess.planToken(), null, null, null
        ), "tester", null);
        MigrationJobDetail exportDetail = awaitCompletion(exportJob.id());
        assertThat(exportDetail.job().status()).isEqualTo("SUCCESS");
        assertThat(exportDetail.outputArtifact()).isNotNull();
        long artifactId = exportDetail.outputArtifact().id();
        assertThat(exportDetail.outputArtifact().totalRows()).isEqualTo(2_504L);
        assertThat(exportDetail.outputArtifact().manifestJson())
                .contains("\"partCount\":3")
                .contains("\"partCount\":0");

        MigrationAssessmentResponse importAssess = migrationService.assess(new MigrationAssessRequest(
                "IMPORT_ARTIFACT", null, null, artifactId, sqliteArtId, "", null, MigrationOptions.defaults()
        ), "tester");
        assertThat(importAssess.canExecute()).isTrue();
        MigrationJob importJob = migrationService.start(new MigrationStartRequest(
                importAssess.planToken(), null, null, null
        ), "tester", null);
        MigrationJobDetail importDetail = awaitCompletion(importJob.id());
        assertThat(importDetail.job().status()).isEqualTo("SUCCESS");
        assertThat(importDetail.job().verifyStatus()).isEqualTo("PASSED");

        // 导出的 Markdown 验收报告可读且包含核心结论（BLOB + CLOB 总字节精确一致）
        long expectedLobBytes = bigBlob.length + bigClob.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        String markdown = migrationService.renderVerificationMarkdown(importJob.id());
        assertThat(markdown).contains("通过 (PASSED)").contains("DOCUMENTS").contains(String.valueOf(expectedLobBytes));

        // 4. 路径 C：单表多分片并发导入 H2 新 Schema（4 线程同时并发写入 BATCH_EVENTS 的 3 个分片 + 并行建索引 + 并行 L3 校验）
        MigrationAssessmentResponse parallelH2ImportAssess = migrationService.assess(new MigrationAssessRequest(
                "IMPORT_ARTIFACT", null, null, artifactId, h2BackId, "PARALLEL_IMPORT_APP", null, MigrationOptions.defaults()
        ), "tester");
        assertThat(parallelH2ImportAssess.targetSchemaExists()).isFalse();
        assertThat(parallelH2ImportAssess.canExecute()).isTrue();
        MigrationJob parallelH2Job = migrationService.start(new MigrationStartRequest(
                parallelH2ImportAssess.planToken(), null, null, null
        ), "tester", null);
        MigrationJobDetail parallelH2Detail = awaitCompletion(parallelH2Job.id());
        assertThat(parallelH2Detail.job().status())
                .as("items=" + parallelH2Detail.items())
                .isEqualTo("SUCCESS");
        assertThat(parallelH2Detail.job().verifyStatus())
                .as("verifyReport=" + parallelH2Detail.job().verifyReportJson())
                .isEqualTo("PASSED");
        assertThat(parallelH2Detail.job().migratedRows()).isEqualTo(2_504L);

        // 5. 路径 D：反向回迁到尚未存在的目标 Schema（SQLite -> H2 新 Schema "AUTO_CREATED_APP"）+ 验证关闭自动创建时阻断，开启时自动创建并通过 L3 校验
        MigrationAssessmentResponse blockedAssess = migrationService.assess(new MigrationAssessRequest(
                "ONLINE_DIRECT", sqliteArtId, "", null, h2BackId, "AUTO_CREATED_APP", null,
                new MigrationOptions("UPPER", "FAIL", true, true, true, true, true, true, false, false,
                        true, "UTC", "FAIL", "FAIL", 38, 10, false, false, "L3_FULL_HASH",
                        4, List.of(), List.of(), Map.of(), Map.of(), Map.of(), null, null)
        ), "tester");
        assertThat(blockedAssess.targetSchemaExists()).isFalse();
        assertThat(blockedAssess.canExecute()).isFalse();
        assertThat(blockedAssess.globalFindings()).anyMatch(f -> "TARGET_SCHEMA_MISSING".equals(f.code()));

        MigrationAssessmentResponse backAssess = migrationService.assess(new MigrationAssessRequest(
                "ONLINE_DIRECT", sqliteArtId, "", null, h2BackId, "AUTO_CREATED_APP", null,
                new MigrationOptions("UPPER", "FAIL", true, true, true, true, true, true, false, true,
                        true, "UTC", "FAIL", "FAIL", 38, 10, false, false, "L3_FULL_HASH",
                        4, List.of(), List.of(), Map.of(), Map.of(), Map.of(), null, null)
        ), "tester");
        assertThat(backAssess.targetSchemaExists()).isFalse();
        assertThat(backAssess.canExecute()).isTrue();
        assertThat(backAssess.createSchemaSql()).containsExactly("CREATE SCHEMA IF NOT EXISTS \"AUTO_CREATED_APP\"");
        MigrationJob backJob = migrationService.start(new MigrationStartRequest(backAssess.planToken(), null, null, null), "tester", null);
        MigrationJobDetail backDetail = awaitCompletion(backJob.id());
        assertThat(backDetail.job().status())
                .as("items=" + backDetail.items())
                .isEqualTo("SUCCESS");

        MigrationVerificationReport manualVerify = migrationService.verify(backJob.id(), new MigrationVerifyRequest("L3_FULL_HASH", List.of()), "tester");
        assertThat(manualVerify.overallStatus()).isEqualTo("PASSED");
        assertThat(manualVerify.passedTables()).isEqualTo(4);
    }

    private MigrationJobDetail awaitCompletion(long jobId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 60_000L;
        while (System.currentTimeMillis() < deadline) {
            MigrationJobDetail detail = migrationService.getJobDetail(jobId);
            String st = detail.job().status();
            if (!"QUEUED".equals(st) && !"RUNNING".equals(st)) {
                return detail;
            }
            Thread.sleep(100L);
        }
        throw new AssertionError("迁移任务超时未完成：" + jobId);
    }
}
