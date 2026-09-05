package com.example.dbadmin.service.ai;

/**
 * 全局 AI 配置，元数据库里恒为一行（id = 1）。
 *
 * <p>刻意不带 {@code userId}：API Key 由管理员统一维护，多用户模式下所有人共用同一份
 * 配置。按用户存 Key 意味着每个人都要自备一份凭据，也意味着审计里「谁花的钱」和
 * 「谁调的接口」是两回事，本期不做。</p>
 *
 * @param apiKeyCipher {@link com.example.dbadmin.service.CryptoService} 加密后的密文，
 *                     与数据库密码同一把系统托管主密钥；未配置时为 {@code null}
 * @param dailyTokenBudget 全站每天的 token 上限，0 表示不限制
 * @param userDailyTokenBudget 每人每天的 token 上限，0 表示不限制
 */
public record AiSettings(
        boolean enabled,
        AiProvider provider,
        String baseUrl,
        String model,
        String apiKeyCipher,
        AiEffort effort,
        long dailyTokenBudget,
        long userDailyTokenBudget
) {
    /** 不设预算的简写。配置以外的地方（测试、评测）并不关心额度。 */
    public AiSettings(boolean enabled, AiProvider provider, String baseUrl, String model,
                      String apiKeyCipher, AiEffort effort) {
        this(enabled, provider, baseUrl, model, apiKeyCipher, effort, 0, 0);
    }

    public static AiSettings disabled() {
        return new AiSettings(false, AiProvider.ANTHROPIC, null, AiProvider.ANTHROPIC.defaultModel(), null, AiEffort.HIGH);
    }

    public boolean hasApiKey() {
        return apiKeyCipher != null && !apiKeyCipher.isBlank();
    }
}
