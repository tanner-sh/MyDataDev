package com.example.dbadmin.service.ai;

import com.example.dbadmin.config.AppProperties;
import com.example.dbadmin.core.DialectRegistry;
import com.example.dbadmin.model.DbConnection;
import com.example.dbadmin.service.ConnectionService;
import com.example.dbadmin.service.SqlScriptSplitter;
import com.example.dbadmin.service.SqlStatementClassifier;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiSqlValidationServiceTest {
    @Test
    void compilesAReadOnlyQueryAndReportsDatabaseErrors() throws Exception {
        String url = "jdbc:h2:mem:ai-validation;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE APP_USER(ID BIGINT PRIMARY KEY, DISPLAY_NAME VARCHAR(100))");
        }
        AiSqlValidationService service = service(url);

        assertThat(service.validate(7, "PUBLIC", "SELECT DISPLAY_NAME FROM APP_USER").valid()).isTrue();
        assertThat(service.validate(7, "PUBLIC", "SELECT MISSING_COLUMN FROM APP_USER").valid()).isFalse();
        assertThat(service.validate(7, "PUBLIC", "SELECT MISSING_COLUMN FROM APP_USER").message())
                .contains("编译失败");
    }

    @Test
    void rejectsMutationsBeforeOpeningADatabaseConnection() throws Exception {
        ConnectionService connections = mock(ConnectionService.class);
        AiSqlValidationService service = new AiSqlValidationService(connections, new DialectRegistry(),
                new SqlScriptSplitter(), new SqlStatementClassifier(), new AppProperties());

        assertThat(service.validate(7, "PUBLIC", "DELETE FROM APP_USER").valid()).isFalse();
        verify(connections, never()).open(anyLong(), nullable(String.class));
    }

    /**
     * SHOW 与 EXPLAIN 也是只读的，但能不能「只解析不取数」地校验，各方言差别很大 ——
     * {@code EXPLAIN ANALYZE SELECT} 在 PostgreSQL 上是真跑一遍。校验入口只收 SELECT。
     */
    @Test
    void rejectsReadOnlyStatementsThatAreNotPlainSelects() throws Exception {
        ConnectionService connections = mock(ConnectionService.class);
        AiSqlValidationService service = new AiSqlValidationService(connections, new DialectRegistry(),
                new SqlScriptSplitter(), new SqlStatementClassifier(), new AppProperties());

        assertThat(service.validate(7, "PUBLIC", "SHOW TABLES").valid()).isFalse();
        assertThat(service.validate(7, "PUBLIC", "EXPLAIN ANALYZE SELECT * FROM APP_USER").valid()).isFalse();
        assertThat(service.validate(7, "PUBLIC", "SELECT 1; SELECT 2").valid()).isFalse();
        verify(connections, never()).open(anyLong(), nullable(String.class));
    }

    @Test
    void acceptsAWithPrefixedSelect() throws Exception {
        String url = "jdbc:h2:mem:ai-validation-cte;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE ORDERS(ID BIGINT PRIMARY KEY, AMOUNT INT)");
        }

        assertThat(service(url).validate(7, "PUBLIC",
                "WITH BIG AS (SELECT ID FROM ORDERS WHERE AMOUNT > 100) SELECT ID FROM BIG").valid()).isTrue();
    }

    private static AiSqlValidationService service(String url) throws Exception {
        ConnectionService connections = mock(ConnectionService.class);
        when(connections.require(anyLong())).thenReturn(new DbConnection(
                7, "test", "h2", url, "sa", "", "dev", false, Instant.now(), Instant.now()));
        when(connections.open(anyLong(), nullable(String.class)))
                .thenAnswer(ignored -> DriverManager.getConnection(url));
        return new AiSqlValidationService(connections, new DialectRegistry(),
                new SqlScriptSplitter(), new SqlStatementClassifier(), new AppProperties());
    }
}
