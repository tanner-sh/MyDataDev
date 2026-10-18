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
        when(config.snapshot()).thenReturn(new McpRuntimeConfig(new McpRuntimeConfig.Settings(true, 100, 1000, 200000, 1000000, 10000, 100000, 30, 50, 200, 50, 200, 60, 1, 60), Set.of(), Map.of()));
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

    @Test void recoveryListChecksAuthorizationAndUsesOnlyTheCurrentActor() {
        authenticate(McpAccessLevel.READ_ONLY);
        var page = new com.example.dbadmin.dto.ApiDtos.SqlFileExecutionPage(List.of(), 0, 20, false);
        when(files.ownedScripts(1, "mcp:agent", true, 0, 20)).thenReturn(page);
        assertThat(tools.list(1, null, null, null).items()).isEmpty();
        verify(files).ownedScripts(1, "mcp:agent", true, 0, 20);
        assertThatThrownBy(() -> tools.list(2, false, 0, 20)).hasMessageContaining("未获授权");
        verify(files, never()).ownedScripts(eq(2L), anyString(), anyBoolean(), anyInt(), anyInt());
    }

    @Test void statusAndCancelAlwaysCheckOwnership() {
        authenticate(McpAccessLevel.FULL);
        when(files.ownedScript(10, "mcp:agent")).thenThrow(new IllegalArgumentException("不属于当前 agent"));
        assertThatThrownBy(() -> tools.status(10, null)).hasMessageContaining("不属于");
        assertThatThrownBy(() -> tools.cancel(10)).hasMessageContaining("不属于");
        verify(files, never()).cancel(anyLong(), anyString());
    }
    @Test void concurrencyRejectionNamesTheAgentsOwnRunningTasks() throws Exception {
        authenticate(McpAccessLevel.FULL);
        var busy = new com.example.dbadmin.api.ApiProblemException(org.springframework.http.HttpStatus.CONFLICT,
                "SQL_FILE_ALREADY_RUNNING", "该连接的脚本任务已达到并发上限（1），请等待完成后重试。");
        when(files.submitScript(eq(1L), anyString(), eq("mcp:agent"), any(), anyString(), anyString())).thenThrow(busy);
        var running = job(42, "RUNNING", 3, 6L);
        var ready = job(43, "READY", 0, null);
        when(files.ownedScripts(1, "mcp:agent", true, 0, 20))
                .thenReturn(new com.example.dbadmin.dto.ApiDtos.SqlFileExecutionPage(List.of(running, ready), 0, 20, false));
        assertThatThrownBy(() -> tools.executeScript(1, "INSERT INTO t VALUES(1);", "BATCH", "COMMIT", null, false))
                .isInstanceOf(McpToolErrors.McpToolException.class)
                .hasMessageStartingWith("[SQL_FILE_ALREADY_RUNNING]")
                .hasMessageContaining("并发上限").hasMessageContaining("taskId=42（RUNNING，已执行 3/6）").hasMessageContaining("没有执行");

        when(files.ownedScripts(1, "mcp:agent", true, 0, 20))
                .thenReturn(new com.example.dbadmin.dto.ApiDtos.SqlFileExecutionPage(List.of(), 0, 20, false));
        assertThatThrownBy(() -> tools.executeScript(1, "INSERT INTO t VALUES(1);", "BATCH", "COMMIT", null, false))
                .hasMessageContaining("不是本 agent 的脚本任务");
    }
    @Test void statusWaitsOnTheServerUntilTheTaskFinishes() throws Exception {
        authenticate(McpAccessLevel.READ_ONLY);
        Instant started = Instant.parse("2026-10-18T01:00:00Z");
        var finished = new com.example.dbadmin.dto.ApiDtos.SqlFileExecutionResponse(42, 1, "target", "oracle", "mcp-script.sql", 10, "abc", "UTF-8",
                "SUCCESS", "COMPLETED", 10, 6L, 6, 0, 1, 0, 0, 6, 0, null, null, "完成", false, false, false,
                Instant.now(), started, started.plusMillis(1500), started, com.example.dbadmin.model.SqlFileTransaction.defaults(),
                List.of("ETL.SP_X（PROCEDURE） 第 3 行第 5 列：PLS-00201"));
        when(files.ownedScript(42, "mcp:agent")).thenReturn(job(42, "RUNNING", 3, 6L), job(42, "RUNNING", 4, 6L), finished);
        var result = tools.status(42, 5);
        assertThat(result.status()).isEqualTo("SUCCESS");
        assertThat(result.elapsedMs()).isEqualTo(1500);
        assertThat(result.startedAt()).isEqualTo("2026-10-18T01:00:00Z");
        assertThat(result.compilationErrors()).containsExactly("ETL.SP_X（PROCEDURE） 第 3 行第 5 列：PLS-00201");
        verify(files, times(3)).ownedScript(42, "mcp:agent");

        // READY 不会自己开始，等它没有意义；不传 waitSeconds 时保持原来的立即返回。
        when(files.ownedScript(43, "mcp:agent")).thenReturn(job(43, "READY", 0, null));
        long before = System.nanoTime();
        assertThat(tools.status(43, 30).status()).isEqualTo("READY");
        assertThat(tools.status(43, null).status()).isEqualTo("READY");
        assertThat(java.time.Duration.ofNanos(System.nanoTime() - before)).isLessThan(java.time.Duration.ofSeconds(1));

        assertThatThrownBy(() -> tools.status(42, 61)).hasMessageStartingWith("[INVALID_REQUEST]").hasMessageContaining("waitSeconds");
    }
    private static com.example.dbadmin.dto.ApiDtos.SqlFileExecutionResponse job(long id, String status, long success, Long total) {
        return new com.example.dbadmin.dto.ApiDtos.SqlFileExecutionResponse(id, 1, "target", "oracle", "mcp-script.sql", 10, "abc", "UTF-8",
                status, "EXECUTING", 10, total, success, 0, 1, 0, 0, success, 0, null, null, "", false, false, false,
                Instant.now(), null, null, Instant.now(), com.example.dbadmin.model.SqlFileTransaction.defaults(), List.of());
    }
    private void execute(String sql, boolean confirmed) throws Exception { tools.executeScript(1, sql, "SCRIPT", "ROLLBACK", null, confirmed); }
    private void authenticate(McpAccessLevel level) {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                new McpAgentPrincipal("agent", Map.of(1L, level), false), null, List.of()));
    }
}
