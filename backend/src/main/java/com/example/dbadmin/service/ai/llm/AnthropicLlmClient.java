package com.example.dbadmin.service.ai.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlockParam;
import com.example.dbadmin.service.ai.AiEffort;
import com.example.dbadmin.service.ai.AiProvider;
import com.example.dbadmin.service.ai.AiSettings;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 官方 Claude API 实现。
 *
 * <p>系统提示走 {@code systemOfTextBlockParams} 并打上 {@code cache_control}：结构摘要是稳定
 * 前缀，同一条连接的连续提问基本都会命中缓存。这也是调用方必须把每次都变的内容放进
 * 用户提示的原因 —— 前缀里混进一个时间戳，缓存就永远打不中。</p>
 *
 * <p>不设 {@code thinking}：Claude Opus 5 默认就是自适应思考，显式关掉反而会让模型偶尔把
 * 工具调用写进正文。深度用 {@code output_config.effort} 调。</p>
 */
public class AnthropicLlmClient implements LlmClient {
    private final AnthropicClient client;
    private final String model;
    private final AiEffort effort;

    public AnthropicLlmClient(AiSettings settings, String apiKey) {
        this(AnthropicOkHttpClient.builder().apiKey(apiKey).build(), settings.model(), settings.effort());
    }

    AnthropicLlmClient(AnthropicClient client, String model, AiEffort effort) {
        this.client = client;
        this.model = model;
        this.effort = effort;
    }

    @Override
    public AiProvider provider() {
        return AiProvider.ANTHROPIC;
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        try {
            Message message = client.messages().create(params(request));
            StringBuilder text = new StringBuilder();
            for (ContentBlock block : message.content()) {
                block.text().ifPresent(value -> text.append(value.text()));
            }
            return new LlmResponse(
                    text.toString(),
                    message.usage().inputTokens(),
                    message.usage().outputTokens(),
                    message.usage().cacheReadInputTokens().orElse(0L)
            );
        } catch (AnthropicServiceException e) {
            throw translate(e);
        }
    }

    @Override
    public LlmResponse stream(LlmRequest request, Consumer<String> onDelta) {
        StringBuilder text = new StringBuilder();
        try (StreamResponse<RawMessageStreamEvent> response = client.messages().createStreaming(params(request))) {
            response.stream()
                    .flatMap(event -> event.contentBlockDelta().stream())
                    .flatMap(delta -> delta.delta().text().stream())
                    .forEach(delta -> {
                        text.append(delta.text());
                        onDelta.accept(delta.text());
                    });
        } catch (AnthropicServiceException e) {
            throw translate(e);
        }
        // 流式事件里也带 usage，但拿它要额外缓存 message_delta 事件；本期只有诊断类调用用
        // 流式，用量统计以非流式为准，这里不装作有数据。
        return LlmResponse.text(text.toString());
    }

    @Override
    public LlmAgentTurn turn(LlmAgentRequest request) {
        try {
            Message message = client.messages().create(agentParams(request));
            StringBuilder text = new StringBuilder();
            List<LlmToolCall> calls = new ArrayList<>();
            for (ContentBlock block : message.content()) {
                block.text().ifPresent(value -> text.append(value.text()));
                block.toolUse().ifPresent(value -> calls.add(new LlmToolCall(
                        value.id(), value.name(), value._input().convert(JsonNode.class))));
            }
            return new LlmAgentTurn(
                    text.toString(), calls,
                    message.usage().inputTokens(),
                    message.usage().outputTokens(),
                    message.usage().cacheReadInputTokens().orElse(0L)
            );
        } catch (AnthropicServiceException e) {
            throw translate(e);
        }
    }

