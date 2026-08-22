package com.example.dbadmin.service;

import com.example.dbadmin.config.AppProperties;
import com.example.dbadmin.core.DialectRegistry;
import com.example.dbadmin.dto.ApiDtos.ObjectSearchResponse;
import com.example.dbadmin.model.DbConnection;
import com.example.dbadmin.repo.AuditRepository;
import com.example.dbadmin.repo.MetadataWriteQueue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ObjectSearchServiceTest {
    private ObjectSearchService service;

    @BeforeEach
    void setUp() throws Exception {
        String url = "jdbc:h2:mem:object-search-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        new JdbcTemplate(new DriverManagerDataSource(url, "sa", "")).execute("""
                CREATE TABLE customer_orders(id INT PRIMARY KEY, total DECIMAL(10,2));
                CREATE TABLE order_items(id INT PRIMARY KEY, sku VARCHAR(40));
                CREATE TABLE unrelated(id INT PRIMARY KEY);
                CREATE VIEW order_summary AS SELECT id FROM customer_orders;
                CREATE SEQUENCE order_seq;
                """);

        DbConnection model = new DbConnection(
                1L, "shop", "h2", url, "sa", "", "dev", false, Instant.now(), Instant.now()
        );
        ConnectionService connections = mock(ConnectionService.class);
        when(connections.require(anyLong())).thenReturn(model);
        when(connections.open(anyLong())).thenAnswer(_i -> DriverManager.getConnection(url, "sa", ""));
        when(connections.open(anyLong(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(_i -> DriverManager.getConnection(url, "sa", ""));

        DialectRegistry dialects = new DialectRegistry();
        MetadataCacheService cache = new MetadataCacheService();
        AuditRepository audit = mock(AuditRepository.class);
        MetadataService metadata = new MetadataService(connections, dialects, audit, cache, new ExecutionGuard());
        SchemaObjectService schemaObjects = new SchemaObjectService(
                connections,
                dialects,
                new SchemaObjectCatalog(new AppProperties()),
                cache,
                new ExecutionGuard(),
                audit,
                mock(com.example.dbadmin.repo.SqlHistoryRepository.class),
                new AppProperties(),
                new SqlScriptSplitter()
        );
        service = new ObjectSearchService(connections, dialects, metadata, schemaObjects);
    }

    @Test
    void findsTablesAndViewsInOneQuery() throws Exception {
        ObjectSearchResponse response = service.search(1L, null, "order", null);

        assertThat(response.hits()).extracting("name")
                .contains("CUSTOMER_ORDERS", "ORDER_ITEMS", "ORDER_SUMMARY");
        assertThat(response.hits()).extracting("name").doesNotContain("UNRELATED");
    }

    @Test
    void reachesObjectKindsTheExplorerHidesBehindAKindPicker() throws Exception {
        ObjectSearchResponse response = service.search(1L, null, "order", null);

        // 序列跟表、视图在同一次搜索里返回 —— 这正是分类型搜索做不到的事。
        assertThat(response.hits()).extracting("kind").contains("SEQUENCE");
        assertThat(response.hits())
                .filteredOn(hit -> "SEQUENCE".equals(hit.kind()))
                .allSatisfy(hit -> assertThat(hit.objectKey()).isNotBlank());
    }

    @Test
    void doesNotReturnViewsTwice() throws Exception {
        ObjectSearchResponse response = service.search(1L, null, "order_summary", null);

        assertThat(response.hits()).filteredOn(hit -> hit.name().equalsIgnoreCase("ORDER_SUMMARY")).hasSize(1);
    }

    @Test
    void tablesCarryNoObjectKeyBecauseTheyAreOpenedBySchemaAndName() throws Exception {
        ObjectSearchResponse response = service.search(1L, null, "customer_orders", null);

        assertThat(response.hits())
                .filteredOn(hit -> "TABLE".equals(hit.kind()))
                .allSatisfy(hit -> {
                    assertThat(hit.objectKey()).isNull();
                    assertThat(hit.schemaName()).isNotBlank();
                });
    }

    @Test
    void honoursTheResultCapAndReportsTruncation() throws Exception {
        ObjectSearchResponse capped = service.search(1L, null, "order", 2);

        assertThat(capped.hits()).hasSize(2);
        assertThat(capped.truncated()).isTrue();
    }

    @Test
    void anEmptyKeywordListsWhateverFitsRatherThanFailing() throws Exception {
        ObjectSearchResponse response = service.search(1L, null, "  ", null);

        assertThat(response.hits()).isNotEmpty();
        assertThat(response.schemaName()).isNotBlank();
    }

    @Test
    void rejectsAnAbsurdlyLongKeyword() {
        assertThatThrownBy(() -> service.search(1L, null, "x".repeat(500), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("200");
    }

    @Test
    void reportsTheSchemaTheSearchActuallyRanAgainst() throws Exception {
        try (Connection ignored = DriverManager.getConnection("jdbc:h2:mem:probe", "sa", "")) {
            ObjectSearchResponse response = service.search(1L, null, "order", null);
            assertThat(response.schemaName()).isEqualTo("PUBLIC");
            assertThat(response.namespaceKind()).isNotBlank();
        }
    }
}
