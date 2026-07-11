package com.example.dbadmin.service;

import com.example.dbadmin.config.AppProperties;
import com.example.dbadmin.core.DialectRegistry;
import com.example.dbadmin.dto.ApiDtos.SqlScriptResponse;
import com.example.dbadmin.repo.AuditRepository;
import com.example.dbadmin.repo.SqlHistoryRepository;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SqlServiceScriptTest {
    @Test
    void executesMultipleStatementsAndReturnsEachResult() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            connection.createStatement().execute("CREATE TABLE users(id INT PRIMARY KEY, name VARCHAR(20))");
        }
        SqlHistoryRepository history = mock(SqlHistoryRepository.class);
        SqlService service = service(url, history);

        SqlScriptResponse response = service.executeScript(
                1L,
                "insert into users(id, name) values (1, 'Alice'); select * from users",
                500,
                "admin"
        );

        assertThat(response.status()).isEqualTo("SUCCESS");
        assertThat(response.executedCount()).isEqualTo(2);
        assertThat(response.results()).hasSize(2);
        assertThat(response.results().get(0).result().affectedRows()).isEqualTo(1);
        assertThat(response.results().get(1).result().resultSet()).isTrue();
        assertThat(response.results().get(1).result().rows()).hasSize(1);
        assertThat(response.results().get(1).result().rows().get(0)).containsEntry("NAME", "Alice");
        verify(history).insert(eq(1L), eq("insert into users(id, name) values (1, 'Alice'); select * from users"), eq("EXECUTE_SCRIPT"), eq("SUCCESS"), anyLong(), eq(null), eq("admin"));
    }

    @Test
    void stopsAtFirstFailedStatementAndKeepsSuccessfulResults() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            connection.createStatement().execute("CREATE TABLE users(id INT PRIMARY KEY)");
        }
        SqlService service = service(url, mock(SqlHistoryRepository.class));

        SqlScriptResponse response = service.executeScript(
                1L,
                "insert into users(id) values (1); select * from missing_table; insert into users(id) values (2)",
                500,
                "admin"
        );

        assertThat(response.status()).isEqualTo("FAILED");
        assertThat(response.executedCount()).isEqualTo(2);
        assertThat(response.results()).hasSize(2);
        assertThat(response.results().get(0).status()).isEqualTo("SUCCESS");
        assertThat(response.results().get(1).status()).isEqualTo("FAILED");
        assertThat(response.results().get(1).sql()).isEqualTo("select * from missing_table");
        assertThat(response.results().get(1).errorMessage()).containsIgnoringCase("missing_table");

        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            var rs = connection.createStatement().executeQuery("select count(*) from users");
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(1);
        }
    }

    @Test
    void reportsTruncatedResultAtRequestedRowLimit() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        SqlService service = service(url, mock(SqlHistoryRepository.class));

        SqlScriptResponse response = service.executeScript(
                1L,
                "select x from system_range(1, 3)",
                2,
                "admin"
        );

        var result = response.results().get(0).result();
        assertThat(result.rows()).hasSize(2);
        assertThat(result.maxRows()).isEqualTo(2);
        assertThat(result.truncated()).isTrue();
    }

    @Test
    void marksMetadataChangesAndEvictsMetadataCacheAfterSuccessfulDdl() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        MetadataService metadata = mock(MetadataService.class);
        SqlService service = service(url, mock(SqlHistoryRepository.class), metadata);

        SqlScriptResponse response = service.executeScript(
                1L,
                "/* migration */ CREATE TABLE orders(id INT PRIMARY KEY); select * from orders",
                500,
                "admin"
        );

        assertThat(response.status()).isEqualTo("SUCCESS");
        assertThat(response.metadataChanged()).isTrue();
        verify(metadata).invalidateConnection(1L);
    }

    private SqlService service(String url, SqlHistoryRepository history) throws Exception {
        return service(url, history, mock(MetadataService.class));
    }

    private SqlService service(String url, SqlHistoryRepository history, MetadataService metadata) throws Exception {
        ConnectionService connections = mock(ConnectionService.class);
        when(connections.open(anyLong())).thenAnswer(_invocation -> DriverManager.getConnection(url, "sa", ""));
        AppProperties properties = new AppProperties();
        properties.getSql().setMaxRows(1000);
        properties.getSql().setTimeoutSeconds(10);
        return new SqlService(
                connections,
                properties,
                mock(AuditRepository.class),
                mock(DialectRegistry.class),
                history,
                metadata,
                new SqlScriptSplitter()
        );
    }
}
