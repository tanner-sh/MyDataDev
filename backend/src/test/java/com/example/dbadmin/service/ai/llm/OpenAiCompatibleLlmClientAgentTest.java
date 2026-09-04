package com.example.dbadmin.service.ai.llm;

import com.example.dbadmin.service.ai.AiEffort;
import com.example.dbadmin.service.ai.AiProvider;
import com.example.dbadmin.service.ai.AiSettings;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiCompatibleLlmClientAgentTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void sendsFunctionToolsAndParsesTheRequestedToolCall() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = """
                    {"choices":[{"message":{"content":null,"tool_calls":[{"id":"call_1","type":"function","function":{"name":"search_schema","arguments":"{\\"query\\":\\"用户\\"}"}}]}}],"usage":{"prompt_tokens":12,"completion_tokens":4}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            AiSettings settings = new AiSettings(true, AiProvider.OPENAI_COMPATIBLE, baseUrl,
                    "local-model", "", AiEffort.MEDIUM);
            OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(
                    HttpClient.newHttpClient(), settings.baseUrl(), settings.model(), "");

            ObjectNode schema = json.createObjectNode();
            schema.put("type", "object");
            schema.putObject("properties").putObject("query").put("type", "string");
            schema.putArray("required").add("query");
            LlmAgentTurn turn = client.turn(new LlmAgentRequest(
                    "只依据工具返回的结构回答。",
                    List.of(LlmAgentMessage.user("查询用户名称")),
                    List.of(new LlmToolDefinition("search_schema", "搜索结构", schema)),
                    1000));

            assertThat(turn.toolCalls()).singleElement().satisfies(call -> {
                assertThat(call.id()).isEqualTo("call_1");
                assertThat(call.name()).isEqualTo("search_schema");
                assertThat(call.arguments().path("query").asText()).isEqualTo("用户");
            });
            assertThat(turn.inputTokens()).isEqualTo(12);
            assertThat(turn.outputTokens()).isEqualTo(4);

            JsonNode sent = json.readTree(requestBody.get());
            assertThat(sent.path("messages").path(0).path("role").asText()).isEqualTo("system");
            assertThat(sent.path("messages").path(1).path("content").asText()).isEqualTo("查询用户名称");
            assertThat(sent.path("tools").path(0).path("function").path("name").asText())
                    .isEqualTo("search_schema");
            assertThat(sent.path("tools").path(0).path("function").path("parameters").path("required").path(0).asText())
                    .isEqualTo("query");
        } finally {
            server.stop(0);
        }
    }
}
