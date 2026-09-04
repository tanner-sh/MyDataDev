package com.example.dbadmin.service.ai;

import com.example.dbadmin.config.AppProperties;
import com.example.dbadmin.service.ConnectionService;
import com.example.dbadmin.service.SqlScriptSplitter;
import com.example.dbadmin.service.SqlStatementClassifier;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiSqlValidationServiceTest {
    @Test
    void compilesAReadOnlyQueryAndReportsDatabaseErrorsWithoutExecutingIt() throws Exception {
        String url = "jdbc:h2:mem:ai-validation;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE APP_USER(ID BIGINT PRIMARY KEY, DISPLAY_NAME VARCHAR(100))");
        }
        ConnectionService connections = mock(ConnectionService.class);
        when(connections.open(anyLong(), nullable(String.class)))
                .thenAnswer(ignored -> DriverManager.getConnection(url));
        AiSqlValidationService service = new AiSqlValidationService(connections,
                new SqlScriptSplitter(), new SqlStatementClassifier(), new AppProperties());

        assertThat(service.validate(7, "PUBLIC", "SELECT DISPLAY_NAME FROM APP_USER").valid()).isTrue();
        assertThat(service.validate(7, "PUBLIC", "SELECT MISSING_COLUMN FROM APP_USER").valid()).isFalse();
        assertThat(service.validate(7, "PUBLIC", "SELECT MISSING_COLUMN FROM APP_USER").message())
                .contains("编译失败");
    }

    @Test
    void rejectsMutationsBeforeOpeningADatabaseConnection() throws Exception {
        ConnectionService connections = mock(ConnectionService.class);
        AiSqlValidationService service = new AiSqlValidationService(connections,
                new SqlScriptSplitter(), new SqlStatementClassifier(), new AppProperties());

        assertThat(service.validate(7, "PUBLIC", "DELETE FROM APP_USER").valid()).isFalse();
        verify(connections, never()).open(anyLong(), nullable(String.class));
    }
}
