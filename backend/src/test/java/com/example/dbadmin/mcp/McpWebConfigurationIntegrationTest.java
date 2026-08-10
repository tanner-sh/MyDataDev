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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:mcp-web-config-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "app.crypto-key=mcp-web-config-test-crypto-key",
        "app.backup.directory=${java.io.tmpdir}/mydatadev-mcp-web-config-backups"
})
@AutoConfigureMockMvc
class McpWebConfigurationIntegrationTest {
    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Test
    void defaultsToEnabledAndQueriesWritableAllowlistedConnectionWithoutRestart() throws Exception {
        mvc.perform(get("/api/mcp/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.endpointPath").value("/mcp"))
                .andExpect(jsonPath("$.agents").isEmpty());

        initialize("missing.invalid").andExpect(status().isUnauthorized());

        mvc.perform(put("/api/mcp/config")
                        .header("X-User", "integration-test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "allowedOrigins":["https://Agent.Example/"],
                                  "limits":{
                                    "defaultQueryRows":100,
                                    "maxQueryRows":400,
                                    "maxResultCells":20000,
                                    "maxResultTextChars":1000000,
                                    "maxCellTextChars":20000,
                                    "maxSqlChars":200000,
                                    "queryTimeoutSeconds":30,
                                    "metadataPageSize":50,
                                    "maxMetadataPageSize":200,
                                    "tablePageSize":50,
                                    "maxTablePageSize":100,
                                    "sessionTtlMinutes":120
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowedOrigins[0]").value("https://agent.example"))
                .andExpect(jsonPath("$.limits.maxQueryRows").value(400));

        mvc.perform(put("/api/mcp/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));

        mvc.perform(put("/api/mcp/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));

        String connectionBody = mvc.perform(post("/api/connections")
                        .header("X-User", "integration-test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name":"MCP writable",
                                  "dbType":"h2",
                                  "jdbcUrl":"jdbc:h2:mem:mcp-web-target;DB_CLOSE_DELAY=-1",
                                  "username":"sa",
                                  "password":"",
                                  "environment":"test",
                                  "readonly":false
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long connectionId = json.readTree(connectionBody).path("id").asLong();

        String productionBody = mvc.perform(post("/api/connections")
                        .header("X-User", "integration-test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name":"MCP production",
                                  "dbType":"h2",
                                  "jdbcUrl":"jdbc:h2:mem:mcp-web-production;DB_CLOSE_DELAY=-1",
                                  "username":"sa",
                                  "password":"",
                                  "environment":"prod",
                                  "readonly":false
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long productionConnectionId = json.readTree(productionBody).path("id").asLong();

        mvc.perform(post("/api/mcp/agents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"agentId":"prod-denied","connectionIds":[%d],"allowProduction":false}
                                """.formatted(productionConnectionId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("生产连接")));

        String createBody = mvc.perform(post("/api/mcp/agents")
                        .header("X-User", "integration-test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"agentId":"web-agent","connectionIds":[%d],"allowProduction":false}
                                """.formatted(connectionId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agent.agentId").value("web-agent"))
                .andExpect(jsonPath("$.agent.keyHash").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        JsonNode created = json.readTree(createBody);
        long agentId = created.path("agent").path("id").asLong();
        String firstCredential = created.path("credential").asText();
        assertThat(firstCredential).startsWith("web-agent.").hasSizeGreaterThan(30);

        MvcResult initialized = initialize(firstCredential).andExpect(status().isOk()).andReturn();
        String sessionId = initialized.getResponse().getHeader(McpApiKeyAuthenticationFilter.SESSION_HEADER);
        assertThat(sessionId).isNotBlank();

        MvcResult query = mcpRequest(firstCredential, sessionId, """
                {
                  "jsonrpc":"2.0",
                  "id":2,
                  "method":"tools/call",
                  "params":{
                    "name":"db_query",
                    "arguments":{"connectionId":%d,"query":"select 1 as query_value"}
                  }
                }
                """.formatted(connectionId)).andExpect(status().isOk()).andReturn();
        assertThat(query.getResponse().getContentAsString())
                .contains("\"isError\":false")
                .contains("QUERY_VALUE");

        String rotateBody = mvc.perform(post("/api/mcp/agents/{id}/rotate-key", agentId)
                        .header("X-User", "integration-test"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String secondCredential = json.readTree(rotateBody).path("credential").asText();
        assertThat(secondCredential).startsWith("web-agent.").isNotEqualTo(firstCredential);

        initialize(firstCredential).andExpect(status().isUnauthorized());
        initialize(secondCredential).andExpect(status().isOk());

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/mcp/agents/{id}", agentId)
                        .header("X-User", "integration-test"))
                .andExpect(status().isOk());
        initialize(secondCredential).andExpect(status().isUnauthorized());

        mvc.perform(put("/api/mcp/status")
                        .header("X-User", "integration-test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));

        initialize(secondCredential).andExpect(status().isServiceUnavailable());
    }

    private org.springframework.test.web.servlet.ResultActions initialize(String credential) throws Exception {
        return mcpRequest(credential, null, """
                {
                  "jsonrpc":"2.0",
                  "id":1,
                  "method":"initialize",
                  "params":{
                    "protocolVersion":"2025-11-25",
                    "capabilities":{},
                    "clientInfo":{"name":"web-config-test","version":"1.0"}
                  }
                }
                """);
    }

    private org.springframework.test.web.servlet.ResultActions mcpRequest(String credential, String sessionId, String body) throws Exception {
        var request = post("/mcp")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + credential)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                .content(body);
        if (sessionId != null) request.header(McpApiKeyAuthenticationFilter.SESSION_HEADER, sessionId);
        org.springframework.test.web.servlet.ResultActions action = mvc.perform(request);
        MvcResult result = action.andReturn();
        if (!result.getRequest().isAsyncStarted()) {
            return action;
        }
        return mvc.perform(asyncDispatch(result));
    }
}
