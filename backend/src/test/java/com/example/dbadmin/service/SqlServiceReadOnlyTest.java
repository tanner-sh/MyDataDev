package com.example.dbadmin.service;

import com.example.dbadmin.config.AppProperties;
import com.example.dbadmin.core.DialectRegistry;
import com.example.dbadmin.model.DbConnection;
import com.example.dbadmin.repo.AuditRepository;
import com.example.dbadmin.repo.SqlHistoryRepository;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SqlServiceReadOnlyTest {
    private static final SqlQueryLimits LIMITS = new SqlQueryLimits(2, 3, 4, 12, 6, 5);

    @Test
    void executesQueryWithMachineSpecificRowCellAndTextLimits() throws Exception {
        String url = database();
        SqlService service = service(url, true);

        var result = service.executeReadOnly(
                1L,
                "select id, name from users order by id",
                null,
                3,
                "mcp:test",
                LIMITS
        );

        assertThat(result.rows()).hasSize(2);
        assertThat(result.rows().get(0)).containsExactly(1, "abcdef");
        assertThat(result.rows().get(1)).containsExactly(2, "ghijkl");
        assertThat(result.truncated()).isTrue();
    }

    @Test
    void queriesWritableConnectionsButStillRejectsMutationsAndMultipleStatements() throws Exception {
        String url = database();
        SqlService writable = service(url, false);

        assertThatThrownBy(() -> writable.executeReadOnly(1L, "update users set name='changed'", null, 2, "mcp:test", LIMITS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("只允许");
        assertThatThrownBy(() -> writable.executeReadOnly(1L, "select 1; delete from users", null, 2, "mcp:test", LIMITS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("一条 SQL");

        var query = writable.executeReadOnly(1L, "select id, name from users order by id", null, 2, "mcp:test", LIMITS);
        assertThat(query.rows()).hasSize(2);
        assertThat(writable.explainReadOnly(1L, "select * from users", null, "mcp:test", LIMITS).rows()).isNotEmpty();

        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            var rs = connection.createStatement().executeQuery("select name from users where id=1");
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("abcdefgh");
        }
    }

    private String database() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            connection.createStatement().execute("create table users(id int primary key, name varchar(40))");
            connection.createStatement().execute("insert into users values (1, 'abcdefgh'), (2, 'ghijklmn'), (3, 'third-row')");
        }
        return url;
    }

    private SqlService service(String url, boolean readonly) throws Exception {
        ConnectionService connections = mock(ConnectionService.class);
        when(connections.open(anyLong())).thenAnswer(_invocation -> DriverManager.getConnection(url, "sa", ""));
        when(connections.open(anyLong(), anyString())).thenAnswer(invocation -> {
            Connection connection = DriverManager.getConnection(url, "sa", "");
            connection.setSchema(invocation.getArgument(1, String.class));
            return connection;
        });
        when(connections.require(anyLong())).thenReturn(new DbConnection(
                1L, "h2", "h2", url, "sa", "", "dev", readonly, Instant.now(), Instant.now()
        ));
        AppProperties properties = new AppProperties();
        properties.getSql().setTimeoutSeconds(10);
        return new SqlService(
                connections,
                properties,
                mock(AuditRepository.class),
                new DialectRegistry(),
                mock(SqlHistoryRepository.class),
                mock(MetadataService.class),
                new SqlScriptSplitter(),
                new SqlStatementClassifier(),
                new ExecutionGuard(),
                new SqlExecutionRegistry()
        );
    }
}
