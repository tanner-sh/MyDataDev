package com.example.dbadmin.repo;

import com.example.dbadmin.dto.ApiDtos.AuditEventPage;
import com.example.dbadmin.repo.AuditRepository.AuditQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AuditRepositoryQueryTest {
    private JdbcTemplate jdbc;
    private AuditRepository repository;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:audit-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""
        );
        new ResourceDatabasePopulator(
                new ClassPathResource("schema.sql"),
                new ClassPathResource("audit-connection-schema.sql")
        ).execute(dataSource);
        jdbc = new JdbcTemplate(dataSource);
        repository = new AuditRepository(jdbc, MetadataWriteQueue.inline());
    }

    private AuditQuery query(String actor, String action, Long connectionId, String keyword) {
        return new AuditQuery(actor, action, connectionId, keyword, null, null, 0, 50);
    }

    @Test
    void returnsTheMostRecentEventsFirst() {
        repository.onConnection("admin", "SQL_EXECUTE", 1L, "select 1");
        repository.onConnection("admin", "DATA_COMMIT", 1L, "table:users", "update users");

        AuditEventPage page = repository.findPage(query(null, null, null, null));

        assertThat(page.items()).extracting("action").containsExactly("DATA_COMMIT", "SQL_EXECUTE");
        assertThat(page.hasMore()).isFalse();
    }

    /**
     * event_hash 为空的行（直接改库、导入旧备份、回填中断）不能把审计写入卡死：以前取上一条
     * 哈希时会在这里抛 NPE，而 insert 吞掉 RuntimeException，结果是之后每一条审计都静默丢失。
     */
    @Test
    void keepsWritingAfterARowWithoutHash() {
        repository.onConnection("admin", "SQL_EXECUTE", 1L, "select 1");
        jdbc.update("UPDATE audit_log SET event_hash = NULL");

        repository.onConnection("alice", "DATA_COMMIT", 1L, "table:orders", "rows=1");

        assertThat(repository.findPage(query(null, null, null, null)).items())
                .extracting("action").containsExactly("DATA_COMMIT", "SQL_EXECUTE");
        // 链在空值那一行本来就断了，校验必须把它报出来，而不是当作没事发生。
        assertThat(repository.verifyChain().valid()).isFalse();
    }

    /**
     * 校验分批读表，而且不占用写入那把锁。以前是一次性物化整张表并 synchronized 在同一个
     * monitor 上：打开一次审计面板就把所有审计写入卡住整整一趟全表扫描。
     */
    @Test
    void verificationDoesNotBlockConcurrentWrites() throws Exception {
        for (int i = 0; i < 1_200; i++) repository.onConnection("admin", "SQL_EXECUTE", 1L, "select " + i);

        // insert 用的是实例 monitor。这里替写入方把它按住，校验必须照样能跑完 ——
        // 跑不完就说明它又和写入挤在同一把锁上了。
        java.util.concurrent.CompletableFuture<AuditRepository.ChainVerification> verified;
        synchronized (repository) {
            verified = java.util.concurrent.CompletableFuture.supplyAsync(repository::verifyChain);
            assertThat(verified.get(10, java.util.concurrent.TimeUnit.SECONDS).valid()).isTrue();
        }

        assertThat(verified.get().checkedEvents()).isEqualTo(1_200);
        assertThat(verified.get().complete()).isTrue();
    }

    @Test
    void verificationCanBeResumedFromAnEarlierPoint() {
        for (int i = 0; i < 5; i++) repository.onConnection("admin", "SQL_EXECUTE", 1L, "select " + i);
        long thirdId = jdbc.queryForObject("SELECT id FROM audit_log ORDER BY id LIMIT 1 OFFSET 2", Long.class);

        // 从中间接着验：上一段验过的最后一条就是这里的「上一个哈希」，结果仍然成立。
        var resumed = repository.verifyChain(thirdId);

        assertThat(resumed.valid()).isTrue();
        assertThat(resumed.complete()).isTrue();
        assertThat(resumed.checkedEvents()).isEqualTo(3);
        assertThat(resumed.nextId()).isNull();
    }

    @Test
    void completeFlagIsSetOnAFullPass() {
        repository.onConnection("admin", "SQL_EXECUTE", 1L, "select 1");

        var verification = repository.verifyChain();

        assertThat(verification.complete()).isTrue();
        assertThat(verification.nextId()).isNull();
        assertThat(verification.headHash()).isEqualTo(
                jdbc.queryForObject("SELECT event_hash FROM audit_log ORDER BY id DESC LIMIT 1", String.class));
    }

    @Test
    void verifiesTheHashChainAndDetectsTampering() {
        repository.onConnection("admin", "SQL_EXECUTE", 1L, "select 1");
        repository.onConnection("alice", "DATA_COMMIT", 1L, "table:orders", "rows=1");

        assertThat(repository.verifyChain().valid()).isTrue();
        jdbc.update("UPDATE audit_log SET detail = 'tampered' WHERE action = 'SQL_EXECUTE'");

        assertThat(repository.verifyChain().valid()).isFalse();
        assertThat(repository.verifyChain().firstInvalidId()).isNotNull();
    }

    @Test
    void filtersByActorAndAction() {
        repository.onConnection("admin", "SQL_EXECUTE", 1L, "select 1");
        repository.onConnection("alice", "SQL_EXECUTE", 1L, "select 2");
        repository.onConnection("admin", "CONNECTION_DELETE", 9L, "prod", "gone");

        assertThat(repository.findPage(query("alice", null, null, null)).items()).hasSize(1);
        assertThat(repository.findPage(query(null, "SQL_EXECUTE", null, null)).items()).hasSize(2);
        assertThat(repository.findPage(query("admin", "SQL_EXECUTE", null, null)).items()).hasSize(1);
    }

    @Test
    void filtersByConnectionRegardlessOfHowTheTargetReads() {
        repository.onConnection("admin", "SQL_EXECUTE", 7L, "select 1");
        repository.onConnection("admin", "DATA_COMMIT", 7L, "table:users", "update users");
        // 连接自身的增删改、备份、恢复这些事件的 target 里根本没有 "connection:<id>"，
        // 以前按连接筛选会把它们整批漏掉 —— 这正是把连接归属改成独立字段的原因。
        repository.onConnection("admin", "CONNECTION_UPDATE", 7L, "生产只读库", "jdbc:h2:mem:x");
        repository.onConnection("admin", "BACKUP_TASK_RUN", 7L, "backup:每日全量", "备份完成。");
        repository.onConnection("admin", "SQL_EXECUTE", 70L, "另一条连接，不能被前缀匹配带出来");
        // 与连接无关的事件不该出现在任何连接的筛选结果里。
        repository.global("admin", "MCP_ENABLE", "mcp", "enabled=true");

        assertThat(repository.findPage(query(null, null, 7L, null)).items()).hasSize(4);
        assertThat(repository.findPage(query(null, null, 70L, null)).items()).hasSize(1);
        assertThat(repository.findPage(query(null, null, null, null)).items()).hasSize(6);
    }

    @Test
    void backfillsTheConnectionOfHistoricalRowsThatEncodedItInTheTarget() {
        jdbc.update("INSERT INTO audit_log(actor, action, target, detail) VALUES (?, ?, ?, ?)",
                "admin", "SQL_EXECUTE", "connection:7", "select 1");
        jdbc.update("INSERT INTO audit_log(actor, action, target, detail) VALUES (?, ?, ?, ?)",
                "admin", "DATA_COMMIT", "connection:7 table:users", "update users");
        jdbc.update("INSERT INTO audit_log(actor, action, target, detail) VALUES (?, ?, ?, ?)",
                "admin", "BACKUP_TASK_RUN", "每日全量", "认不出连接，保持为空");

        assertThat(db.migration.V8__AuditConnectionId.backfill(jdbc)).isEqualTo(2);

        assertThat(repository.findPage(query(null, null, 7L, null)).items()).hasSize(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE connection_id IS NULL", Integer.class))
                .isEqualTo(1);
        // 迁移可重入：再跑一次不该有任何变化。
        assertThat(db.migration.V8__AuditConnectionId.backfill(jdbc)).isZero();
    }

    @Test
    void ignoresTargetsThatOnlyLookLikeAConnectionReference() {
        assertThat(AuditRepository.connectionIdFromTarget("connection:7")).isEqualTo(7L);
        assertThat(AuditRepository.connectionIdFromTarget("connection:7 table:users")).isEqualTo(7L);
        assertThat(AuditRepository.connectionIdFromTarget("connection:abc")).isNull();
        assertThat(AuditRepository.connectionIdFromTarget("connection:")).isNull();
        assertThat(AuditRepository.connectionIdFromTarget("连接:7")).isNull();
        assertThat(AuditRepository.connectionIdFromTarget(null)).isNull();
        assertThat(AuditRepository.connectionIdFromTarget("connection:99999999999999999999")).isNull();
    }

    @Test
    void searchesTargetAndDetailCaseInsensitively() {
        repository.onConnection("admin", "SQL_EXECUTE", 1L, "SELECT * FROM Orders");
        repository.onConnection("admin", "SQL_EXECUTE", 1L, "select * from customers");

        assertThat(repository.findPage(query(null, null, null, "orders")).items()).hasSize(1);
        assertThat(repository.findPage(query(null, null, null, "connection:1")).items()).hasSize(2);
    }

    @Test
    void treatsLikeMetacharactersInTheKeywordAsLiterals() {
        repository.onConnection("admin", "SQL_EXECUTE", 1L, "折扣 100% 的订单");
        repository.onConnection("admin", "SQL_EXECUTE", 1L, "普通订单");

        assertThat(repository.findPage(query(null, null, null, "100%")).items()).hasSize(1);
    }

    @Test
    void filtersByTimeRange() {
        repository.onConnection("admin", "SQL_EXECUTE", 1L, "old");
        jdbc.update("UPDATE audit_log SET created_at = ? WHERE detail = ?",
                java.sql.Timestamp.from(Instant.parse("2020-01-01T00:00:00Z")), "old");
        repository.onConnection("admin", "SQL_EXECUTE", 1L, "new");

        AuditEventPage recent = repository.findPage(new AuditQuery(
                null, null, null, null, Instant.parse("2024-01-01T00:00:00Z"), null, 0, 50
        ));
        assertThat(recent.items()).singleElement().extracting("detail").isEqualTo("new");

        AuditEventPage old = repository.findPage(new AuditQuery(
                null, null, null, null, null, Instant.parse("2021-01-01T00:00:00Z"), 0, 50
        ));
        assertThat(old.items()).singleElement().extracting("detail").isEqualTo("old");
    }

    @Test
    void paginatesWithoutASeparateCountQuery() {
        for (int index = 0; index < 5; index++) {
            repository.onConnection("admin", "SQL_EXECUTE", 1L, "select " + index);
        }

        AuditEventPage first = repository.findPage(new AuditQuery(null, null, null, null, null, null, 0, 2));
        assertThat(first.items()).hasSize(2);
        assertThat(first.hasMore()).isTrue();

        AuditEventPage last = repository.findPage(new AuditQuery(null, null, null, null, null, null, 2, 2));
        assertThat(last.items()).hasSize(1);
        assertThat(last.hasMore()).isFalse();
    }

    @Test
    void truncatesTheDetailInListingsButKeepsTheFullValueRetrievable() {
        String huge = "x".repeat(AuditRepository.MAX_LISTED_DETAIL_CHARS + 500);
        repository.onConnection("admin", "SQL_EXECUTE", 1L, huge);

        var listed = repository.findPage(query(null, null, null, null)).items().get(0);
        assertThat(listed.detail()).hasSize(AuditRepository.MAX_LISTED_DETAIL_CHARS);
        assertThat(listed.detailTruncated()).isTrue();
        assertThat(repository.detail(listed.id())).contains(huge);
    }

    @Test
    void exposesTheActorsAndActionsThatWereActuallyRecorded() {
        repository.onConnection("admin", "SQL_EXECUTE", 1L, "a");
        repository.onConnection("alice", "DATA_COMMIT", 1L, "b");
        repository.onConnection("admin", "SQL_EXECUTE", 1L, "c");

        var facets = repository.facets();
        assertThat(facets.actors()).containsExactly("admin", "alice");
        assertThat(facets.actions()).containsExactly("DATA_COMMIT", "SQL_EXECUTE");
    }
}
