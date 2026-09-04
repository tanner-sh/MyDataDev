package com.example.dbadmin.service.ai.llm;

import com.example.dbadmin.service.ai.AiProvider;

import java.util.function.Consumer;

/**
 * 模型调用的统一入口。
 *
 * <p>两个实现同期落地（官方 SDK 与 OpenAI 兼容协议），是因为只有一个实现的抽象没法验证：
 * 流式事件、token 用量、思考深度这三处两家协议差别最大，等到第二家再补必然要返工接口形状。</p>
 */
public interface LlmClient {
    AiProvider provider();

    /** 一次性调用，拿完整文本。 */
    LlmResponse complete(LlmRequest request);

    /**
     * 流式调用：每个文本增量回调一次，返回汇总结果。
     *
     * <p>{@code onDelta} 由调用线程同步回调，实现方不额外开线程 —— SSE 的写出由上层的
     * {@code SseEmitter} 负责，这里只管把增量吐出来。</p>
     */
    LlmResponse stream(LlmRequest request, Consumer<String> onDelta);

    /**
     * 带函数工具的一轮对话。这里故意只做“一轮”：是否继续、调用哪些本地服务以及调用上限，
     * 都由应用层 Agent 编排器控制，provider 不能越过权限和隐私闸门直接碰数据库。
     */
    LlmAgentTurn turn(LlmAgentRequest request);
}
