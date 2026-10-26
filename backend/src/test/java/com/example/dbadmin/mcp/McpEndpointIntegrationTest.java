package com.example.dbadmin.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:mcp-endpoint-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "app.crypto-key-file=target/test-secrets/mcp-endpoint-test.key",
        "app.backup.directory=${java.io.tmpdir}/mydatadev-mcp-endpoint-backups",
        "spring.ai.mcp.server.enabled=true",
        "app.mcp.enabled=true",
        "app.mcp.agents[0].id=integration-agent",
        "app.mcp.agents[0].key-hash=$2y$04$MSNn9Q.hrj2hRiKsuc1Acenmf50KhwoLyzjHKA5erHiDBAp89eav6",
        "app.mcp.agents[0].connection-ids[0]=999"
})
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(McpEndpointIntegrationTest.ProbeConfig.class)
class McpEndpointIntegrationTest {
    private static final String AUTHORIZATION = "Bearer integration-agent.integration-secret";

    @Autowired
    private MockMvc mvc;

    @org.springframework.boot.test.context.TestConfiguration
    static class ProbeConfig {
        @org.springframework.context.annotation.Bean CancellationProbe cancellationProbe() { return new CancellationProbe(); }
        @org.springframework.context.annotation.Bean ScriptProbe scriptProbe() { return new ScriptProbe(); }
    }
    public static class CancellationProbe {
        final java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch cancelled = new java.util.concurrent.CountDownLatch(1);
        @org.springaicommunity.mcp.annotation.McpTool(name = "test_cancellation_probe", description = "Test cancellation context")
        public String run() throws Exception {
            try (var watch = com.example.dbadmin.service.RequestCancellation.onCancel(cancelled::countDown)) {
                entered.countDown();
                return cancelled.await(5, java.util.concurrent.TimeUnit.SECONDS) ? "cancelled" : "missed";
            }
        }
    }
    @Autowired private CancellationProbe probe;
    /** 与 db_execute_script 同形的返回值，失败定位字段全为 null —— 正是脚本成功时的样子。 */
    public static class ScriptProbe {
        @org.springaicommunity.mcp.annotation.McpTool(name = "test_script_probe", description = "Test structured null fields", generateOutputSchema = true)
        public McpDtos.ScriptJobResult run() {
            return new McpDtos.ScriptJobResult(1, 999, "SUCCEEDED", "DONE", 3L, 3, null, "SCRIPT", "COMMIT", "COMMITTED", 1, 0, null, null, null, "",
                    3, null, null, null, java.util.List.of());
        }
    }

    @Test void structuredContentKeepsNullFieldsDeclaredRequiredByTheOutputSchema() throws Exception {
        var initialized = complete(mvc.perform(post("/mcp").header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
                .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                .content(initializeRequest())).andReturn());
        String session = initialized.getResponse().getHeader(McpApiKeyAuthenticationFilter.SESSION_HEADER);
        JsonNode list = rpc(session, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}");
        JsonNode schema = null;
        for (JsonNode tool : list.path("result").path("tools")) {
            if (tool.path("name").asText().equals("test_script_probe")) schema = tool.path("outputSchema");
        }
        assertThat(schema).isNotNull();
        JsonNode call = rpc(session, "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"test_script_probe\",\"arguments\":{}}}");
        // 协议对象本身仍省略 null（不应出现 "error":null 之类的键）。
        assertThat(call.has("error")).isFalse();
        JsonNode structured = call.path("result").path("structuredContent");
        assertThat(structured.path("taskId").asLong()).isEqualTo(1);
        for (JsonNode required : schema.path("required")) {
            assertThat(structured.has(required.asText())).as("structuredContent 缺少必有字段 " + required.asText()).isTrue();
        }
        for (String field : new String[]{"failedStatementIndex", "lastCommitIndex", "failedStartLine", "failedEndLine", "startedAt", "finishedAt", "elapsedMs"}) {
            assertThat(structured.get(field).isNull()).isTrue();
            assertThat(schema.path("properties").path(field).path("type").toString()).contains("\"null\"");
        }
    }

    private JsonNode rpc(String session, String body) throws Exception {
        String out = complete(mvc.perform(post("/mcp").header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
                .header(McpApiKeyAuthenticationFilter.SESSION_HEADER, session)
                .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                .content(body)).andReturn()).getResponse().getContentAsString();
        String json = out.lines().filter(line -> line.startsWith("data:"))
                .map(line -> line.substring(5).trim()).findFirst().orElse(out);
        return new ObjectMapper().readTree(json);
    }

