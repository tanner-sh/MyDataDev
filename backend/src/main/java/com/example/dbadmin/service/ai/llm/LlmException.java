package com.example.dbadmin.service.ai.llm;

/**
 * 上游模型服务返回的错误。
 *
 * <p>把状态码带出来，好让 API 层决定是 502 还是 400；{@code message} 直接面向用户，
 * 所以实现方要把上游的英文错误压缩成一句中文，而不是把整个 JSON 抛到界面上。</p>
 */
public class LlmException extends RuntimeException {
    private final int upstreamStatus;

    public LlmException(String message, int upstreamStatus, Throwable cause) {
        super(message, cause);
        this.upstreamStatus = upstreamStatus;
    }

    public LlmException(String message, int upstreamStatus) {
        this(message, upstreamStatus, null);
    }

    public int upstreamStatus() {
        return upstreamStatus;
    }
}
