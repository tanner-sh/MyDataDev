package com.example.dbadmin.service;

import com.example.dbadmin.dto.ApiDtos.MetadataResponse;
import com.example.dbadmin.dto.ApiDtos.ObjectDetail;
import com.example.dbadmin.dto.ApiDtos.ObjectStructure;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
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
        ConnectionService connections = mock(ConnectionService.class);
        when(connections.open(anyLong())).thenAnswer(_invocation -> DriverManager.getConnection(url, "sa", ""));

        MetadataService service = new MetadataService(connections);
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
        ConnectionService connections = mock(ConnectionService.class);
        when(connections.open(anyLong())).thenAnswer(_invocation -> DriverManager.getConnection(url, "sa", ""));

        MetadataService service = new MetadataService(connections);
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
        ConnectionService connections = mock(ConnectionService.class);
        when(connections.open(anyLong())).thenAnswer(_invocation -> DriverManager.getConnection(url, "sa", ""));

        MetadataService service = new MetadataService(connections);
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
        ConnectionService connections = mock(ConnectionService.class);
        when(connections.open(anyLong())).thenAnswer(_invocation -> DriverManager.getConnection(url, "sa", ""));

        MetadataService service = new MetadataService(connections);
        ObjectDetail detail = service.detail(1L, null, "ACTIVE_USERS");

        assertThat(detail.type()).contains("VIEW");
        assertThat(detail.rowCount()).isNull();
        assertThat(detail.ddl()).contains("视图定义反查暂未实现");
    }
}
