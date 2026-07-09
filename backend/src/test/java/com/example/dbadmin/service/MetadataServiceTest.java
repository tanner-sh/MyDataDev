package com.example.dbadmin.service;

import com.example.dbadmin.core.DialectRegistry;
import com.example.dbadmin.dto.ApiDtos.MetadataResponse;
import com.example.dbadmin.dto.ApiDtos.ObjectDetail;
import com.example.dbadmin.dto.ApiDtos.ObjectRelations;
import com.example.dbadmin.dto.ApiDtos.ObjectStructure;
import com.example.dbadmin.dto.ApiDtos.TableDesignRequest;
import com.example.dbadmin.dto.ApiDtos.ColumnDesign;
import com.example.dbadmin.dto.ApiDtos.IndexDesign;
import com.example.dbadmin.model.DbConnection;
import com.example.dbadmin.repo.AuditRepository;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MetadataServiceTest {
    @Test
    void inspectLoadsObjectSummariesWithoutColumnsOrIndexes() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            connection.createStatement().execute("CREATE TABLE alpha_users(id BIGINT PRIMARY KEY, name VARCHAR(40) NOT NULL)");
            connection.createStatement().execute("CREATE INDEX idx_alpha_users_name ON alpha_users(name)");
            connection.createStatement().execute("CREATE TABLE alpha_orders(id BIGINT PRIMARY KEY)");
            connection.createStatement().execute("CREATE TABLE beta_events(id BIGINT PRIMARY KEY)");
        }
        MetadataService service = service(url);
        MetadataResponse response = service.inspect(1L, null, "alpha", 0, 1);

        assertThat(response.page()).isZero();
        assertThat(response.pageSize()).isEqualTo(1);
        assertThat(response.hasMore()).isTrue();
        assertThat(response.objects()).hasSize(1);
        assertThat(response.objects().get(0).name()).contains("ALPHA");
        assertThat(response.objects().get(0).columns()).isEmpty();
        assertThat(response.objects().get(0).indexes()).isEmpty();
    }

    @Test
    void loadsObjectStructureOnDemand() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            connection.createStatement().execute("CREATE TABLE users(id BIGINT PRIMARY KEY, name VARCHAR(40) NOT NULL)");
            connection.createStatement().execute("CREATE INDEX idx_users_name ON users(name)");
        }
        MetadataService service = service(url);
        ObjectStructure structure = service.structure(1L, null, "USERS");

        assertThat(structure.name()).isEqualTo("USERS");
        assertThat(structure.columns()).extracting("name").contains("ID", "NAME");
        assertThat(structure.indexes()).anyMatch(index -> "IDX_USERS_NAME".equals(index.name()) && "NAME".equals(index.columnName()));
    }

    @Test
    void loadsObjectDetailWithPrimaryKeysIndexesRowCountAndDdl() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            connection.createStatement().execute("CREATE TABLE users(id BIGINT PRIMARY KEY, name VARCHAR(40) NOT NULL)");
            connection.createStatement().execute("CREATE INDEX idx_users_name ON users(name)");
            connection.createStatement().execute("INSERT INTO users(id, name) VALUES (1, 'Alice'), (2, 'Bob')");
        }
        MetadataService service = service(url);
        ObjectDetail detail = service.detail(1L, null, "USERS");

        assertThat(detail.name()).isEqualTo("USERS");
        assertThat(detail.primaryKeys()).containsExactly("ID");
        assertThat(detail.rowCount()).isEqualTo(2L);
        assertThat(detail.indexes()).anyMatch(index -> "IDX_USERS_NAME".equals(index.name()) && "NAME".equals(index.columnName()));
        assertThat(detail.ddl()).contains("CREATE TABLE");
        assertThat(detail.ddl()).contains("\"ID\" BIGINT NOT NULL");
        assertThat(detail.ddl()).contains("PRIMARY KEY (\"ID\")");
    }

    @Test
    void skipsRowCountForViews() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            connection.createStatement().execute("CREATE TABLE users(id BIGINT PRIMARY KEY)");
            connection.createStatement().execute("CREATE VIEW active_users AS SELECT * FROM users");
        }
        MetadataService service = service(url);
        ObjectDetail detail = service.detail(1L, null, "ACTIVE_USERS");

        assertThat(detail.type()).contains("VIEW");
        assertThat(detail.rowCount()).isNull();
        assertThat(detail.ddl()).contains("视图定义反查暂未实现");
    }

    @Test
    void loadsImportedAndExportedRelations() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            connection.createStatement().execute("CREATE TABLE users(id BIGINT PRIMARY KEY)");
            connection.createStatement().execute("CREATE TABLE orders(id BIGINT PRIMARY KEY, user_id BIGINT, CONSTRAINT fk_orders_users FOREIGN KEY(user_id) REFERENCES users(id))");
        }

        MetadataService service = service(url);
        ObjectRelations userRelations = service.relations(1L, null, "USERS");
        ObjectRelations orderRelations = service.relations(1L, null, "ORDERS");

        assertThat(userRelations.exportedKeys()).anyMatch(relation -> "ORDERS".equals(relation.fkTableName()) && "USER_ID".equals(relation.fkColumnName()));
        assertThat(orderRelations.importedKeys()).anyMatch(relation -> "USERS".equals(relation.pkTableName()) && "ID".equals(relation.pkColumnName()));
    }

    @Test
    void previewsTableDesignSql() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            connection.createStatement().execute("CREATE TABLE users(id BIGINT PRIMARY KEY, name VARCHAR(40) NOT NULL)");
        }

        MetadataService service = service(url);
        var response = service.previewDesign(1L, new TableDesignRequest(
                null,
                "USERS",
                List.of(
                        new ColumnDesign("ID", "BIGINT", null, false, null, "ID", false),
                        new ColumnDesign("USERNAME", "VARCHAR", 80, false, null, "NAME", false),
                        new ColumnDesign("EMAIL", "VARCHAR", 120, true, null, null, false)
                ),
                List.of(new IndexDesign("IDX_USERS_EMAIL", List.of("EMAIL"), false, null, false)),
                List.of("ID"),
                null
        ));

        assertThat(response.sql()).anyMatch(sql -> sql.contains("RENAME COLUMN"));
        assertThat(response.sql()).anyMatch(sql -> sql.contains("ADD COLUMN"));
        assertThat(response.sql()).anyMatch(sql -> sql.contains("CREATE INDEX"));
    }

    private MetadataService service(String url) throws Exception {
        ConnectionService connections = mock(ConnectionService.class);
        when(connections.open(anyLong())).thenAnswer(_invocation -> DriverManager.getConnection(url, "sa", ""));
        when(connections.require(anyLong())).thenReturn(new DbConnection(1L, "h2", "h2", url, "sa", "", "dev", false, Instant.now(), Instant.now()));
        return new MetadataService(connections, new DialectRegistry(), mock(AuditRepository.class));
    }
}
