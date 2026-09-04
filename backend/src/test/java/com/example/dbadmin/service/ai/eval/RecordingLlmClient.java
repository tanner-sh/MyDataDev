package com.example.dbadmin.service.ai.eval;

import com.example.dbadmin.service.ai.AiProvider;
import com.example.dbadmin.service.ai.llm.LlmAgentRequest;
import com.example.dbadmin.service.ai.llm.LlmAgentTurn;
import com.example.dbadmin.service.ai.llm.LlmClient;
import com.example.dbadmin.service.ai.llm.LlmRequest;
import com.example.dbadmin.service.ai.llm.LlmResponse;
import com.example.dbadmin.service.ai.llm.LlmToolCall;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 记录每轮模型要调用了哪些工具的透明装饰器。
 *
 * <p>「平均 6.1 次工具调用」这个数字本身没法优化 —— 要知道是搜了两次还是读了三次表，才谈得上
 * 减哪一次。放在 LlmClient 这一层而不是工具那一层，是因为这里看到的是模型的意图序列，包括
 * 被上限拦掉的调用。</p>
 */
public final class RecordingLlmClient implements LlmClient {
    private final LlmClient delegate;
    private final List<String> calls = new ArrayList<>();

    public RecordingLlmClient(LlmClient delegate) {
        this.delegate = delegate;
    }

    /** 从上次 {@link #drain()} 之后记录到的工具调用，按发生顺序。 */
    public synchronized List<String> drain() {
        List<String> result = List.copyOf(calls);
        calls.clear();
        return result;
    }

    private synchronized void record(LlmAgentTurn turn) {
        for (LlmToolCall call : turn.toolCalls()) calls.add(call.name());
    }

    @Override
    public AiProvider provider() {
        return delegate.provider();
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        return delegate.complete(request);
    }

    @Override
    public LlmResponse stream(LlmRequest request, Consumer<String> onDelta) {
        return delegate.stream(request, onDelta);
    }

    @Override
    public LlmAgentTurn turn(LlmAgentRequest request) {
        LlmAgentTurn turn = delegate.turn(request);
        record(turn);
        return turn;
    }

    @Override
    public LlmAgentTurn turn(LlmAgentRequest request, Consumer<String> onDelta) {
        LlmAgentTurn turn = delegate.turn(request, onDelta);
        record(turn);
        return turn;
    }
}
