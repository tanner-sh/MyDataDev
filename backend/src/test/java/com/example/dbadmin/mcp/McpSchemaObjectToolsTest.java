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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
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
    private final McpConfigurationService config = mock(McpConfigurationService.class);
    private final AuditRepository audit = mock(AuditRepository.class);
    private McpSchemaObjectTools tools;

    @BeforeEach
    void setup() {
        when(connections.require(1)).thenReturn(new DbConnection(1L, "target", "oracle", "jdbc:test", "sa", "", "dev", false, Instant.now(), Instant.now()));
        textLimits(20, 10);
        tools = new McpSchemaObjectTools(new McpAccessService(connections), objects, config, audit);
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
        when(objects.detail(1L, "key-SP_BAD", true)).thenReturn(detail(bad, "CREATE OR REPLACE PROCEDURE SP_BAD AS BEGIN X; END;"));
        when(objects.compilationErrors(1L, bad)).thenReturn(List.of(new CompilationError(1, 40, "PLS-00201: identifier 'X' must be declared")));
        var view = tools.get(1L, "key-SP_BAD", null, null, null);
        assertThat(view.compilationErrors()).containsExactly("ETL.SP_BAD（PROCEDURE） 第 1 行第 40 列：PLS-00201: identifier 'X' must be declared");
        // 结果文本总量为 20，单元文本上限为 10，分片同时受两者约束。
        assertThat(view.source()).hasSize(10);
        assertThat(view.sourceTruncated()).isTrue();
        assertThat(view.nextSourceOffset()).isEqualTo(10);
        assertThat(view.parameters()).extracting(McpDtos.SchemaObjectParameterView::name).containsExactly("P_DATE");

        var ok = summary("SP_OK", "VALID");
        when(objects.detail(1L, "key-SP_OK", true)).thenReturn(detail(ok, "short"));
        var complete = tools.get(1L, "key-SP_OK", null, null, null);
        assertThat(complete.compilationErrors()).isEmpty();
        assertThat(complete.source()).isEqualTo("short");
        assertThat(complete.sourceTotalChars()).isEqualTo(5);
        assertThat(complete.nextSourceOffset()).isNull();
        assertThat(complete.sourceTruncated()).isFalse();
        verify(objects, never()).compilationErrors(anyLong(), org.mockito.ArgumentMatchers.eq(ok));
    }

    @Test
    void reconstructsALongSingleRowProcedureWithoutDroppingItsTail() throws Exception {
        textLimits(1_000_000, 20_000);
        String source = "CREATE OR REPLACE PROCEDURE SP_LONG AS BEGIN " + "NULL; /* 正文 */ ".repeat(10_000) + "END SP_LONG;";
        when(objects.detail(1L, "key-SP_LONG", true)).thenReturn(detail(summary("SP_LONG", "VALID"), source));
        var chunk = tools.get(1L, "key-SP_LONG", null, null, null);
        assertThat(chunk.source()).hasSize(8_000);
        String version = chunk.sourceVersion();
        assertThat(version).isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8))));
        var assembled = new StringBuilder();
        int calls = 0;
        while (true) {
            assertThat(chunk.sourceOffset()).isEqualTo(assembled.length());
            assertThat(chunk.sourceTotalChars()).isEqualTo(source.length());
            assertThat(chunk.sourceVersion()).isEqualTo(version);
            assembled.append(chunk.source());
            calls++;
            if (chunk.nextSourceOffset() == null) break;
            assertThat(chunk.sourceTruncated()).isTrue();
            assertThat(chunk.nextSourceOffset()).isEqualTo(assembled.length());
            assertThat(calls).isLessThan(100);
            chunk = tools.get(1L, "key-SP_LONG", chunk.nextSourceOffset(), null, version);
        }
        assertThat(chunk.sourceTruncated()).isFalse();
        assertThat(assembled.toString()).isEqualTo(source).endsWith("END SP_LONG;");
        verify(audit, times(calls)).onConnection("mcp:agent", "MCP_TOOL_CALL", 1L, "tool=db_get_schema_object;status=success");
    }

    @Test
    void honorsRequestedChunkSizeAndLimitsWithoutOffsetOverflow() throws Exception {
        when(objects.detail(1L, "key-SP_OK", true)).thenReturn(detail(summary("SP_OK", "VALID"), "01234567890123456789"));
        assertThat(tools.get(1L, "key-SP_OK", 0, 4, null).source()).isEqualTo("0123");
        var capped = tools.get(1L, "key-SP_OK", 15, Integer.MAX_VALUE, null);
        assertThat(capped.source()).isEqualTo("56789");
        assertThat(capped.nextSourceOffset()).isNull();
        textLimits(6, 6);
        assertThat(tools.get(1L, "key-SP_OK", 0, Integer.MAX_VALUE, null).source()).isEqualTo("012345");
        var end = tools.get(1L, "key-SP_OK", 20, null, null);
        assertThat(end.source()).isEmpty();
        assertThat(end.nextSourceOffset()).isNull();
        assertThat(end.sourceTruncated()).isFalse();
    }

    @Test
    void preservesUnicodeCharactersAcrossJsonChunks() throws Exception {
        String source = "123456789😀中文\r\nEND;";
        when(objects.detail(1L, "key-SP_OK", true)).thenReturn(detail(summary("SP_OK", "VALID"), source));
        var first = tools.get(1L, "key-SP_OK", 0, null, null);
        assertThat(first.source()).isEqualTo("123456789");
        assertThat(first.nextSourceOffset()).isEqualTo(9);
        var last = tools.get(1L, "key-SP_OK", first.nextSourceOffset(), null, first.sourceVersion());
        assertThat(first.source() + last.source()).isEqualTo(source);
        assertThat(last.nextSourceOffset()).isNull();
        assertThatThrownBy(() -> tools.get(1L, "key-SP_OK", 10, null, null)).hasMessageContaining("Unicode");
        assertThatThrownBy(() -> tools.get(1L, "key-SP_OK", 9, 1, null)).hasMessageContaining("上限不足");
    }

    @Test
    void rejectsInvalidOffsetsSizesAndUnauthorizedConnections() throws Exception {
        when(objects.detail(1L, "key-SP_OK", true)).thenReturn(detail(summary("SP_OK", "VALID"), "short"));
        assertThatThrownBy(() -> tools.get(1L, "key-SP_OK", -1, null, null)).hasMessageStartingWith("[INVALID_REQUEST]");
        assertThatThrownBy(() -> tools.get(1L, "key-SP_OK", 0, 0, null)).hasMessageStartingWith("[INVALID_REQUEST]");
        assertThatThrownBy(() -> tools.get(1L, "key-SP_OK", 0, -1, null)).hasMessageStartingWith("[INVALID_REQUEST]");
        assertThatThrownBy(() -> tools.get(1L, "key-SP_OK", Integer.MAX_VALUE, null, null)).hasMessageContaining("超过源码长度");
        assertThatThrownBy(() -> tools.get(2L, "key-SP_OK", 0, null, null)).hasMessageStartingWith("[INVALID_REQUEST]");
        verify(objects, never()).detail(eq(2L), anyString(), anyBoolean());
        verify(audit, times(4)).onConnection("mcp:agent", "MCP_TOOL_CALL", 1L, "tool=db_get_schema_object;status=error");
    }

    @Test
    void rejectsSourceChangesBetweenChunksEvenIfLengthIsUnchanged() throws Exception {
        when(objects.detail(1L, "key-SP_OK", true)).thenReturn(detail(summary("SP_OK", "VALID"), "first revision"));
        var first = tools.get(1L, "key-SP_OK", null, null, null);
        when(objects.detail(1L, "key-SP_OK", true)).thenReturn(detail(summary("SP_OK", "VALID"), "other revision"));
        assertThatThrownBy(() -> tools.get(1L, "key-SP_OK", first.nextSourceOffset(), null, first.sourceVersion()))
                .hasMessageStartingWith("[INVALID_REQUEST]").hasMessageContaining("源码已变化");
    }

    @Test
    void unavailableSourceHasNoLengthVersionOrContinuation() throws Exception {
        when(objects.detail(1L, "key-SP_OK", true)).thenReturn(new SchemaObjectDetail(summary("SP_OK", "VALID"), null, false,
                "没有源码权限", List.of(), List.of(), true, null, "v1", List.of(), Map.of()));
        var view = tools.get(1L, "key-SP_OK", null, null, null);
        assertThat(view.sourceAvailable()).isFalse();
        assertThat(view.sourceUnavailableReason()).isEqualTo("没有源码权限");
        assertThat(view.source()).isNull();
        assertThat(view.sourceTotalChars()).isNull();
        assertThat(view.sourceVersion()).isNull();
        assertThat(view.sourceTruncated()).isFalse();
        assertThat(view.nextSourceOffset()).isNull();
    }

    private void textLimits(long resultChars, int cellChars) {
        when(config.snapshot()).thenReturn(new McpRuntimeConfig(new McpRuntimeConfig.Settings(true, 100, 1000, 200000,
                resultChars, cellChars, 100000, 30, 50, 200, 50, 200, 60, 1, 60), Set.of(), Map.of()));
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
