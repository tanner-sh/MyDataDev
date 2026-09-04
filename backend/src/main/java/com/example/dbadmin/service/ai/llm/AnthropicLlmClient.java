package com.example.dbadmin.service.ai.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.helpers.MessageAccumulator;
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
 * 用户提示的原因 —— 前缀里混进一个时间戳，缓存就永远打不中。Agent 轮次还会在最后一条消息上
 * 追加一个滚动断点，细节见 {@link #agentParams}。</p>
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
            return response(client.messages().create(params(request)));
        } catch (AnthropicServiceException e) {
            throw translate(e);
        }
    }

    @Override
    public LlmResponse stream(LlmRequest request, Consumer<String> onDelta) {
        MessageAccumulator accumulator = MessageAccumulator.create();
        try (StreamResponse<RawMessageStreamEvent> response = client.messages().createStreaming(params(request))) {
            response.stream().forEach(event -> {
                accumulator.accumulate(event);
                event.contentBlockDelta()
                        .flatMap(block -> block.delta().text())
                        .ifPresent(delta -> onDelta.accept(delta.text()));
            });
        } catch (AnthropicServiceException e) {
            throw translate(e);
        }
        return response(accumulator.message());
    }

    @Override
    public LlmAgentTurn turn(LlmAgentRequest request) {
        try {
            return agentTurn(client.messages().create(agentParams(request)));
        } catch (AnthropicServiceException e) {
            throw translate(e);
        }
    }

    @Override
    public LlmAgentTurn turn(LlmAgentRequest request, Consumer<String> onDelta) {
        MessageAccumulator accumulator = MessageAccumulator.create();
        try (StreamResponse<RawMessageStreamEvent> response = client.messages().createStreaming(agentParams(request))) {
            response.stream().forEach(event -> {
                accumulator.accumulate(event);
                // 工具参数也走 input_json_delta，但它不是给人看的；只把正文增量吐出去。
                event.contentBlockDelta()
                        .flatMap(block -> block.delta().text())
                        .ifPresent(delta -> onDelta.accept(delta.text()));
            });
        } catch (AnthropicServiceException e) {
            throw translate(e);
        }
        return agentTurn(accumulator.message());
    }

    private static LlmAgentTurn agentTurn(Message message) {
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
    }

    private static LlmResponse response(Message message) {
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

    /**
     * Agent 轮次的参数，带两个缓存断点。
     *
     * <p>缓存前缀的顺序是 tools → system → messages，所以打在 system 上的那个断点覆盖了工具
     * 定义和系统提示。但 Agent 真正膨胀的是 messages：每一轮都要把此前所有工具结果（结构
     * JSON、DDL）原样重发，不缓存的话输入 token 随轮次平方增长。</p>
     *
     * <p>因此在最后一条消息上再打一个滚动断点：本轮写入的缓存，正好是下一轮的前缀。断点只
     * 落在 USER 与 TOOL_RESULTS 上 —— Agent 编排器总是在追加这两种消息之后才调用模型，落在
     * 助手消息上没有意义。</p>
     */
    private MessageCreateParams agentParams(LlmAgentRequest request) {
        MessageCreateParams.Builder builder = MessageCreateParams.builder()
                .model(model)
                .maxTokens(request.maxTokens())
                .outputConfig(OutputConfig.builder().effort(effortValue()).build())
                .systemOfTextBlockParams(List.of(TextBlockParam.builder()
                        .text(request.systemPrompt())
                        .cacheControl(CacheControlEphemeral.builder().build())
                        .build()));
        List<LlmAgentMessage> messages = request.messages();
        for (int index = 0; index < messages.size(); index++) {
            LlmAgentMessage message = messages.get(index);
            boolean last = index == messages.size() - 1;
            switch (message.role()) {
                case USER -> builder.addMessage(userMessage(message.text(), last));
                case ASSISTANT -> builder.addMessage(assistantMessage(message));
                case TOOL_RESULTS -> builder.addMessage(toolResultMessage(message.toolResults(), last));
            }
        }
        for (LlmToolDefinition definition : request.tools()) builder.addTool(tool(definition));
        return builder.build();
    }

    private static MessageParam userMessage(String text, boolean cacheBreakpoint) {
        TextBlockParam.Builder block = TextBlockParam.builder().text(text);
        if (cacheBreakpoint) block.cacheControl(CacheControlEphemeral.builder().build());
        return MessageParam.builder()
                .role(MessageParam.Role.USER)
                .contentOfBlockParams(List.of(ContentBlockParam.ofText(block.build())))
                .build();
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

    private MessageParam toolResultMessage(List<LlmToolResult> results, boolean cacheBreakpoint) {
        List<ContentBlockParam> blocks = new ArrayList<>();
        for (int index = 0; index < results.size(); index++) {
            LlmToolResult result = results.get(index);
            ToolResultBlockParam.Builder block = ToolResultBlockParam.builder()
                    .toolUseId(result.callId())
                    .content(result.content())
                    .isError(result.error());
            // 断点要打在整条消息的最后一个块上，前面的块留在被缓存的前缀里。
            if (cacheBreakpoint && index == results.size() - 1) {
                block.cacheControl(CacheControlEphemeral.builder().build());
            }
            blocks.add(ContentBlockParam.ofToolResult(block.build()));
        }
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
