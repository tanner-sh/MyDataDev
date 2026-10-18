package com.example.dbadmin.mcp;

import com.example.dbadmin.dto.ApiDtos.CompilationError;
import com.example.dbadmin.dto.ApiDtos.SchemaObjectDetail;
import com.example.dbadmin.dto.ApiDtos.SchemaObjectPage;
import com.example.dbadmin.dto.ApiDtos.SchemaObjectParameter;
import com.example.dbadmin.dto.ApiDtos.SchemaObjectSummary;
import com.example.dbadmin.model.DbConnection;
import com.example.dbadmin.repo.AuditRepository;
import com.example.dbadmin.service.ConnectionService;
import com.example.dbadmin.service.SchemaObjectService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

class McpSchemaObjectToolsTest {
    private final ConnectionService connections = mock(ConnectionService.class);
    private final SchemaObjectService objects = mock(SchemaObjectService.class);
    private McpSchemaObjectTools tools;

    @BeforeEach
    void setup() {
        when(connections.require(1)).thenReturn(new DbConnection(1L, "target", "oracle", "jdbc:test", "sa", "", "dev", false, Instant.now(), Instant.now()));
        var config = mock(McpConfigurationService.class);
        when(config.snapshot()).thenReturn(new McpRuntimeConfig(new McpRuntimeConfig.Settings(true, 100, 1000, 200000, 20, 10, 100000, 30, 50, 200, 50, 200, 60, 1, 60), Set.of(), Map.of()));
        tools = new McpSchemaObjectTools(new McpAccessService(connections), objects, config, mock(AuditRepository.class));
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                new McpAgentPrincipal("agent", Map.of(1L, McpAccessLevel.READ_ONLY), false), null, List.of()));
    }

    @AfterEach
    void clear() { SecurityContextHolder.clearContext(); }

    @Test
    void listsObjectsWithStatusAndCanNarrowToInvalidOnes() throws Exception {
        when(objects.list(1L, "ETL", "PROCEDURE", null, 0, 50, false)).thenReturn(new SchemaObjectPage(
                List.of(summary("SP_OK", "VALID"), summary("SP_BAD", "INVALID")), 2, true, 0, 50, false, "now", false));
        assertThat(tools.list(1L, "PROCEDURE", "ETL", null, null, null, null).items())
                .extracting(McpDtos.SchemaObjectView::name).containsExactly("SP_OK", "SP_BAD");
        assertThat(tools.list(1L, "PROCEDURE", "ETL", null, "invalid", null, null).items())
                .extracting(McpDtos.SchemaObjectView::status).containsExactly("INVALID");
        assertThatThrownBy(() -> tools.list(2L, "PROCEDURE", null, null, null, null, null)).hasMessageStartingWith("[INVALID_REQUEST]");
    }

    @Test
    void returnsCompilationErrorsOnlyForInvalidObjectsAndCapsTheSource() throws Exception {
        var bad = summary("SP_BAD", "INVALID");
        when(objects.detail(1L, "key-SP_BAD", false)).thenReturn(detail(bad, "CREATE OR REPLACE PROCEDURE SP_BAD AS BEGIN X; END;"));
        when(objects.compilationErrors(1L, bad)).thenReturn(List.of(new CompilationError(1, 40, "PLS-00201: identifier 'X' must be declared")));
        var view = tools.get(1L, "key-SP_BAD");
        assertThat(view.compilationErrors()).containsExactly("ETL.SP_BAD（PROCEDURE） 第 1 行第 40 列：PLS-00201: identifier 'X' must be declared");
        // 配置里的结果文本上限是 20 字符。
        assertThat(view.source()).hasSize(20);
        assertThat(view.sourceTruncated()).isTrue();
        assertThat(view.parameters()).extracting(McpDtos.SchemaObjectParameterView::name).containsExactly("P_DATE");

        var ok = summary("SP_OK", "VALID");
        when(objects.detail(1L, "key-SP_OK", false)).thenReturn(detail(ok, "short"));
        assertThat(tools.get(1L, "key-SP_OK").compilationErrors()).isEmpty();
        verify(objects, never()).compilationErrors(anyLong(), org.mockito.ArgumentMatchers.eq(ok));
    }

    private static SchemaObjectSummary summary(String name, String status) {
        return new SchemaObjectSummary("key-" + name, "ETL", name, name, "PROCEDURE", "", status);
    }

    private static SchemaObjectDetail detail(SchemaObjectSummary object, String source) {
        return new SchemaObjectDetail(object, source, true, null,
                List.of(new SchemaObjectParameter(1, "P_DATE", "IN", "DATE", 91, true)),
                List.of(), true, null, "v1", List.of(), Map.of());
    }
}
