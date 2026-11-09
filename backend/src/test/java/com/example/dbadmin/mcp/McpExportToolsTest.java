package com.example.dbadmin.mcp;

import com.example.dbadmin.config.AppProperties;
import com.example.dbadmin.core.DialectRegistry;
import com.example.dbadmin.model.DbConnection;
import com.example.dbadmin.repo.AuditRepository;
import com.example.dbadmin.repo.SqlHistoryRepository;
import com.example.dbadmin.service.ConnectionService;
import com.example.dbadmin.service.ExecutionGuard;
import com.example.dbadmin.service.ExportService;
import com.example.dbadmin.service.SqlScriptSplitter;
import com.example.dbadmin.service.SqlStatementClassifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.ByteArrayInputStream;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpExportToolsTest {
    private final AuditRepository audit = mock(AuditRepository.class);
    private McpExportTools tools;
    private McpConfigurationService config;
    private ExportService exports;
    private ConnectionService connections;

    @BeforeEach
    void setUp() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        try (var connection = DriverManager.getConnection(url, "sa", "")) {
            connection.createStatement().execute("CREATE TABLE orders(id BIGINT PRIMARY KEY, buyer VARCHAR(40), note VARCHAR(40))");
            connection.createStatement().execute("INSERT INTO orders VALUES (1234567890123456789, '张三', NULL), (2, 'O''Brien', '')");
        }
        connections = mock(ConnectionService.class);
        when(connections.require(anyLong())).thenReturn(new DbConnection(1L, "h2", "h2", url, "sa", "", "dev", false, Instant.now(), Instant.now()));
        when(connections.open(anyLong())).thenAnswer(invocation -> DriverManager.getConnection(url, "sa", ""));
        when(connections.open(anyLong(), anyString())).thenAnswer(invocation -> DriverManager.getConnection(url, "sa", ""));
        AppProperties properties = new AppProperties();
        properties.getSql().setTimeoutSeconds(10);
        exports = new ExportService(connections, new DialectRegistry(), properties, new ObjectMapper(),
                new SqlStatementClassifier(), new SqlScriptSplitter(), mock(AuditRepository.class), mock(SqlHistoryRepository.class), new ExecutionGuard());
        config = mock(McpConfigurationService.class);
        useExportLimit(McpRuntimeConfig.Settings.DEFAULT_EXPORT_MAX_KILOBYTES);
        tools = new McpExportTools(new McpAccessService(connections), config, exports, audit, new SimpleMeterRegistry());
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                new McpAgentPrincipal("agent", Map.of(1L, McpAccessLevel.READ_ONLY), false), null, List.of()));
    }

    private void useExportLimit(int kilobytes) {
        when(config.snapshot()).thenReturn(new McpRuntimeConfig(new McpRuntimeConfig.Settings(
                true, 100, 1000, 200000, 1000000, 10000, 100000, 30, 50, 200, 50, 200, 60, 1, 60, kilobytes), Set.of(), Map.of()));
    }

    @Test
    void refusesFilesOverTheConfiguredLimitInsteadOfReturningHalfOfOne() throws Exception {
        try (var connection = DriverManager.getConnection(connections.require(1).jdbcUrl(), "sa", "")) {
            connection.createStatement().execute("INSERT INTO orders SELECT \"X\" + 10, REPEAT('x', 30), NULL FROM SYSTEM_RANGE(1, 100)");
        }
        useExportLimit(1);
        assertThatThrownBy(() -> tools.exportQuery(1, null, "select * from orders", "csv", null, null))
                .hasMessageStartingWith("[INVALID_REQUEST]").hasMessageContaining("1 KB").hasMessageContaining("maxRows");
        useExportLimit(64);
        assertThat(tools.exportQuery(1, null, "select * from orders", "csv", null, null).sizeBytes()).isGreaterThan(1024);
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void returnsTextFormatsInlineAndAuditsTheCall() throws Exception {
        var result = tools.exportQuery(1, null, "select id, buyer, note from orders order by id", "CSV", null, null);

        assertThat(result.format()).isEqualTo("csv");
        assertThat(result.encoding()).isEqualTo("utf-8");
        assertThat(result.mimeType()).isEqualTo("text/csv");
        assertThat(result.truncated()).isFalse();
        assertThat(result.maxRows()).isEqualTo(ExportService.EXPORT_MAX_ROWS);
        assertThat(result.content()).contains("\"张三\"").contains("\"O'Brien\"").contains("1234567890123456789");
        assertThat(result.sizeBytes()).isEqualTo(result.content().getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        verify(audit).onConnection(eq("mcp:agent"), eq("MCP_TOOL_CALL"), eq(1L), contains("tool=db_export_query;status=success"));
    }

    @Test
    void generatesInsertsForTheRequestedTarget() throws Exception {
        var result = tools.exportQuery(1, null, "select id, buyer from orders where id = 2", "sql", 5, "archive.orders_copy");

        assertThat(result.content()).contains("INSERT INTO").contains("orders_copy").contains("'O''Brien'");
        assertThat(result.maxRows()).isEqualTo(5);
    }

    @Test
    void returnsExcelAsBase64Workbook() throws Exception {
        var result = tools.exportQuery(1, null, "select id, buyer from orders", "xlsx", null, null);

        assertThat(result.encoding()).isEqualTo("base64");
        byte[] bytes = Base64.getDecoder().decode(result.content());
        assertThat(bytes.length).isEqualTo(result.sizeBytes());
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            assertThat(zip.getNextEntry()).isNotNull();
        }
    }

    @Test
    void rejectsWritesAndUnknownFormatsWithCodedErrors() {
        assertThatThrownBy(() -> tools.exportQuery(1, null, "delete from orders", "csv", null, null))
                .hasMessageStartingWith("[INVALID_REQUEST]").hasMessageContaining("单条查询");
        assertThatThrownBy(() -> tools.exportQuery(1, null, "select 1", "pdf", null, null))
                .hasMessageStartingWith("[INVALID_REQUEST]").hasMessageContaining("format");
        verify(audit, org.mockito.Mockito.times(2)).onConnection(eq("mcp:agent"), eq("MCP_TOOL_CALL"), eq(1L), contains("status=error"));
    }

    @Test
    void refusesConnectionsOutsideTheAgentsWhitelist() {
        assertThatThrownBy(() -> tools.exportQuery(9, null, "select 1", "csv", null, null)).hasMessageStartingWith("[");
    }

    @Test
    void parsesQualifiedTargetNames() {
        assertThat(McpExportTools.targetParts("S.T")).containsExactly("S", "T");
        assertThat(McpExportTools.targetParts("\"a.b\".T")).containsExactly("a.b", "T");
        assertThat(McpExportTools.targetParts(" ")).isNull();
        assertThatThrownBy(() -> McpExportTools.targetParts("S..T")).isInstanceOf(IllegalArgumentException.class);
        assertThat(McpExportTools.normalizeFormat("md")).isEqualTo("markdown");
        assertThat(McpExportTools.normalizeRows(50_000)).isEqualTo(ExportService.EXPORT_MAX_ROWS);
    }
}
