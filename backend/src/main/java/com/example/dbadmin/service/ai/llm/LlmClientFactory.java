package com.example.dbadmin.service.ai.llm;

import com.example.dbadmin.service.CryptoService;
import com.example.dbadmin.service.ai.AiSettings;
import org.springframework.stereotype.Component;

/**
 * 按当前配置造一个客户端。
 *
 * <p>不缓存：配置改了就该立刻生效，而建客户端本身只是包一层 HTTP 客户端，代价可以忽略。</p>
 */
@Component
public class LlmClientFactory {
    private final CryptoService crypto;

    public LlmClientFactory(CryptoService crypto) {
        this.crypto = crypto;
    }

    public LlmClient create(AiSettings settings) {
        String apiKey = settings.hasApiKey() ? crypto.decrypt(settings.apiKeyCipher()) : null;
        return switch (settings.provider()) {
            case ANTHROPIC -> new AnthropicLlmClient(settings, apiKey);
            case OPENAI_COMPATIBLE -> new OpenAiCompatibleLlmClient(settings, apiKey);
        };
    }
}
