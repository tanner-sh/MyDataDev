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

    /**
     * 报错诊断请求。
     *
     * <p>SQL 与报错原文都由前端原样回传：后端不留会话，也不从 SQL 历史里翻 —— 用户看到的
     * 那一屏才是要诊断的东西。</p>
     */
    public record AiDiagnoseRequest(
            long connectionId,
            @Size(max = 200) String schemaName,
            @NotBlank @Size(max = 20000) String sql,
            @NotBlank @Size(max = 10000) String errorMessage
    ) {
    }

    /**
     * 自然语言转 SQL 的请求。
     *
     * <p>问题原文不落任何一张表：既不进 sql_history，也不进审计，只活在这一次请求里。</p>
     */
    public record AiGenerateRequest(
            long connectionId,
            @Size(max = 200) String schemaName,
            @NotBlank @Size(max = 2000) String question
    ) {
    }

    /** 一次问答的回答。文本是 Markdown，前端只做代码块提取，不整段渲染 HTML。 */
    public record AiAnswerResponse(String text) {
    }

    /**
     * 给所有登录用户的可用性快照。
     *
     * <p>设置面板是管理员的，但「这条连接上要不要显示 AI 按钮」是每个用户都要知道的事，
     * 所以单独开一个不含任何配置细节的只读接口：只说功能开没开、哪些连接被授权了。</p>
     */
    public record AiStatusResponse(boolean enabled, java.util.List<Long> sharedConnectionIds) {
    }
}
