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
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(dataSource);
        jdbc = new JdbcTemplate(dataSource);
        repository = new AuditRepository(jdbc, MetadataWriteQueue.inline());
    }

    private AuditQuery query(String actor, String action, Long connectionId, String keyword) {
        return new AuditQuery(actor, action, connectionId, keyword, null, null, 0, 50);
    }

    @Test
    void returnsTheMostRecentEventsFirst() {
        repository.log("admin", "SQL_EXECUTE", "connection:1", "select 1");
        repository.log("admin", "DATA_COMMIT", "connection:1 table:users", "update users");

        AuditEventPage page = repository.findPage(query(null, null, null, null));

        assertThat(page.items()).extracting("action").containsExactly("DATA_COMMIT", "SQL_EXECUTE");
        assertThat(page.hasMore()).isFalse();
    }

    @Test
    void filtersByActorAndAction() {
        repository.log("admin", "SQL_EXECUTE", "connection:1", "select 1");
        repository.log("alice", "SQL_EXECUTE", "connection:1", "select 2");
        repository.log("admin", "CONNECTION_DELETE", "prod", "gone");

        assertThat(repository.findPage(query("alice", null, null, null)).items()).hasSize(1);
        assertThat(repository.findPage(query(null, "SQL_EXECUTE", null, null)).items()).hasSize(2);
        assertThat(repository.findPage(query("admin", "SQL_EXECUTE", null, null)).items()).hasSize(1);
    }

    @Test
    void filtersByConnectionAcrossBothTargetShapes() {
        // target 有两种写法：光是 connection:<id>，或后面还跟着 table:xxx
        repository.log("admin", "SQL_EXECUTE", "connection:7", "select 1");
        repository.log("admin", "DATA_COMMIT", "connection:7 table:users", "update users");
        repository.log("admin", "SQL_EXECUTE", "connection:70", "另一条连接，不能被前缀匹配带出来");

        assertThat(repository.findPage(query(null, null, 7L, null)).items()).hasSize(2);
        assertThat(repository.findPage(query(null, null, 70L, null)).items()).hasSize(1);
    }

    @Test
    void searchesTargetAndDetailCaseInsensitively() {
        repository.log("admin", "SQL_EXECUTE", "connection:1", "SELECT * FROM Orders");
        repository.log("admin", "SQL_EXECUTE", "connection:1", "select * from customers");

        assertThat(repository.findPage(query(null, null, null, "orders")).items()).hasSize(1);
        assertThat(repository.findPage(query(null, null, null, "connection:1")).items()).hasSize(2);
    }

    @Test
    void treatsLikeMetacharactersInTheKeywordAsLiterals() {
        repository.log("admin", "SQL_EXECUTE", "connection:1", "折扣 100% 的订单");
        repository.log("admin", "SQL_EXECUTE", "connection:1", "普通订单");

        assertThat(repository.findPage(query(null, null, null, "100%")).items()).hasSize(1);
    }

    @Test
    void filtersByTimeRange() {
        repository.log("admin", "SQL_EXECUTE", "connection:1", "old");
        jdbc.update("UPDATE audit_log SET created_at = ? WHERE detail = ?",
                java.sql.Timestamp.from(Instant.parse("2020-01-01T00:00:00Z")), "old");
        repository.log("admin", "SQL_EXECUTE", "connection:1", "new");

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
            repository.log("admin", "SQL_EXECUTE", "connection:1", "select " + index);
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
        repository.log("admin", "SQL_EXECUTE", "connection:1", huge);

        var listed = repository.findPage(query(null, null, null, null)).items().get(0);
        assertThat(listed.detail()).hasSize(AuditRepository.MAX_LISTED_DETAIL_CHARS);
        assertThat(listed.detailTruncated()).isTrue();
        assertThat(repository.detail(listed.id())).contains(huge);
    }

    @Test
    void exposesTheActorsAndActionsThatWereActuallyRecorded() {
        repository.log("admin", "SQL_EXECUTE", "connection:1", "a");
        repository.log("alice", "DATA_COMMIT", "connection:1", "b");
        repository.log("admin", "SQL_EXECUTE", "connection:1", "c");

        var facets = repository.facets();
        assertThat(facets.actors()).containsExactly("admin", "alice");
        assertThat(facets.actions()).containsExactly("DATA_COMMIT", "SQL_EXECUTE");
    }
}
