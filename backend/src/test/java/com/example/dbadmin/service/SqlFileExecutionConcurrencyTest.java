package com.example.dbadmin.service;

import com.example.dbadmin.api.ApiProblemException;
import com.example.dbadmin.config.AppProperties;
import com.example.dbadmin.core.DialectRegistry;
import com.example.dbadmin.mcp.McpConfigurationService;
import com.example.dbadmin.mcp.McpRuntimeConfig;
import com.example.dbadmin.model.DbConnection;
import com.example.dbadmin.model.SqlFileExecution;
import com.example.dbadmin.model.SqlFileTransaction;
import com.example.dbadmin.repo.AuditRepository;
import com.example.dbadmin.repo.SqlFileExecutionRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SqlFileExecutionConcurrencyTest {
    @Test
    void startUsesLiveSettingAndRetainsPermitsUntilWorkersFinish() throws Exception {
        AppProperties properties = new AppProperties();
        SqlFileExecutionRepository jobs = mock(SqlFileExecutionRepository.class);
        ConnectionService connections = mock(ConnectionService.class);
        SqlFileExecutionCoordinator coordinator = mock(SqlFileExecutionCoordinator.class);
        BackgroundTaskControl control = new BackgroundTaskControl(properties);
        when(connections.require(7)).thenReturn(new DbConnection(7L, "test", "h2", "jdbc:h2:mem:test",
                "sa", "", "dev", false, Instant.now(), Instant.now()));
        when(jobs.queue(anyLong(), anyString(), anyString(), anyInt())).thenReturn(true);
        for (long id = 1; id <= 3; id++) {
            SqlFileExecution job = new SqlFileExecution(id, 7, "test", "h2", "test.sql", "/tmp/test.sql",
                    10, "abc", "UTF-8", "READY", "READY", 0, 1L, 0, 0, 1, 0, 0, 0, 0,
                    null, null, null, false, false, false, "agent", Instant.now().plusSeconds(3600),
                    null, null, Instant.now(), SqlFileTransaction.defaults());
            when(jobs.findById(id)).thenReturn(Optional.of(job));
        }
        SqlFileExecutionService service = new SqlFileExecutionService(jobs, connections, mock(ExecutionGuard.class),
                new SqlStatementClassifier(), new SqlScriptSplitter(), new DialectRegistry(), mock(MetadataService.class),
                mock(AuditRepository.class), properties, coordinator, control, new LargeFileUploadGuard(properties));
        McpConfigurationService config = mock(McpConfigurationService.class);
        when(config.snapshot()).thenReturn(configWithLimit(1));
        service.setMcpConfiguration(config);
        service.start(1, null, "agent-one");
        when(jobs.countRunningByConnection(7)).thenReturn(1);
        assertThatThrownBy(() -> service.start(2, null, "agent-one"))
                .isInstanceOfSatisfying(ApiProblemException.class, error ->
                        org.assertj.core.api.Assertions.assertThat(error.code()).isEqualTo("SQL_FILE_ALREADY_RUNNING"));
        when(config.snapshot()).thenReturn(configWithLimit(2));
        service.start(2, null, "agent-two");
        // Even a stale repository count cannot bypass the in-memory permit gate.
        assertThatThrownBy(() -> service.start(3, null, "agent-one"))
                .isInstanceOfSatisfying(ApiProblemException.class, error ->
                        org.assertj.core.api.Assertions.assertThat(error.code()).isEqualTo("CONNECTION_BACKGROUND_BUSY"));
        var completed = org.mockito.ArgumentCaptor.forClass(Runnable.class);
        verify(coordinator, times(2)).submit(anyLong(), any(Runnable.class), completed.capture());
        completed.getAllValues().get(0).run();
        service.start(3, null, "agent-one");
    }

    private McpRuntimeConfig configWithLimit(int limit) {
        return new McpRuntimeConfig(new McpRuntimeConfig.Settings(true, 100, 500, 20_000,
                1_000_000, 20_000, 200_000, 30, 50, 200, 50, 100, 120, limit), Set.of(), Map.of());
    }
}
