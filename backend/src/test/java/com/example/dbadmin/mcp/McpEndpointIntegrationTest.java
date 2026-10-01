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
class McpEndpointIntegrationTest {
    private static final String AUTHORIZATION = "Bearer integration-agent.integration-secret";

    @Autowired
    private MockMvc mvc;

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
                .contains("\"readOnlyHint\":true")
                .doesNotContain("db_update")
                .doesNotContain("db_delete");

        // 校验实际发布的 schema，避免工具成功返回后又被客户端因 null 拒绝。
        String catalog = toolList.getResponse().getContentAsString();
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
                case "db_query", "db_explain" -> assertNullableString(output.path("truncatedReason"));
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
