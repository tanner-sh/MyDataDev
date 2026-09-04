package com.example.dbadmin.service.ai.llm;

import com.example.dbadmin.service.ai.AiProvider;
import com.example.dbadmin.service.ai.AiSettings;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * OpenAI 兼容协议（{@code POST {baseUrl}/chat/completions}）实现。
 *
 * <p>存在的意义是离线部署：自建网关、Ollama、vLLM 都讲这套协议，用户不必把库结构发到
 * 公网也能用上功能。用 JDK 自带的 {@link HttpClient} 而不是引第三方 SDK —— 这一侧只用到
 * 协议里最小的一块，多一个依赖不值得。</p>
 *
 * <p>没有思考深度的等价参数，{@code effort} 在这一侧被忽略；这是协议差异，不是遗漏。</p>
 */
public class OpenAiCompatibleLlmClient implements LlmClient {
    private static final Duration TIMEOUT = Duration.ofMinutes(3);

    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();
    private final String baseUrl;
    private final String model;
    private final String apiKey;

    public OpenAiCompatibleLlmClient(AiSettings settings, String apiKey) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build(),
                settings.baseUrl(), settings.model(), apiKey);
    }

    OpenAiCompatibleLlmClient(HttpClient http, String baseUrl, String model, String apiKey) {
        this.http = http;
        this.baseUrl = baseUrl;
        this.model = model;
        this.apiKey = apiKey;
    }

    @Override
    public AiProvider provider() {
        return AiProvider.OPENAI_COMPATIBLE;
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        HttpResponse<String> response = send(body(request, false), HttpResponse.BodyHandlers.ofString());
        requireSuccess(response.statusCode(), response.body());
        try {
            JsonNode root = json.readTree(response.body());
            JsonNode message = root.path("choices").path(0).path("message").path("content");
            JsonNode usage = root.path("usage");
            return new LlmResponse(
                    message.isTextual() ? message.asText() : "",
                    usage.path("prompt_tokens").asLong(0),
                    usage.path("completion_tokens").asLong(0),
                    0
            );
        } catch (IOException e) {
            throw new LlmException("模型服务返回了无法解析的响应。", response.statusCode(), e);
        }
    }

    @Override
    public LlmResponse stream(LlmRequest request, Consumer<String> onDelta) {
        HttpResponse<Stream<String>> response = send(body(request, true), HttpResponse.BodyHandlers.ofLines());
        StringBuilder text = new StringBuilder();
        try (Stream<String> lines = response.body()) {
            if (response.statusCode() >= 400) {
                requireSuccess(response.statusCode(), lines.limit(20).reduce("", (a, b) -> a + b));
            }
            lines.forEach(line -> {
                String delta = parseDelta(line);
                if (delta != null && !delta.isEmpty()) {
                    text.append(delta);
                    onDelta.accept(delta);
                }
            });
        }
        return LlmResponse.text(text.toString());
    }

    @Override
    public LlmAgentTurn turn(LlmAgentRequest request) {
        HttpResponse<String> response = send(agentBody(request), HttpResponse.BodyHandlers.ofString());
        requireSuccess(response.statusCode(), response.body());
        try {
            JsonNode root = json.readTree(response.body());
            JsonNode message = root.path("choices").path(0).path("message");
            String text = message.path("content").isTextual() ? message.path("content").asText() : "";
            List<LlmToolCall> calls = new ArrayList<>();
            for (JsonNode call : message.path("tool_calls")) {
                String arguments = call.path("function").path("arguments").asText("{}");
                JsonNode parsed;
                try {
                    parsed = json.readTree(arguments);
                } catch (IOException ignored) {
                    parsed = json.createObjectNode();
                }
                calls.add(new LlmToolCall(call.path("id").asText(), call.path("function").path("name").asText(), parsed));
            }
            JsonNode usage = root.path("usage");
            return new LlmAgentTurn(text, calls,
                    usage.path("prompt_tokens").asLong(0), usage.path("completion_tokens").asLong(0), 0);
        } catch (IOException e) {
            throw new LlmException("模型服务返回了无法解析的工具调用响应。", response.statusCode(), e);
        }
    }

    /** SSE 行解析：只认 {@code data:} 行，{@code [DONE]} 与心跳行忽略。 */
    private String parseDelta(String line) {
        if (line == null || !line.startsWith("data:")) return null;
        String payload = line.substring("data:".length()).trim();
        if (payload.isEmpty() || "[DONE]".equals(payload)) return null;
        try {
            JsonNode content = json.readTree(payload).path("choices").path(0).path("delta").path("content");
            return content.isTextual() ? content.asText() : null;
        } catch (IOException e) {
            // 兼容端点的实现质量参差，遇到一行坏数据就整段失败不划算：跳过继续读。
            return null;
        }
    }

    private <T> HttpResponse<T> send(String body, HttpResponse.BodyHandler<T> handler) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/chat/completions"))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        // 本地模型通常没有 Key，带一个空的 Authorization 头反而会被某些网关拒掉。
        if (apiKey != null && !apiKey.isBlank()) builder.header("Authorization", "Bearer " + apiKey);
        try {
            return http.send(builder.build(), handler);
        } catch (IOException e) {
            throw new LlmException("无法连接模型服务：" + e.getMessage(), 0, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("模型调用被中断。", 0, e);
        }
    }

    private String body(LlmRequest request, boolean stream) {
        ObjectNode root = json.createObjectNode();
        root.put("model", model);
        root.put("max_tokens", request.maxTokens());
        root.put("stream", stream);
        ArrayNode messages = root.putArray("messages");
        if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
            ObjectNode system = messages.addObject();
            system.put("role", "system");
            system.put("content", request.systemPrompt());
        }
        ObjectNode user = messages.addObject();
        user.put("role", "user");
        user.put("content", request.userPrompt());
        return root.toString();
    }

    private String agentBody(LlmAgentRequest request) {
        ObjectNode root = json.createObjectNode();
        root.put("model", model);
        root.put("max_tokens", request.maxTokens());
        root.put("stream", false);
        ArrayNode messages = root.putArray("messages");
        if (!request.systemPrompt().isBlank()) {
            ObjectNode system = messages.addObject();
            system.put("role", "system");
            system.put("content", request.systemPrompt());
        }
        for (LlmAgentMessage message : request.messages()) {
            switch (message.role()) {
                case USER -> {
                    ObjectNode item = messages.addObject();
                    item.put("role", "user");
                    item.put("content", message.text());
                }
                case ASSISTANT -> {
                    ObjectNode item = messages.addObject();
                    item.put("role", "assistant");
                    if (message.text().isBlank()) item.putNull("content");
                    else item.put("content", message.text());
                    if (!message.toolCalls().isEmpty()) {
                        ArrayNode calls = item.putArray("tool_calls");
                        for (LlmToolCall call : message.toolCalls()) {
                            ObjectNode encoded = calls.addObject();
                            encoded.put("id", call.id());
                            encoded.put("type", "function");
                            ObjectNode function = encoded.putObject("function");
                            function.put("name", call.name());
                            function.put("arguments", call.arguments().toString());
                        }
                    }
                }
                case TOOL_RESULTS -> {
                    for (LlmToolResult result : message.toolResults()) {
                        ObjectNode item = messages.addObject();
                        item.put("role", "tool");
                        item.put("tool_call_id", result.callId());
                        item.put("content", result.content());
                    }
                }
            }
        }
        ArrayNode tools = root.putArray("tools");
        for (LlmToolDefinition definition : request.tools()) {
            ObjectNode item = tools.addObject();
            item.put("type", "function");
            ObjectNode function = item.putObject("function");
            function.put("name", definition.name());
            function.put("description", definition.description());
            function.set("parameters", definition.inputSchema());
        }
        return root.toString();
    }

    private void requireSuccess(int status, String body) {
        if (status < 400) return;
        String reason = switch (status) {
            case 401, 403 -> "API Key 无效或没有权限。";
            case 404 -> "接口地址或模型不存在，请检查 base URL 是否以 /v1 结尾。";
            case 429 -> "上游限流，请稍后再试。";
            default -> status >= 500 ? "模型服务暂时不可用。" : "模型服务返回错误。";
        };
        throw new LlmException(reason + upstreamHint(body), status);
    }

    /** 兼容端点的报错信息往往是唯一线索，截一段带回界面，但不整段透传。 */
    private String upstreamHint(String body) {
        if (body == null || body.isBlank()) return "";
        String flattened = body.replaceAll("\\s+", " ").trim();
        return "（上游：" + (flattened.length() > 200 ? flattened.substring(0, 200) + "…" : flattened) + "）";
    }
}
