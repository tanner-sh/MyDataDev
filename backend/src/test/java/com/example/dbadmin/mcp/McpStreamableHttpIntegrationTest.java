package com.example.dbadmin.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.transport.WebMvcStreamableServerTransportProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.address=127.0.0.1",
        "spring.datasource.url=jdbc:h2:mem:mcp-streamable-http-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "app.crypto-key-file=target/test-secrets/mcp-streamable-http-test.key",
        "app.backup.directory=${java.io.tmpdir}/mydatadev-mcp-streamable-http-test-backups",
        "app.mcp.enabled=true",
        "app.mcp.agents[0].id=integration-agent",
        "app.mcp.agents[0].key-hash=$2y$04$MSNn9Q.hrj2hRiKsuc1Acenmf50KhwoLyzjHKA5erHiDBAp89eav6",
        "app.mcp.agents[0].connection-ids[0]=999"
})
class McpStreamableHttpIntegrationTest {
    private static final String PROTOCOL_VERSION = "2025-11-25";

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private WebMvcStreamableServerTransportProvider transport;

    @Test
    void listeningGetSendsHeadersBeforeAnyEvent() throws Exception {
        String session = initialize();
        var listener = open("GET", session);
        try {
            assertThat(listener.getResponseCode()).isEqualTo(200);
            assertThat(listener.getContentType()).startsWith(MediaType.TEXT_EVENT_STREAM_VALUE);
            var ping = post(session, """
                    {"jsonrpc":"2.0","id":2,"method":"ping"}
                    """);
            try {
                assertThat(ping.getResponseCode()).isEqualTo(200);
                assertThat(readBody(ping)).contains("\"result\":{}");
            } finally {
                ping.disconnect();
            }

            transport.notifyClients("notifications/message", Map.of("level", "info", "data", "sse-test"))
                    .block(Duration.ofSeconds(3));
            var stream = new BufferedReader(new InputStreamReader(listener.getInputStream(), StandardCharsets.UTF_8));
            String line;
            do {
                line = stream.readLine();
            } while (line != null && !line.startsWith("data:"));
            assertThat(line).contains("\"method\":\"notifications/message\"", "\"data\":\"sse-test\"");
        } finally {
            listener.disconnect();
            delete(session);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"resources/list", "prompts/list", "unknown/method"})
    void unknownMethodSendsErrorAndEndsPostResponse(String method) throws Exception {
        String session = initialize();
        var request = post(session, """
                {"jsonrpc":"2.0","id":"request-1","method":"%s"}
                """.formatted(method));
        try {
            assertThat(request.getResponseCode()).isEqualTo(200);
            var data = readBody(request).lines()
                    .filter(line -> line.startsWith("data:"))
                    .map(line -> line.substring("data:".length()))
                    .toList();
            assertThat(data).hasSize(1);
            var result = mapper.readTree(data.get(0));
            assertThat(result.path("id").asText()).isEqualTo("request-1");
            assertThat(result.at("/error/code").asInt()).isEqualTo(-32601);
        } finally {
            request.disconnect();
            delete(session);
        }
    }

    private String initialize() throws IOException {
        var request = post(null, """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
                    "protocolVersion":"%s","capabilities":{},"clientInfo":{"name":"http-test","version":"1"}}}
                """.formatted(PROTOCOL_VERSION));
        String session;
        try {
            assertThat(request.getResponseCode()).isEqualTo(200);
            session = request.getHeaderField(McpApiKeyAuthenticationFilter.SESSION_HEADER);
            assertThat(session).isNotBlank();
        } finally {
            request.disconnect();
        }
        var notification = post(session, """
                {"jsonrpc":"2.0","method":"notifications/initialized"}
                """);
        try {
            assertThat(notification.getResponseCode()).isEqualTo(202);
        } finally {
            notification.disconnect();
        }
        return session;
    }

    private HttpURLConnection open(String method, String session) throws IOException {
        var connection = (HttpURLConnection) URI.create("http://127.0.0.1:" + port + "/mcp").toURL().openConnection();
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(3000);
        connection.setRequestMethod(method);
        connection.setRequestProperty(HttpHeaders.AUTHORIZATION, "Bearer integration-agent.integration-secret");
        connection.setRequestProperty(HttpHeaders.ACCEPT, "GET".equals(method)
                ? MediaType.TEXT_EVENT_STREAM_VALUE : "application/json, text/event-stream");
        if (session != null) {
            connection.setRequestProperty(McpApiKeyAuthenticationFilter.SESSION_HEADER, session);
            connection.setRequestProperty("MCP-Protocol-Version", PROTOCOL_VERSION);
        }
        return connection;
    }

    private HttpURLConnection post(String session, String body) throws IOException {
        var connection = open("POST", session);
        connection.setRequestProperty(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        connection.setDoOutput(true);
        try (var output = connection.getOutputStream()) {
            output.write(body.getBytes(StandardCharsets.UTF_8));
        }
        return connection;
    }

    private void delete(String session) throws IOException {
        var connection = open("DELETE", session);
        try {
            assertThat(connection.getResponseCode()).isEqualTo(200);
        } finally {
            connection.disconnect();
        }
    }

    private static String readBody(HttpURLConnection connection) throws IOException {
        try (var input = connection.getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
