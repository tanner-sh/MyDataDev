package com.example.dbadmin.service;

import com.example.dbadmin.core.DialectRegistry;
import com.example.dbadmin.dto.SchemaDiagramDtos.DiagramTable;
import com.example.dbadmin.dto.SchemaDiagramDtos.SchemaDiagram;
import com.example.dbadmin.model.DbConnection;
import com.example.dbadmin.repo.AuditRepository;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** ER 图的数据装配：真实 JDBC 元数据，覆盖复合外键、自引用与图外引用。 */
class SchemaDiagramServiceTest {
    @Test
    void collectsKeyColumnsAndForeignKeysForTheSchema() throws Exception {
        SchemaDiagram diagram = diagram("""
                CREATE TABLE countries(id BIGINT PRIMARY KEY, code VARCHAR(2), name VARCHAR(80));
                CREATE TABLE cities(id BIGINT PRIMARY KEY, country_id BIGINT NOT NULL REFERENCES countries(id), name VARCHAR(80));
                """, null);

        assertThat(diagram.tables()).extracting(DiagramTable::name)
                .containsExactlyInAnyOrder("COUNTRIES", "CITIES");
        var cities = diagram.tables().stream().filter(table -> table.name().equals("CITIES")).findFirst().orElseThrow();
        // 只返回参与关系的列：name 不是主键也不是外键，不该出现。
        assertThat(cities.keyColumns()).extracting("name").containsExactly("ID", "COUNTRY_ID");
        assertThat(cities.keyColumns()).extracting("primaryKey").containsExactly(true, false);
        assertThat(cities.keyColumns()).extracting("foreignKey").containsExactly(false, true);
        assertThat(cities.columnCount()).isEqualTo(3);

        assertThat(diagram.relations()).hasSize(1);
        assertThat(diagram.relations().get(0).fromTable()).isEqualTo("CITIES");
        assertThat(diagram.relations().get(0).toTable()).isEqualTo("COUNTRIES");
        assertThat(diagram.truncated()).isFalse();
    }

    @Test
    void keepsEveryColumnOfACompositeForeignKey() throws Exception {
        SchemaDiagram diagram = diagram("""
                CREATE TABLE tenants(tenant_id BIGINT, id BIGINT, PRIMARY KEY(tenant_id, id));
                CREATE TABLE items(id BIGINT PRIMARY KEY, tenant_id BIGINT, owner_id BIGINT,
                    FOREIGN KEY(tenant_id, owner_id) REFERENCES tenants(tenant_id, id));
                """, null);

        // 复合外键在 JDBC 元数据里是每列一行，服务端原样返回，由前端合成一条边。
        assertThat(diagram.relations()).hasSize(2);
        assertThat(diagram.relations()).extracting("fromColumn").containsExactlyInAnyOrder("TENANT_ID", "OWNER_ID");
        assertThat(diagram.relations()).extracting("constraintName").doesNotContainNull();
        var items = diagram.tables().stream().filter(table -> table.name().equals("ITEMS")).findFirst().orElseThrow();
        assertThat(items.keyColumns()).extracting("name").containsExactly("ID", "TENANT_ID", "OWNER_ID");
    }

    @Test
    void keepsSelfReferences() throws Exception {
        SchemaDiagram diagram = diagram("""
                CREATE TABLE employees(id BIGINT PRIMARY KEY, manager_id BIGINT REFERENCES employees(id), name VARCHAR(40));
                """, null);

        assertThat(diagram.relations()).hasSize(1);
        assertThat(diagram.relations().get(0).fromTable()).isEqualTo("EMPLOYEES");
        assertThat(diagram.relations().get(0).toTable()).isEqualTo("EMPLOYEES");
    }

    @Test
    void dropsRelationsPointingOutsideTheDrawnSet() throws Exception {
        // limit=1 只画一张表，另一端落在图外的外键必须丢掉 ——
        // 画一根连到不存在的方块的线，比不画更让人困惑。
        SchemaDiagram diagram = diagram("""
                CREATE TABLE a_countries(id BIGINT PRIMARY KEY);
                CREATE TABLE b_cities(id BIGINT PRIMARY KEY, country_id BIGINT REFERENCES a_countries(id));
                """, 1);

        assertThat(diagram.tables()).hasSize(1);
        assertThat(diagram.relations()).isEmpty();
        assertThat(diagram.truncated()).isTrue();
        assertThat(diagram.totalTables()).isEqualTo(2);
    }

    @Test
    void clampsTheRequestedLimitToTheHardMaximum() throws Exception {
        SchemaDiagram diagram = diagram("CREATE TABLE solo(id BIGINT PRIMARY KEY);", 10_000);

        // 请求值被夹到 MAX_TABLE_LIMIT，不会因为一个大数字把远端连接池占住。
        assertThat(diagram.tables()).hasSize(1);
        assertThat(diagram.truncated()).isFalse();
    }

    @Test
    void ignoresViewsBecauseTheyCarryNoForeignKeys() throws Exception {
        SchemaDiagram diagram = diagram("""
                CREATE TABLE orders(id BIGINT PRIMARY KEY);
                CREATE VIEW recent_orders AS SELECT id FROM orders;
                """, null);

        assertThat(diagram.tables()).extracting(DiagramTable::name).containsExactly("ORDERS");
    }

    private SchemaDiagram diagram(String ddl, Integer limit) throws Exception {
        String url = "jdbc:h2:mem:er-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            for (String statement : ddl.split(";")) {
                if (!statement.isBlank()) connection.createStatement().execute(statement);
            }
        }
        ConnectionService connections = mock(ConnectionService.class);
        when(connections.open(1L)).thenAnswer(ignored -> DriverManager.getConnection(url, "sa", ""));
        when(connections.require(1L)).thenReturn(new DbConnection(
                1L, "h2", "h2", url, "sa", "", "dev", false, Instant.now(), Instant.now()
        ));
        MetadataService metadata = new MetadataService(
                connections, new DialectRegistry(), mock(AuditRepository.class), new MetadataCacheService(), new ExecutionGuard()
        );
        return new SchemaDiagramService(metadata).build(1L, "PUBLIC", limit);
    }

    @Test
    void limitIsAtLeastOne() throws Exception {
        assertThat(diagram("CREATE TABLE solo(id BIGINT PRIMARY KEY);", 0).tables()).hasSize(1);
    }

    @Test
    void emptySchemaProducesAnEmptyDiagram() throws Exception {
        SchemaDiagram diagram = diagram("CREATE TABLE placeholder(id BIGINT PRIMARY KEY);", null);
        assertThat(diagram.relations()).isEmpty();
        assertThat(diagram.tables()).extracting(DiagramTable::name).isEqualTo(List.of("PLACEHOLDER"));
    }
}
