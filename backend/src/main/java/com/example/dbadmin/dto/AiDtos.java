package com.example.dbadmin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class AiDtos {
    private AiDtos() {
    }

    /**
     * 回显给界面的 AI 配置。
     *
     * <p>只说 Key 配没配（{@code apiKeyConfigured}），永远不回传密文或明文 —— 管理员自己
     * 也读不回来，要换就重填。</p>
     */
    public record AiSettingsResponse(
            boolean enabled,
            String provider,
            String baseUrl,
            String model,
            String effort,
            boolean apiKeyConfigured
    ) {
    }

    /** {@code apiKey} 传 {@code ******} 表示沿用已保存的 Key，传空串表示清除。 */
    public record AiSettingsUpdateRequest(
            boolean enabled,
            @NotBlank @Size(max = 32) String provider,
            @Size(max = 512) String baseUrl,
            @Size(max = 128) String model,
            @Size(max = 2000) String apiKey,
            @Size(max = 16) String effort
    ) {
    }

    /** 连接的共享策略。列表按连接展开，未配置过的连接也会出现，档位是 {@code NONE}。 */
    public record AiConnectionPolicyResponse(
            long connectionId,
            String connectionName,
            String dbType,
            String environment,
            boolean production,
            String sharing,
            int sampleRowLimit
    ) {
    }

    public record AiConnectionPolicyRequest(
            @NotBlank @Size(max = 24) String sharing,
            Integer sampleRowLimit
    ) {
    }

    /** 连通性测试结果。失败时 {@code ok=false}，把上游原因原样带回界面。 */
    public record AiProbeResponse(boolean ok, String provider, String model, long latencyMs, String message) {
    }
}
