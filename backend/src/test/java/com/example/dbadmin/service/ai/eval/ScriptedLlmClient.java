package com.example.dbadmin.service.ai.eval;

import com.example.dbadmin.service.ai.AiProvider;
import com.example.dbadmin.service.ai.llm.LlmAgentRequest;
import com.example.dbadmin.service.ai.llm.LlmAgentTurn;
import com.example.dbadmin.service.ai.llm.LlmClient;
import com.example.dbadmin.service.ai.llm.LlmRequest;
import com.example.dbadmin.service.ai.llm.LlmResponse;
import com.example.dbadmin.service.ai.llm.LlmToolCall;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 按剧本逐轮返回的假模型，让 Agent 的编排能在 CI 里跑真流程而不需要 API Key。
 *
 * <p>同时记下每轮拿到的消息，这样「工具结果有没有回传给模型」「重试提示长什么样」这类问题
 * 可以直接断言，而不用靠抓日志。</p>
 */
public final class ScriptedLlmClient implements LlmClient {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final List<LlmAgentTurn> script;
    private final List<LlmAgentRequest> seen = new ArrayList<>();
    private int index;

    public ScriptedLlmClient(List<LlmAgentTurn> script) {
        this.script = List.copyOf(script);
    }

    public static LlmAgentTurn answer(String text) {
        return new LlmAgentTurn(text, List.of(), 100, 20, 0);
    }

    public static LlmAgentTurn toolCall(String id, String name, String argumentsJson) {
        try {
            return new LlmAgentTurn("", List.of(new LlmToolCall(id, name, JSON.readTree(argumentsJson))), 100, 20, 0);
        } catch (Exception e) {
            throw new IllegalArgumentException("剧本里的工具参数不是合法 JSON", e);
        }
    }

    /** 每一轮实际发给模型的请求，按顺序。 */
    public List<LlmAgentRequest> seen() {
        return List.copyOf(seen);
    }

    @Override
    public AiProvider provider() {
        return AiProvider.ANTHROPIC;
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        return LlmResponse.text("");
    }

    @Override
    public LlmResponse stream(LlmRequest request, Consumer<String> onDelta) {
        return LlmResponse.text("");
    }

    @Override
    public LlmAgentTurn turn(LlmAgentRequest request) {
        seen.add(request);
        if (index >= script.size()) {
            throw new IllegalStateException("剧本只有 " + script.size() + " 轮，Agent 却要第 " + (index + 1) + " 轮");
        }
        return script.get(index++);
    }
}
