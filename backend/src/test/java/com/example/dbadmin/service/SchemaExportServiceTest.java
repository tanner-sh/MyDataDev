package com.example.dbadmin.service;

import com.example.dbadmin.config.AppProperties;
import com.example.dbadmin.core.DialectRegistry;
import com.example.dbadmin.dto.ApiDtos.SchemaExportObject;
import com.example.dbadmin.dto.ApiDtos.SchemaExportRequest;
import com.example.dbadmin.model.DbConnection;
import com.example.dbadmin.repo.AuditRepository;
import com.example.dbadmin.repo.SqlHistoryRepository;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SchemaExportServiceTest {
    @Test
    void exportsDependenciesFirstAndTheScriptReplaysIntoAnEmptyDatabase() throws Exception {
        Fixture fixture = fixture();
        try (Connection connection = DriverManager.getConnection(fixture.url(), "sa", ""); Statement statement = connection.createStatement()) {
            // 名字故意排得和依赖顺序相反：按字母导出的话每一步都会报「对象不存在」。
            statement.execute("CREATE TABLE z_customer(id BIGINT PRIMARY KEY, name VARCHAR(40))");
            statement.execute("CREATE TABLE m_order(id BIGINT PRIMARY KEY, customer_id BIGINT REFERENCES z_customer(id), amount INT)");
            statement.execute("CREATE TABLE a_order_item(id BIGINT PRIMARY KEY, order_id BIGINT REFERENCES m_order(id))");
            statement.execute("CREATE VIEW b_big_order AS SELECT id, customer_id FROM m_order WHERE amount > 100");
            statement.execute("CREATE VIEW a_big_customer AS SELECT c.name FROM z_customer c JOIN b_big_order o ON o.customer_id = c.id");
            statement.execute("CREATE SEQUENCE order_seq START WITH 5");
        }

        var candidates = fixture.service().candidates(1L, "PUBLIC");
        assertThat(candidates.objects()).extracting("name")
                .contains("Z_CUSTOMER", "M_ORDER", "A_ORDER_ITEM", "A_BIG_CUSTOMER", "B_BIG_ORDER", "ORDER_SEQ");
        List<SchemaExportObject> all = candidates.objects().stream()
                .map(candidate -> new SchemaExportObject(candidate.kind(), candidate.name(), candidate.objectKey()))
                .toList();

        var response = fixture.service().export(1L, new SchemaExportRequest("PUBLIC", all, null), "tester");

        List<String> order = response.items().stream().map(item -> item.name()).toList();
        assertThat(order.indexOf("Z_CUSTOMER")).isLessThan(order.indexOf("M_ORDER"));
        assertThat(order.indexOf("M_ORDER")).isLessThan(order.indexOf("A_ORDER_ITEM"));
        assertThat(order.indexOf("M_ORDER")).isLessThan(order.indexOf("B_BIG_ORDER"));
        assertThat(order.indexOf("B_BIG_ORDER")).isLessThan(order.indexOf("A_BIG_CUSTOMER"));
        assertThat(order.get(0)).isEqualTo("ORDER_SEQ");
        assertThat(response.items().stream().filter(item -> item.name().equals("A_BIG_CUSTOMER")).findFirst().orElseThrow().dependsOn())
                .contains("Z_CUSTOMER", "B_BIG_ORDER");
        assertThat(response.failed()).isZero();
        assertThat(response.cycles()).isEmpty();
        assertThat(response.script()).startsWith("-- MyDataDev 对象定义导出");
        verify(fixture.audit()).onConnection(eq("tester"), eq("OBJECT_DDL_EXPORT"), eq(1L), eq("schema:PUBLIC"), contains("objects=6"));

        // 真正的判据：脚本原样拆句、按顺序在一个空库里执行，一条都不报错。
        String target = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(target, "sa", ""); Statement statement = connection.createStatement()) {
            for (var segment : new SqlScriptSplitter().split(response.script(), "h2")) {
                statement.execute(segment.sql());
            }
            List<String> created = new ArrayList<>();
            try (ResultSet rs = statement.executeQuery("SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = 'PUBLIC' ORDER BY TABLE_NAME")) {
                while (rs.next()) created.add(rs.getString(1));
            }
            assertThat(created).containsExactly("A_BIG_CUSTOMER", "A_ORDER_ITEM", "B_BIG_ORDER", "M_ORDER", "Z_CUSTOMER");
        }
    }

    @Test
    void reportsDependenciesLeftOutOfTheSelectionAndSkipsUnreadableObjects() throws Exception {
        Fixture fixture = fixture();
        try (Connection connection = DriverManager.getConnection(fixture.url(), "sa", ""); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE parent(id BIGINT PRIMARY KEY)");
            statement.execute("CREATE TABLE child(id BIGINT PRIMARY KEY, parent_id BIGINT REFERENCES parent(id))");
        }
        var response = fixture.service().export(1L, new SchemaExportRequest("PUBLIC", List.of(
                new SchemaExportObject("TABLE", "CHILD", null),
                new SchemaExportObject("TABLE", "MISSING_TABLE", null)), null), "tester");
        assertThat(response.items()).extracting("name").containsExactly("CHILD");
        assertThat(response.failed()).isEqualTo(1);
        assertThat(response.warnings()).anySatisfy(warning -> assertThat(warning).contains("MISSING_TABLE").contains("已跳过"));
        assertThat(response.warnings()).anySatisfy(warning -> assertThat(warning).contains("CHILD → PARENT"));
    }

    @Test
    void rejectsEmptyAndOversizedRequests() throws Exception {
        Fixture fixture = fixture();
        assertThatThrownBy(() -> fixture.service().export(1L, new SchemaExportRequest("PUBLIC", List.of(), null), "tester"))
                .hasMessageContaining("至少选择一个");
        List<SchemaExportObject> tooMany = new ArrayList<>();
        for (int i = 0; i <= SchemaExportService.MAX_OBJECTS; i++) tooMany.add(new SchemaExportObject("TABLE", "T" + i, null));
        assertThatThrownBy(() -> fixture.service().export(1L, new SchemaExportRequest("PUBLIC", tooMany, null), "tester"))
                .hasMessageContaining("最多导出");
    }

    private Fixture fixture() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        try (Connection ignored = DriverManager.getConnection(url, "sa", "")) {
            // Keep the named in-memory database alive for subsequent calls.
        }
        DbConnection configured = new DbConnection(1L, "remote", "h2", url, "sa", "", "dev", false, Instant.now(), Instant.now());
        ConnectionService connections = mock(ConnectionService.class);
        when(connections.require(1L)).thenReturn(configured);
        when(connections.open(1L)).thenAnswer(ignored -> DriverManager.getConnection(url, "sa", ""));
        AuditRepository audit = mock(AuditRepository.class);
        DialectRegistry dialects = new DialectRegistry();
        MetadataCacheService cache = new MetadataCacheService();
        SchemaObjectCatalog catalog = new SchemaObjectCatalog(new AppProperties());
        MetadataService metadata = new MetadataService(connections, dialects, audit, cache, new ExecutionGuard());
        SchemaObjectService schemaObjects = new SchemaObjectService(connections, dialects, catalog, cache, new ExecutionGuard(),
                audit, mock(SqlHistoryRepository.class), new AppProperties(), new SqlScriptSplitter());
        return new Fixture(url, new SchemaExportService(connections, dialects, metadata, schemaObjects, catalog, audit), audit);
    }

    private record Fixture(String url, SchemaExportService service, AuditRepository audit) {
    }
}
