package com.example.dbadmin.mcp;

import com.example.dbadmin.model.DbConnection;
import com.example.dbadmin.repo.AuditRepository;
import com.example.dbadmin.service.*;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class McpScriptToolsTest {
    private final ConnectionService connections = mock(ConnectionService.class);
    private final SqlFileExecutionService files = mock(SqlFileExecutionService.class);
    private McpScriptTools tools;
    @BeforeEach void setup() {
        when(connections.require(1)).thenReturn(new DbConnection(1L, "target", "oracle", "jdbc:test", "sa", "", "dev", false, Instant.now(), Instant.now()));
        var config = mock(McpConfigurationService.class);
        when(config.snapshot()).thenReturn(new McpRuntimeConfig(new McpRuntimeConfig.Settings(true, 100, 1000, 200000, 1000000, 10000, 100000, 30, 50, 200, 50, 200, 60), Set.of(), Map.of()));
        tools = new McpScriptTools(new McpAccessService(connections), config, files, new SqlScriptSplitter(), new SqlStatementClassifier(),
                new ExecutionGuard(), mock(SqlTransactionRegistry.class), mock(AuditRepository.class));
    }
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    @Test void doesNotAllowReadOnlyAgentsToSubmitBackgroundScripts() {
        authenticate(McpAccessLevel.READ_ONLY);
        assertThatThrownBy(() -> execute("INSERT INTO t VALUES(1); COMMIT;", false)).hasMessageContaining("访问档位");
        verifyNoInteractions(files);
    }
    @Test void checksEveryUnitBeforeSubmittingAnyPart() {
        authenticate(McpAccessLevel.DATA_WRITE);
        assertThatThrownBy(() -> execute("INSERT INTO t VALUES(1); CREATE TABLE other(id INT);", false)).hasMessageContaining("完全");
        assertThatThrownBy(() -> execute("BEGIN EXECUTE IMMEDIATE 'DROP TABLE t'; END;\n/", true)).hasMessageContaining("完全");
        assertThatThrownBy(() -> execute("BEGIN/* comment */ EXECUTE IMMEDIATE 'DROP TABLE t'; END;\n/", true)).hasMessageContaining("完全");
        verifyNoInteractions(files);
    }
    @Test void requiresUnscopedConfirmationAcrossWholeScript() {
        authenticate(McpAccessLevel.FULL);
        assertThatThrownBy(() -> execute("INSERT INTO t VALUES(1); DELETE FROM t; COMMIT;", false)).hasMessageContaining("unscopedMutationConfirmed");
        verifyNoInteractions(files);
    }
    @Test void checksUnscopedWritesAfterTheFirstStatementOfAGoBatch() {
        authenticate(McpAccessLevel.FULL);
        when(connections.require(1)).thenReturn(new DbConnection(1L, "target", "sqlserver", "jdbc:test", "sa", "", "dev", false, Instant.now(), Instant.now()));
        assertThatThrownBy(() -> tools.executeScript(1, "SELECT 1; DELETE FROM t;\nGO", "BATCH", "COMMIT", null, false))
                .hasMessageContaining("unscopedMutationConfirmed");
        for (String sql : List.of("SELECT 1\nUPDATE dbo.t SET v='bad'\nGO", "SELECT 1\nDELETE FROM dbo.t\nGO", "SELECT * FROM other WHERE id=1\nDELETE FROM dbo.t\nGO")) {
            assertThatThrownBy(() -> tools.executeScript(1, sql, "BATCH", "COMMIT", null, false)).hasMessageContaining("unscopedMutationConfirmed");
        }
        assertThat(new SqlStatementClassifier().requiresSqlServerBatchConfirmation("SELECT 'DELETE FROM t', [UPDATE] FROM t -- UPDATE t" )).isFalse();
        verifyNoInteractions(files);
    }

    @Test void statusAndCancelAlwaysCheckOwnership() {
        authenticate(McpAccessLevel.FULL);
        when(files.ownedScript(10, "mcp:agent")).thenThrow(new IllegalArgumentException("不属于当前 agent"));
        assertThatThrownBy(() -> tools.status(10)).hasMessageContaining("不属于");
        assertThatThrownBy(() -> tools.cancel(10)).hasMessageContaining("不属于");
        verify(files, never()).cancel(anyLong(), anyString());
    }
    private void execute(String sql, boolean confirmed) throws Exception { tools.executeScript(1, sql, "SCRIPT", "ROLLBACK", null, confirmed); }
    private void authenticate(McpAccessLevel level) {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                new McpAgentPrincipal("agent", Map.of(1L, level), false), null, List.of()));
    }
}
