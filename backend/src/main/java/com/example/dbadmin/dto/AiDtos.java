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
            boolean apiKeyConfigured,
            long dailyTokenBudget,
            long userDailyTokenBudget
    ) {
    }

    /** {@code apiKey} 传 {@code ******} 表示沿用已保存的 Key，传空串表示清除。 */
    public record AiSettingsUpdateRequest(
            boolean enabled,
            @NotBlank @Size(max = 32) String provider,
            @Size(max = 512) String baseUrl,
            @Size(max = 128) String model,
            @Size(max = 2000) String apiKey,
            @Size(max = 16) String effort,
            Long dailyTokenBudget,
            Long userDailyTokenBudget
    ) {
        /** 不带预算字段的请求：沿用已保存的额度，而不是把它清零。 */
        public AiSettingsUpdateRequest(boolean enabled, String provider, String baseUrl, String model,
                                       String apiKey, String effort) {
            this(enabled, provider, baseUrl, model, apiKey, effort, null, null);
        }
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

    /**
     * AI token 用量与预算。
     *
     * @param usedToday 全站今日已消耗（输入 + 输出）；缓存读单独统计，它说明的是省了多少
     * @param usedTodayByCaller 当前这个用户今日已消耗
     * @param days 回看窗口的天数
     */
    public record AiUsageResponse(
            long dailyTokenBudget,
            long userDailyTokenBudget,
            long usedToday,
            long usedTodayByCaller,
            int days,
            java.util.List<AiUsageDayResponse> daily,
            java.util.List<AiUsageActorResponse> actors
    ) {
    }

    public record AiUsageDayResponse(
            String day,
            int requests,
            long inputTokens,
            long outputTokens,
            long cacheReadTokens
    ) {
    }

    public record AiUsageActorResponse(String actor, int requests, long tokens) {
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
            @Size(max = 20000) String currentSql,
            @jakarta.validation.Valid AiExecutionFailure failure,
            @jakarta.validation.Valid AiExecutionOutcome outcome,
            @jakarta.validation.Valid AiExecutionPlan plan
    ) {
    }

    /**
     * 一次执行失败的现场：跑挂的那条 SQL 与驱动返回的错误原文。
     *
     * <p>独立成字段而不是让前端拼进 {@code message}，是因为这两段都是不可信数据 —— 错误原文来自
     * 目标数据库，里面可能带着任何内容。拼进用户消息，它们看起来就和用户的指令没有区别了。</p>
     */
    public record AiExecutionFailure(
            @NotBlank @Size(max = 20000) String sql,
            @NotBlank @Size(max = 8000) String errorMessage
    ) {
    }

    /**
     * 一次执行成功但结果可疑的现场：跑的是哪条 SQL，结果长什么形状。
     *
     * <p>只收计数，不收数据行 —— 查错真正需要的信号本来就是计数（0 行、某列全空、行数爆炸，
     * 说的都是关联写错了），所以这条路留在「只发结构」这一档里。要看真实样本行是另一件事，
     * 走结果解读那个入口，那边要求连接开到「结构 + 样本行」。</p>
     */
    public record AiExecutionOutcome(
            @NotBlank @Size(max = 20000) String sql,
            @Size(max = 4000) String shape
    ) {
    }

    /**
     * 一次执行计划的现场：跑的是哪条 SQL、计划长什么样、确定性规则已经看出了什么。
     *
     * <p>{@code findings} 是 {@code explainInsights.ts} 用固定规则算出来的结论（全表扫描、
     * 未命中索引一类），一并发过去是为了让模型在它们之上解释与给建议，而不是重新判断一遍
     * —— 那部分本来就不该由模型来做。</p>
     */
    public record AiExecutionPlan(
            @NotBlank @Size(max = 20000) String sql,
            @NotBlank @Size(max = 20000) String plan,
            @Size(max = 4000) String findings
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
    /**
     * 会话里的一条可见消息。
     *
     * @param question 这一轮是反问时带上结构化的问题与选项；刷新页面后选项按钮还在，
     *                 用户不必把问题重新读一遍再手打回答
     */
    public record AiChatMessageResponse(
            String role,
            String text,
            AiGroundingReport grounding,
            AiClarifyResponse question
    ) {
        public AiChatMessageResponse(String role, String text, AiGroundingReport grounding) {
            this(role, text, grounding, null);
        }
    }

    /** 模型的反问。 */
    public record AiClarifyResponse(String question, java.util.List<AiClarifyOptionResponse> options) {
    }

    public record AiClarifyOptionResponse(String label, String detail) {
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

    /** @param usageCount 这条词条涉及的表在执行历史里被查过多少次；排序依据，也给管理员一个取舍参考 */
    public record AiGlossarySuggestionResponse(
            String term,
            java.util.List<String> aliases,
            java.util.List<String> objectNames,
            String description,
            int usageCount
    ) {
    }

    /** @param uncommentedObjects 连注释都没有的对象：给不出候选词，但正是 AI 最找不到的那批 */
    public record AiGlossarySuggestionsResponse(
            java.util.List<AiGlossarySuggestionResponse> suggestions,
            java.util.List<String> uncommentedObjects
    ) {
    }

    /**
     * AI 搜过、这个库里什么都没搜到的业务词。
     *
     * @param hits 被搜空过多少次；反复搜不到的那个词，正是这条连接上真正缺的说法
     */
    public record AiGlossaryGapResponse(
            String term,
            int hits,
            java.time.Instant lastSeenAt
    ) {
    }

    public record AiGlossaryGapDismissRequest(
            @NotNull @Size(max = 50) java.util.List<@NotBlank @Size(max = 120) String> terms
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
