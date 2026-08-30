package com.example.dbadmin.mcp;

import com.example.dbadmin.api.ApiProblemException;
import com.example.dbadmin.config.AppProperties;
import com.example.dbadmin.core.DialectRegistry;
import com.example.dbadmin.model.DbConnection;
import com.example.dbadmin.repo.AuditRepository;
import com.example.dbadmin.repo.SqlHistoryRepository;
import com.example.dbadmin.service.ConnectionService;
import com.example.dbadmin.service.DataEditService;
import com.example.dbadmin.service.ExecutionGuard;
import com.example.dbadmin.service.MetadataService;
import com.example.dbadmin.service.SqlExecutionRegistry;
import com.example.dbadmin.service.SqlScriptSplitter;
import com.example.dbadmin.service.SqlService;
import com.example.dbadmin.service.SqlStatementClassifier;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * db_execute 的授权与安全约束。
 *
 * <p>分档的意义全在这里：档位决定「允许尝试什么」，而只读连接、生产确认、未限定范围写确认
 * 这三道闸门无论档位多高都必须照旧生效 —— 它们复用界面那条执行路径，不是 MCP 的第二套实现。</p>
 */
class McpDatabaseToolsWriteTest {
    private String url;
    private JdbcTemplate target;
    private ConnectionService connections;
    private McpDatabaseTools tools;

    @BeforeEach
    void setUp() throws Exception {
        url = "jdbc:h2:mem:mcp-write-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        target = new JdbcTemplate(new DriverManagerDataSource(url, "sa", ""));
        target.execute("CREATE TABLE accounts(id INT PRIMARY KEY, balance INT)");
        target.update("INSERT INTO accounts VALUES (1, 100)");

        connections = mock(ConnectionService.class);
        when(connections.open(anyLong())).thenAnswer(ignored -> DriverManager.getConnection(url, "sa", ""));
        when(connections.open(anyLong(), any())).thenAnswer(ignored -> DriverManager.getConnection(url, "sa", ""));

        AuditRepository audit = mock(AuditRepository.class);
        MetadataService metadata = mock(MetadataService.class);
        SqlService sql = new SqlService(
                connections, new AppProperties(), audit, new DialectRegistry(),
                mock(SqlHistoryRepository.class), metadata, new SqlScriptSplitter(), new SqlStatementClassifier(),
                new ExecutionGuard(), new SqlExecutionRegistry(), mock(DataEditService.class)
        );
        McpConfigurationService configuration = mock(McpConfigurationService.class);
        when(configuration.snapshot()).thenReturn(new McpRuntimeConfig(settings(), java.util.Set.of(), Map.of()));
        tools = new McpDatabaseTools(
                new McpAccessService(connections), metadata, mock(DataEditService.class), sql,
                new SqlStatementClassifier(), audit, new SimpleMeterRegistry(), configuration
        );
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void readOnlyAgentCannotWriteAtAll() throws Exception {
        connection(1L, "dev", "dev", false);
        authenticate(Map.of(1L, McpAccessLevel.READ_ONLY));

        assertThatThrownBy(() -> tools.execute(1L, null, "UPDATE accounts SET balance = 0 WHERE id = 1", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("数据读写");
        assertThat(balance()).isEqualTo(100);
    }

    @Test
    void dataWriteAgentCanModifyRowsButNotSchema() throws Exception {
        connection(1L, "dev", "dev", false);
        authenticate(Map.of(1L, McpAccessLevel.DATA_WRITE));

        var result = tools.execute(1L, null, "UPDATE accounts SET balance = 50 WHERE id = 1", null, null);

        assertThat(result.statementKind()).isEqualTo("MUTATION");
        assertThat(result.updatedRows()).isEqualTo(1);
        assertThat(balance()).isEqualTo(50);
        assertThatThrownBy(() -> tools.execute(1L, null, "CREATE TABLE extra(id INT)", null, null))
                .hasMessageContaining("完全");
    }

    @Test
    void fullAgentCanRunDdl() throws Exception {
        connection(1L, "dev", "dev", false);
        authenticate(Map.of(1L, McpAccessLevel.FULL));

        assertThat(tools.execute(1L, null, "CREATE TABLE extra(id INT)", null, null).statementKind()).isEqualTo("DDL");
        assertThat(target.queryForObject("SELECT COUNT(*) FROM extra", Integer.class)).isZero();
    }

    @Test
    void readOnlyConnectionRejectsWritesEvenAtFullLevel() {
        connection(1L, "locked", "dev", true);
        authenticate(Map.of(1L, McpAccessLevel.FULL));

        assertThatThrownBy(() -> tools.execute(1L, null, "UPDATE accounts SET balance = 0 WHERE id = 1", null, null))
                .isInstanceOf(ApiProblemException.class)
                .extracting(error -> ((ApiProblemException) error).code())
                .isEqualTo("READONLY_CONNECTION");
        assertThat(balance()).isEqualTo(100);
    }

    @Test
    void productionWritesStillRequireTheConnectionNameConfirmation() {
        connection(1L, "orders-prod", "prod", false);
        authenticate(Map.of(1L, McpAccessLevel.FULL), true);

        assertThatThrownBy(() -> tools.execute(1L, null, "UPDATE accounts SET balance = 0 WHERE id = 1", null, null))
                .isInstanceOf(ApiProblemException.class)
                .extracting(error -> ((ApiProblemException) error).code())
                .isEqualTo("PRODUCTION_CONFIRMATION_REQUIRED");
        assertThat(balance()).isEqualTo(100);
    }

    @Test
    void unscopedUpdateStillRequiresItsOwnConfirmation() throws Exception {
        connection(1L, "dev", "dev", false);
        authenticate(Map.of(1L, McpAccessLevel.DATA_WRITE));

        assertThatThrownBy(() -> tools.execute(1L, null, "UPDATE accounts SET balance = 0", null, null))
                .isInstanceOf(ApiProblemException.class)
                .extracting(error -> ((ApiProblemException) error).code())
                .isEqualTo("UNSCOPED_MUTATION_CONFIRMATION_REQUIRED");
        assertThat(balance()).isEqualTo(100);

        tools.execute(1L, null, "UPDATE accounts SET balance = 7", null, true);
        assertThat(balance()).isEqualTo(7);
    }

    @Test
    void queriesAreRefusedHereSoTheyKeepTheReadOnlyScope() {
        connection(1L, "dev", "dev", false);
        authenticate(Map.of(1L, McpAccessLevel.FULL));

        assertThatThrownBy(() -> tools.execute(1L, null, "SELECT * FROM accounts", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("db_query");
    }

    private void connection(long id, String name, String environment, boolean readonly) {
        DbConnection model = new DbConnection(id, name, "h2", url, "sa", "", environment, readonly, Instant.now(), Instant.now());
        when(connections.require(id)).thenReturn(model);
        when(connections.list()).thenReturn(List.of());
    }

    private void authenticate(Map<Long, McpAccessLevel> levels) {
        authenticate(levels, false);
    }

    private void authenticate(Map<Long, McpAccessLevel> levels, boolean allowProduction) {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                new McpAgentPrincipal("agent", levels, allowProduction), null, List.of()));
    }

    private int balance() {
        Integer value = target.queryForObject("SELECT balance FROM accounts WHERE id = 1", Integer.class);
        return value == null ? -1 : value;
    }

    private McpRuntimeConfig.Settings settings() {
        return new McpRuntimeConfig.Settings(true, 100, 1_000, 200_000, 1_000_000, 10_000, 100_000, 30, 50, 200, 50, 200, 60);
    }
}