    private MessageCreateParams params(LlmRequest request) {
        MessageCreateParams.Builder builder = MessageCreateParams.builder()
                .model(model)
                .maxTokens(request.maxTokens())
                .outputConfig(OutputConfig.builder().effort(effortValue()).build())
                .addUserMessage(request.userPrompt());
        if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
            builder.systemOfTextBlockParams(List.of(TextBlockParam.builder()
                    .text(request.systemPrompt())
                    .cacheControl(CacheControlEphemeral.builder().build())
                    .build()));
        }
        return builder.build();
    }

    private MessageCreateParams agentParams(LlmAgentRequest request) {
        MessageCreateParams.Builder builder = MessageCreateParams.builder()
                .model(model)
                .maxTokens(request.maxTokens())
                .outputConfig(OutputConfig.builder().effort(effortValue()).build())
                .systemOfTextBlockParams(List.of(TextBlockParam.builder()
                        .text(request.systemPrompt())
                        .cacheControl(CacheControlEphemeral.builder().build())
                        .build()));
        for (LlmAgentMessage message : request.messages()) {
            switch (message.role()) {
                case USER -> builder.addUserMessage(message.text());
                case ASSISTANT -> builder.addMessage(assistantMessage(message));
                case TOOL_RESULTS -> builder.addMessage(toolResultMessage(message.toolResults()));
            }
        }
        for (LlmToolDefinition definition : request.tools()) builder.addTool(tool(definition));
        return builder.build();
    }

    private MessageParam assistantMessage(LlmAgentMessage message) {
        List<ContentBlockParam> blocks = new ArrayList<>();
        if (!message.text().isBlank()) {
            blocks.add(ContentBlockParam.ofText(TextBlockParam.builder().text(message.text()).build()));
        }
        for (LlmToolCall call : message.toolCalls()) {
            ToolUseBlockParam.Input.Builder input = ToolUseBlockParam.Input.builder();
            call.arguments().fields().forEachRemaining(entry ->
                    input.putAdditionalProperty(entry.getKey(), JsonValue.fromJsonNode(entry.getValue())));
            blocks.add(ContentBlockParam.ofToolUse(ToolUseBlockParam.builder()
                    .id(call.id()).name(call.name()).input(input.build()).build()));
        }
        return MessageParam.builder().role(MessageParam.Role.ASSISTANT).contentOfBlockParams(blocks).build();
    }

    private MessageParam toolResultMessage(List<LlmToolResult> results) {
        List<ContentBlockParam> blocks = results.stream()
                .map(result -> ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                        .toolUseId(result.callId())
                        .content(result.content())
                        .isError(result.error())
                        .build()))
                .toList();
        return MessageParam.builder().role(MessageParam.Role.USER).contentOfBlockParams(blocks).build();
    }

    private Tool tool(LlmToolDefinition definition) {
        Tool.InputSchema.Properties.Builder properties = Tool.InputSchema.Properties.builder();
        definition.inputSchema().path("properties").fields().forEachRemaining(entry ->
                properties.putAdditionalProperty(entry.getKey(), JsonValue.fromJsonNode(entry.getValue())));
        Tool.InputSchema.Builder schema = Tool.InputSchema.builder()
                .type(JsonValue.from("object"))
                .properties(properties.build());
        JsonNode required = definition.inputSchema().path("required");
        if (required.isArray()) required.forEach(item -> schema.addRequired(item.asText()));
        return Tool.builder()
                .name(definition.name())
                .description(definition.description())
                .inputSchema(schema.build())
                .build();
    }

    private OutputConfig.Effort effortValue() {
        return switch (effort) {
            case LOW -> OutputConfig.Effort.LOW;
            case MEDIUM -> OutputConfig.Effort.MEDIUM;
            case HIGH -> OutputConfig.Effort.HIGH;
            case XHIGH -> OutputConfig.Effort.XHIGH;
            case MAX -> OutputConfig.Effort.MAX;
        };
    }

    private LlmException translate(AnthropicServiceException e) {
        int status = e.statusCode();
        String reason = switch (status) {
            case 401, 403 -> "API Key 无效或没有权限。";
            case 404 -> "模型不存在：" + model + "。";
            case 429 -> "上游限流，请稍后再试。";
            default -> status >= 500 ? "模型服务暂时不可用。" : "模型服务返回错误。";
        };
        return new LlmException(reason, status, e);
    }
}
