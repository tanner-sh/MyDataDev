package com.example.dbadmin.service.ai;

import java.util.Locale;

/**
 * 大模型服务商。
 *
 * <p>只有两种：官方 Claude API，以及「OpenAI 兼容协议」这一大类（自建网关、Ollama、
 * vLLM 等）。后者不是为了支持某个具体产品，而是给离线部署留一条不出网的路。</p>
 */
public enum AiProvider {
    /** Claude API，走官方 anthropic-java SDK。 */
    ANTHROPIC("claude-opus-5", false),
    /** OpenAI 兼容协议的 /chat/completions 端点，必须显式给出 base URL。 */
    OPENAI_COMPATIBLE(null, true);

    private final String defaultModel;
    private final boolean requiresBaseUrl;

    AiProvider(String defaultModel, boolean requiresBaseUrl) {
        this.defaultModel = defaultModel;
        this.requiresBaseUrl = requiresBaseUrl;
    }

    public String defaultModel() {
        return defaultModel;
    }

    public boolean requiresBaseUrl() {
        return requiresBaseUrl;
    }

    public static AiProvider parse(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (normalized.isEmpty()) return ANTHROPIC;
        for (AiProvider provider : values()) {
            if (provider.name().equals(normalized)) return provider;
        }
        throw new IllegalArgumentException("不支持的 AI 服务商：" + value);
    }
}
