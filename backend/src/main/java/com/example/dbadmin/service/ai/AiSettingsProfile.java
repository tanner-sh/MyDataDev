package com.example.dbadmin.service.ai;

import com.example.dbadmin.dto.AiDtos.AiSettingsResponse;
import com.example.dbadmin.dto.AiDtos.AiSettingsUpdateRequest;
import com.example.dbadmin.service.ConnectionService;

import java.util.Locale;
import java.util.function.UnaryOperator;

/**
 * AI 配置的规范化、校验与密文转换。
 *
 * <p>加解密由调用方以函数传入，这个类保持纯逻辑可直接测；接 {@code CryptoService} 是
 * {@link AiSettingsService} 的事。写法与 {@link com.example.dbadmin.service.SshTunnelProfile}
 * 一致，掩码语义也一致：{@code ******} 沿用旧值，空串清除。</p>
 */
public final class AiSettingsProfile {
    public static final String SECRET_MASK = ConnectionService.PASSWORD_MASK;
    public static final int MAX_MODEL_LENGTH = 128;
    public static final int MAX_BASE_URL_LENGTH = 512;
    /** 单日预算的上限：十亿 token 一天，再高就等于没设，但能挡住多打几个零的手滑。 */
    public static final long MAX_DAILY_BUDGET = 1_000_000_000L;

    private AiSettingsProfile() {
    }

    public static AiSettings toSettings(AiSettingsUpdateRequest request, AiSettings existing, UnaryOperator<String> encrypt) {
        if (request == null) throw new IllegalArgumentException("AI 配置不能为空。");
        AiSettings previous = existing == null ? AiSettings.disabled() : existing;
        AiProvider provider = AiProvider.parse(request.provider());
        AiEffort effort = AiEffort.parse(request.effort());
        String model = model(request.model(), provider);
        String baseUrl = baseUrl(request.baseUrl(), provider);
        // 换服务商时旧 Key 不再适用，但用户此刻可能只是先改服务商、下一步再填 Key。
        // 沿用旧密文只在服务商没变时成立，否则会拿 Claude 的 Key 去打自建网关。
        String cipher = provider == previous.provider()
                ? secret(request.apiKey(), previous.apiKeyCipher(), encrypt)
                : secret(request.apiKey(), null, encrypt);

        if (request.enabled() && (cipher == null || cipher.isBlank()) && requiresApiKey(provider)) {
            throw new IllegalArgumentException("启用 AI 之前请先填写 API Key。");
        }
        return new AiSettings(request.enabled(), provider, baseUrl, model, cipher, effort,
                budget(request.dailyTokenBudget(), previous.dailyTokenBudget(), "每日总预算"),
                budget(request.userDailyTokenBudget(), previous.userDailyTokenBudget(), "每人每日预算"));
    }

    public static AiSettingsResponse summarize(AiSettings settings) {
        AiSettings value = settings == null ? AiSettings.disabled() : settings;
        return new AiSettingsResponse(
                value.enabled(),
                value.provider().name(),
                value.baseUrl(),
                value.model(),
                value.effort().name(),
                value.hasApiKey(),
                value.dailyTokenBudget(),
                value.userDailyTokenBudget()
        );
    }

    /**
     * 预算的规范化。
     *
     * <p>缺字段沿用旧值，与 Key 的掩码语义一致：老客户端或只想改模型名的请求，不该把管理员
     * 设好的额度顺手清零。要取消限制得显式传 0 —— 0 表示不限制。</p>
     */
    private static long budget(Long submitted, long previous, String label) {
        if (submitted == null) return previous;
        if (submitted < 0) throw new IllegalArgumentException(label + "不能为负数。");
        if (submitted > MAX_DAILY_BUDGET) {
            throw new IllegalArgumentException(label + "过大（上限 " + MAX_DAILY_BUDGET + " token）。");
        }
        return submitted;
    }

    /**
     * 自建网关和本地模型经常不校验 Key（Ollama 就没有 Key 的概念），所以只有官方 API
     * 强制要求；兼容端点留给用户自己决定填不填。
     */
    private static boolean requiresApiKey(AiProvider provider) {
        return provider == AiProvider.ANTHROPIC;
    }

    private static String model(String submitted, AiProvider provider) {
        String trimmed = submitted == null ? "" : submitted.trim();
        if (trimmed.isEmpty()) {
            if (provider.defaultModel() == null) {
                throw new IllegalArgumentException("请填写模型名称。");
            }
            return provider.defaultModel();
        }
        if (trimmed.length() > MAX_MODEL_LENGTH) {
            throw new IllegalArgumentException("模型名称过长（上限 " + MAX_MODEL_LENGTH + " 字符）。");
        }
        return trimmed;
    }

    /**
     * 兼容端点必须给出 base URL；官方 API 的地址由 SDK 决定，这里一律清空，免得界面上
     * 留着一个填了也不生效的输入框。
     */
    private static String baseUrl(String submitted, AiProvider provider) {
        String trimmed = submitted == null ? "" : submitted.trim();
        if (!provider.requiresBaseUrl()) return null;
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("兼容协议的服务商需要填写接口地址，例如 http://127.0.0.1:11434/v1。");
        }
        if (trimmed.length() > MAX_BASE_URL_LENGTH) {
            throw new IllegalArgumentException("接口地址过长（上限 " + MAX_BASE_URL_LENGTH + " 字符）。");
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            throw new IllegalArgumentException("接口地址必须以 http:// 或 https:// 开头。");
        }
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    /**
     * 解释 Key 字段：掩码沿用旧值，空串清除，其余加密保存。
     *
     * <p>刻意不 {@code trim()} 之外的处理，但这里要 {@code trim()}：Key 从网页复制时经常
     * 带上换行，而它不像口令那样允许首尾空白有语义。</p>
     */
    private static String secret(String submitted, String storedCipher, UnaryOperator<String> encrypt) {
        if (submitted == null || SECRET_MASK.equals(submitted)) {
            return storedCipher != null && !storedCipher.isBlank() ? storedCipher : null;
        }
        String trimmed = submitted.trim();
        if (trimmed.isEmpty()) return null;
        return encrypt.apply(trimmed);
    }
}
