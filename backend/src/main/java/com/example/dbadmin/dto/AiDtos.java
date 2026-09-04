package com.example.dbadmin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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

    /**
     * 多轮 SQL 对话。浏览器只提交当前这句话；历史和工具结果绑定在服务端短期会话里，
     * 避免客户端伪造模型已经检查过的结构。
     */
    public record AiChatRequest(
            long connectionId,
            @Size(max = 200) String schemaName,
            @Size(max = 36) String conversationId,
            @NotBlank @Size(max = 20000) String message,
            @Size(max = 20000) String currentSql
    ) {
    }

    /** 最终 SQL 所依据的真实结构项。 */
    public record AiGroundingReference(String kind, String label, String detail) {
    }

    /** 编译校验结果和本轮用到的结构证据。 */
    public record AiGroundingReport(
            boolean validated,
            String validationMessage,
            java.util.List<AiGroundingReference> references
    ) {
        public AiGroundingReport {
            references = references == null ? java.util.List.of() : java.util.List.copyOf(references);
        }
    }

    /** 服务端保存并可在页面刷新后恢复的一条可见消息。 */
    public record AiChatMessageResponse(String role, String text, AiGroundingReport grounding) {
    }

    public record AiConversationResponse(
            String id,
            long connectionId,
            String schemaName,
            java.util.List<AiChatMessageResponse> messages
    ) {
    }

    public record AiCancelResponse(boolean cancelled) {
    }

    /** 管理员维护的业务词典：自然语言、别名与真实数据库对象之间的映射。 */
    public record AiGlossaryEntryRequest(
            @NotBlank @Size(max = 120) String term,
            @Size(max = 10) java.util.List<@NotBlank @Size(max = 120) String> aliases,
            @Size(max = 10) java.util.List<@NotBlank @Size(max = 200) String> objectNames,
            @Size(max = 1000) String description
    ) {
    }

    public record AiGlossaryUpdateRequest(
            @NotNull @Size(max = 100) java.util.List<@jakarta.validation.Valid AiGlossaryEntryRequest> entries
    ) {
    }

    public record AiGlossaryEntryResponse(
            long id,
            String term,
            java.util.List<String> aliases,
            java.util.List<String> objectNames,
            String description
    ) {
    }

    /**
     * 执行计划解读请求。
     *
     * <p>{@code findings} 是前端确定性规则（explainInsights.ts）已经得出的结论，一并发过去
     * 让模型在其上解释，而不是重新判断一遍 —— 那部分不该由模型来做。</p>
     */
    public record AiExplainRequest(
            long connectionId,
            @Size(max = 200) String schemaName,
            @NotBlank @Size(max = 20000) String sql,
            @NotBlank @Size(max = 20000) String plan,
            @Size(max = 4000) String findings
    ) {
    }

    /**
     * 结果解读请求。
     *
     * <p>{@code preview} 是前端截好的前几行文本 —— 这是唯一会把真实数据发出去的入口，
     * 因此后端要求连接开了样本档，只授权结构的连接会被拒。</p>
     */
    public record AiInterpretRequest(
            long connectionId,
            @Size(max = 200) String schemaName,
            @NotBlank @Size(max = 20000) String sql,
            @NotBlank @Size(max = 20000) String preview,
            @Size(max = 2000) String chartCandidates
    ) {
    }

    /** Schema 文档生成请求：一次最多 20 张表，超出的会被截断。 */
    public record AiDocumentRequest(
            long connectionId,
            @Size(max = 200) String schemaName,
            @NotNull @Size(min = 1, max = 50) java.util.List<@NotBlank @Size(max = 200) String> tables
    ) {
    }

    /** 结构同步脚本的风险解读请求。脚本只读不改。 */
    public record AiReviewScriptRequest(
            long connectionId,
            @NotBlank @Size(max = 40000) String script
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
    public record AiStatusResponse(
            boolean enabled,
            java.util.List<Long> sharedConnectionIds,
            /** 其中还开了样本档的连接：只有这些连接允许把查询结果发给模型解读。 */
            java.util.List<Long> sampledConnectionIds
    ) {
    }
}
