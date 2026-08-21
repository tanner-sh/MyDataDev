package com.example.dbadmin.service;

import com.example.dbadmin.api.ApiProblemException;
import com.example.dbadmin.config.AppProperties;
import com.example.dbadmin.core.DialectRegistry;
import com.example.dbadmin.dto.ApiDtos.SqlScriptResponse;
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
        assertThat(response.results().get(1).result().columns()).extracting("label").contains("NAME");
        assertThat(response.results().get(1).result().rows().get(0)).contains("Alice");
        assertThat(response.results().get(1).result().sourceTable().nameParts()).containsExactly("PUBLIC", "USERS");
        verify(history).insert(eq(1L), eq("insert into users(id, name) values (1, 'Alice'); select * from users"), eq("EXECUTE_SCRIPT"), eq("SUCCESS"), anyLong(), eq(null), eq("admin"));
    }

    @Test
    void rejectsUnscopedUpdateBeforeExecutingAnyStatementAndAllowsExplicitRetry() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            connection.createStatement().execute("CREATE TABLE users(id INT PRIMARY KEY, name VARCHAR(20))");
            connection.createStatement().execute("INSERT INTO users VALUES (1, 'Alice'), (2, 'Bob')");
        }
        SqlService service = service(url, mock(SqlHistoryRepository.class));
        String script = "insert into users values (3, 'Carol'); update users set name = 'changed'";

        assertThatThrownBy(() -> service.executeScript(
                1L, script, 500, null, "admin", null, null, null, false
        ))
                .isInstanceOfSatisfying(ApiProblemException.class, problem -> {
                    assertThat(problem.code()).isEqualTo("UNSCOPED_MUTATION_CONFIRMATION_REQUIRED");
                    assertThat(problem.details().get("statements")).asList().hasSize(1);
                });

        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             var resultSet = connection.createStatement().executeQuery("SELECT COUNT(*) FROM users")) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getInt(1)).isEqualTo(2);
        }

        SqlScriptResponse response = service.executeScript(
                1L, script, 500, null, "admin", null, null, null, true
        );
        assertThat(response.status()).isEqualTo("SUCCESS");
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             var resultSet = connection.createStatement().executeQuery("SELECT COUNT(*) FROM users WHERE name = 'changed'")) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getInt(1)).isEqualTo(3);
        }
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
    void preservesBigIntAndDecimalValuesForJavaScriptClients() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        SqlService service = service(url, mock(SqlHistoryRepository.class));

        SqlScriptResponse response = service.executeScript(
                1L,
                "select cast(9007199254740993 as bigint) as big_id, cast(1234567890.123456789 as decimal(30, 9)) as amount",
                10,
                "admin"
        );

        assertThat(response.results().get(0).result().rows().get(0))
                .containsExactly("9007199254740993", "1234567890.123456789");
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

    @Test
    void evictsMetadataCacheAfterFailedDdlAttempt() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        MetadataService metadata = mock(MetadataService.class);
        SqlService service = service(url, mock(SqlHistoryRepository.class), metadata);

        SqlScriptResponse response = service.executeScript(
                1L,
                "CREATE TABLE broken_table(id THIS_TYPE_DOES_NOT_EXIST)",
                500,
                "admin"
        );

        assertThat(response.status()).isEqualTo("FAILED");
        verify(metadata).invalidateConnection(1L);
    }

    @Test
    void singleStatementEndpointRejectsAHiddenSecondStatement() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            connection.createStatement().execute("CREATE TABLE users(id INT PRIMARY KEY)");
        }
        SqlService service = service(url, mock(SqlHistoryRepository.class));

        assertThatThrownBy(() -> service.execute(1L, "select 1; drop table users", 10, "admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("仅支持一条 SQL");

        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             var resultSet = connection.createStatement().executeQuery("SELECT COUNT(*) FROM users")) {
            assertThat(resultSet.next()).isTrue();
        }
    }

    @Test
    void pagesSingleSelectWithoutLoadingTheWholeResult() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        SqlService service = service(url, mock(SqlHistoryRepository.class));

        var first = service.executePage(1L, "select x from system_range(1, 5) order by x", 0, 2, "admin", null, null);
        var second = service.executePage(1L, "select x from system_range(1, 5) order by x", 2, 2, "admin", null, null);
        var last = service.executePage(1L, "select x from system_range(1, 5) order by x", 4, 2, "admin", null, null);

        assertThat(first.rows()).containsExactly(java.util.List.of("1"), java.util.List.of("2"));
        assertThat(first.page().offset()).isZero();
        assertThat(first.page().hasMore()).isTrue();
        assertThat(second.rows()).containsExactly(java.util.List.of("3"), java.util.List.of("4"));
        assertThat(second.page().hasMore()).isTrue();
        assertThat(last.rows()).containsExactly(java.util.List.of("5"));
        assertThat(last.page().hasMore()).isFalse();
    }

    @Test
    void returnsPagingMetadataForNewScriptClients() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        SqlService service = service(url, mock(SqlHistoryRepository.class));

        SqlScriptResponse response = service.executeScript(
                1L, "select x from system_range(1, 3) order by x", null, 2, "admin", null, null
        );

        var result = response.results().get(0).result();
        assertThat(result.rows()).hasSize(2);
        assertThat(result.page()).isNotNull();
        assertThat(result.page().hasMore()).isTrue();
        assertThat(result.truncated()).isFalse();
    }

    @Test
    void executesAndPagesWithinRequestedSchema() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            connection.createStatement().execute("CREATE SCHEMA ARCHIVE");
            connection.createStatement().execute("CREATE TABLE PUBLIC.context_values(id INT PRIMARY KEY, label VARCHAR(20))");
            connection.createStatement().execute("CREATE TABLE ARCHIVE.context_values(id INT PRIMARY KEY, label VARCHAR(20))");
            connection.createStatement().execute("INSERT INTO PUBLIC.context_values VALUES (1, 'default')");
            connection.createStatement().execute("INSERT INTO ARCHIVE.context_values VALUES (1, 'archive-1'), (2, 'archive-2'), (3, 'archive-3')");
        }
        SqlService service = service(url, mock(SqlHistoryRepository.class));

        var direct = service.execute(1L, "select label from context_values order by id", 10, "admin", null, null, "ARCHIVE");
        var firstPage = service.executeScript(
                1L, "select label from context_values order by id", null, 2, "admin", null, null, "ARCHIVE"
        ).results().get(0).result();
        var secondPage = service.executePage(
                1L, "select label from context_values order by id", 2, 2, "admin", null, null, firstPage.page().schemaName()
        );

        assertThat(direct.rows()).extracting(row -> row.get(0)).containsExactly("archive-1", "archive-2", "archive-3");
        assertThat(firstPage.rows()).extracting(row -> row.get(0)).containsExactly("archive-1", "archive-2");
        assertThat(firstPage.page().schemaName()).isEqualTo("ARCHIVE");
        assertThat(secondPage.rows()).extracting(row -> row.get(0)).containsExactly("archive-3");
        assertThat(secondPage.page().schemaName()).isEqualTo("ARCHIVE");
    }

    @Test
    void allowsMoreThanFiftyStatementsByDefault() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        SqlService service = service(url, mock(SqlHistoryRepository.class));

        SqlScriptResponse response = service.executeScript(1L, selectScript(51), 10, "admin");

        assertThat(new AppProperties().getSql().getMaxStatements()).isEqualTo(500);
        assertThat(response.status()).isEqualTo("SUCCESS");
        assertThat(response.executedCount()).isEqualTo(51);
        assertThat(response.results()).hasSize(51);
    }

    @Test
    void enforcesConfiguredStatementLimitAndRecommendsFileExecution() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        AppProperties properties = new AppProperties();
        properties.getSql().setMaxStatements(2);
        SqlService service = service(url, mock(SqlHistoryRepository.class), mock(MetadataService.class), properties);

        assertThat(service.executeScript(1L, selectScript(2), 10, "admin").executedCount()).isEqualTo(2);
        assertThatThrownBy(() -> service.executeScript(1L, selectScript(3), 10, "admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("一次最多执行 2 条 SQL")
                .hasMessageContaining("执行本地 SQL 文件");
    }

    @Test
    void keepsExplicitlyPagedSelectAsOneShotResult() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        SqlService service = service(url, mock(SqlHistoryRepository.class));

        SqlScriptResponse response = service.executeScript(
                1L, "select x from system_range(1, 5) order by x limit 2", 500, "admin"
        );

        assertThat(response.results().get(0).result().rows()).hasSize(2);
        assertThat(response.results().get(0).result().page()).isNull();
    }

    @Test
    void formatsClausesWithoutChangingQuotedTextOrComments() throws Exception {
        SqlService service = service(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1",
                mock(SqlHistoryRepository.class)
        );

        String formatted = service.format("""
                select 'from  where' as text_value, "order by" as quoted_name,
                       $tag$select  from$tag$ as function_body, q'[left  join]' as oracle_text
                from users left join roles on roles.id = users.role_id -- where  join
                where users.name = 'A  B'
                """);

        assertThat(formatted)
                .contains("'from  where'")
                .contains("\"order by\"")
                .contains("$tag$select  from$tag$")
                .contains("q'[left  join]'")
                .contains("-- where  join\n")
                .contains("\n    LEFT JOIN roles")
                .contains("\nWHERE users.name")
                .contains("'A  B'");
    }

    private SqlService service(String url, SqlHistoryRepository history) throws Exception {
        return service(url, history, mock(MetadataService.class));
    }

    private SqlService service(String url, SqlHistoryRepository history, MetadataService metadata) throws Exception {
        AppProperties properties = new AppProperties();
        properties.getSql().setMaxRows(1000);
        properties.getSql().setTimeoutSeconds(10);
        return service(url, history, metadata, properties);
    }

    private SqlService service(String url, SqlHistoryRepository history, MetadataService metadata, AppProperties properties) throws Exception {
        ConnectionService connections = mock(ConnectionService.class);
        when(connections.open(anyLong())).thenAnswer(_invocation -> DriverManager.getConnection(url, "sa", ""));
        when(connections.open(anyLong(), anyString())).thenAnswer(invocation -> {
            Connection connection = DriverManager.getConnection(url, "sa", "");
            connection.setSchema(invocation.getArgument(1, String.class));
            return connection;
        });
        when(connections.require(anyLong())).thenReturn(new DbConnection(
                1L, "h2", "h2", url, "sa", "", "dev", false, Instant.now(), Instant.now()
        ));
        return new SqlService(
                connections,
                properties,
                mock(AuditRepository.class),
                new DialectRegistry(),
                history,
                metadata,
                new SqlScriptSplitter(),
                new SqlStatementClassifier(),
                new ExecutionGuard(),
                new SqlExecutionRegistry()
        );
    }

    private String selectScript(int statements) {
        return java.util.stream.IntStream.rangeClosed(1, statements)
                .mapToObj(index -> "select " + index + " as val")
                .collect(java.util.stream.Collectors.joining(";"));
    }
}