    @Test void cancellationTraversesTheRealSdkToolInvocation() throws Exception {
        var initialized = complete(mvc.perform(post("/mcp").header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
                .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                .content(initializeRequest())).andReturn());
        String session = initialized.getResponse().getHeader(McpApiKeyAuthenticationFilter.SESSION_HEADER);
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var result = executor.submit(() -> complete(mvc.perform(post("/mcp")
                    .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION).header(McpApiKeyAuthenticationFilter.SESSION_HEADER, session)
                    .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                    .content("{\"jsonrpc\":\"2.0\",\"id\":42,\"method\":\"tools/call\",\"params\":{\"name\":\"test_cancellation_probe\",\"arguments\":{}}}")).andReturn()));
            assertThat(probe.entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            mvc.perform(post("/mcp").header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
                    .header(McpApiKeyAuthenticationFilter.SESSION_HEADER, session)
                    .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                    .content("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\",\"params\":{\"requestId\":42}}"))
                    .andExpect(status().isAccepted());
            assertThat(result.get(10, java.util.concurrent.TimeUnit.SECONDS).getResponse().getContentAsString()).contains("cancelled").doesNotContain("missed");
        } finally { executor.shutdownNow(); }
    }

    @Test
    void protectsEndpointAndPublishesReadOnlyToolCatalog() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());

        mvc.perform(post("/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                        .content(initializeRequest()))
                .andExpect(status().isUnauthorized());

        MvcResult initialized = complete(mvc.perform(post("/mcp")
                        .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                        .content(initializeRequest()))
                .andExpect(status().isOk())
                .andReturn());

        String sessionId = initialized.getResponse().getHeader(McpApiKeyAuthenticationFilter.SESSION_HEADER);
        assertThat(sessionId).isNotBlank();
        assertThat(initialized.getResponse().getContentAsString())
                .contains("\"protocolVersion\"")
                .contains("mydatadev-database");

        MvcResult toolList = complete(mvc.perform(post("/mcp")
                        .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
                        .header(McpApiKeyAuthenticationFilter.SESSION_HEADER, sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                        .content("""
                                {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
                                """))
                .andExpect(status().isOk())
                .andReturn());

        assertThat(toolList.getResponse().getContentAsString())
                .contains("db_list_connections")
                .contains("db_list_namespaces")
                .contains("db_search_objects")
                .contains("db_describe_object")
                .contains("db_get_object_ddl")
                .contains("db_browse_table")
                .contains("db_query")
                .contains("db_explain")
                .contains("db_execute_script")
                .contains("db_script_status")
                .contains("db_cancel_script")
                .contains("db_list_script_tasks")
                .contains("db_list_schema_objects")
                .contains("db_get_schema_object")
                .contains("\"readOnlyHint\":true")
                .doesNotContain("db_update")
                .doesNotContain("db_delete");

        // 校验实际发布的 schema，避免工具成功返回后又被客户端因 null 拒绝。
        String catalog = toolList.getResponse().getContentAsString();
        // 结构化结果保留 null 之后，工具目录本身不该因此冒出 "description":null 之类的键。
        assertThat(catalog).doesNotContain(":null");
        String json = catalog.lines().filter(line -> line.startsWith("data:"))
                .map(line -> line.substring(5).trim()).findFirst().orElse(catalog);
        JsonNode tools = new ObjectMapper().readTree(json).path("result").path("tools");
        JsonNode descriptionSchema = null;
        for (JsonNode tool : tools) {
            if (tool.path("name").asText().equals("db_describe_object")) {
                descriptionSchema = tool.path("outputSchema");
            }
            JsonNode output = tool.path("outputSchema").path("properties");
            switch (tool.path("name").asText()) {
                case "db_list_namespaces" -> assertNullableString(output.path("currentNamespace"));
                case "db_search_objects" -> {
                    assertNullableString(output.path("selectedSchema"));
                    assertNullableString(output.path("items").path("items").path("properties").path("schemaName"));
                }
                case "db_browse_table" -> assertNullableString(output.path("nextCursor"));
                case "db_query", "db_explain" -> {
                    assertNullableString(output.path("truncatedReason"));
                    assertNullable(output.path("nextOffset"), "integer");
                }
                case "db_execute" -> assertThat(output.path("compilationErrors").path("type").asText()).isEqualTo("array");
                case "db_list_schema_objects" -> {
                    JsonNode item = output.path("items").path("items").path("properties");
                    for (String field : new String[]{"schemaName", "displayName", "subtype", "status"}) assertNullableString(item.path(field));
                }
                case "db_get_schema_object" -> {
                    assertNullableString(output.path("source"));
                    assertNullableString(output.path("sourceUnavailableReason"));
                    assertNullableString(output.path("sourceVersion"));
                    assertNullable(output.path("sourceTotalChars"), "integer");
                    assertNullable(output.path("nextSourceOffset"), "integer");
                    assertThat(output.path("sourceOffset").path("type").asText()).isEqualTo("integer");
                    var input = tool.path("inputSchema");
                    assertThat(input.path("required")).doesNotContain(
                            com.fasterxml.jackson.databind.node.TextNode.valueOf("sourceOffset"),
                            com.fasterxml.jackson.databind.node.TextNode.valueOf("sourceMaxChars"),
                            com.fasterxml.jackson.databind.node.TextNode.valueOf("expectedSourceVersion"));
                    assertThat(input.path("properties").has("sourceOffset")).isTrue();
                    assertThat(input.path("properties").has("sourceMaxChars")).isTrue();
                    assertThat(input.path("properties").has("expectedSourceVersion")).isTrue();
                    JsonNode parameter = output.path("parameters").path("items").path("properties");
                    for (String field : new String[]{"name", "mode", "typeName"}) assertNullableString(parameter.path(field));
                }
                default -> { }
            }
        }
        assertThat(descriptionSchema).isNotNull();
        JsonNode properties = descriptionSchema.path("properties");
        assertNullableString(properties.path("schemaName"));
        assertNullableString(properties.path("primaryKeyName"));
        JsonNode column = properties.path("columns").path("items").path("properties");
        assertNullableString(column.path("defaultValue"));
        assertNullableString(column.path("remarks"));
        assertThat(column.path("name").path("type").asText()).isEqualTo("string");
        assertThat(column.path("nullable").path("type").asText()).isEqualTo("boolean");
        assertNullableString(properties.path("indexes").path("items").path("properties").path("columnName"));
        for (String direction : new String[]{"importedKeys", "exportedKeys"}) {
            JsonNode items = properties.path(direction).path("items");
            JsonNode relation = (items.has("$ref")
                    ? descriptionSchema.at(items.path("$ref").asText().substring(1)) : items).path("properties");
            for (String field : new String[]{"constraintName", "pkSchemaName", "fkSchemaName"}) {
                assertNullableString(relation.path(field));
            }
        }

        MvcResult toolCall = complete(mvc.perform(post("/mcp")
                        .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
                        .header(McpApiKeyAuthenticationFilter.SESSION_HEADER, sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                        .content("""
                                {
                                  "jsonrpc":"2.0",
                                  "id":3,
                                  "method":"tools/call",
                                  "params":{"name":"db_list_connections","arguments":{}}
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn());

        assertThat(toolCall.getResponse().getContentAsString())
                .contains("\"connections\":[]")
                .contains("\"isError\":false")
                .doesNotContain("MCP 调用身份不可用");
    }

    private static void assertNullable(JsonNode schema, String type) {
        assertThat(schema.path("type").isArray()).as(schema.toString()).isTrue();
        assertThat(schema.path("type")).containsExactlyInAnyOrder(
                com.fasterxml.jackson.databind.node.TextNode.valueOf(type),
                com.fasterxml.jackson.databind.node.TextNode.valueOf("null"));
    }

    private static void assertNullableString(JsonNode schema) {
        assertThat(schema.path("type").isArray()).as(schema.toString()).isTrue();
        assertThat(schema.path("type")).containsExactlyInAnyOrder(
                com.fasterxml.jackson.databind.node.TextNode.valueOf("string"),
                com.fasterxml.jackson.databind.node.TextNode.valueOf("null"));
    }

    private MvcResult complete(MvcResult result) throws Exception {
        if (!result.getRequest().isAsyncStarted()) return result;
        return mvc.perform(asyncDispatch(result)).andReturn();
    }

    private String initializeRequest() {
        return """
                {
                  "jsonrpc": "2.0",
                  "id": 1,
                  "method": "initialize",
                  "params": {
                    "protocolVersion": "2025-11-25",
                    "capabilities": {},
                    "clientInfo": {"name": "integration-test", "version": "1.0"}
                  }
                }
                """;
    }
}
