package com.example.dbadmin.service.ai;

import com.example.dbadmin.model.DbConnection;
import com.example.dbadmin.repo.AuditRepository;
import com.example.dbadmin.api.ApiProblemException;
import com.example.dbadmin.service.BackgroundTaskControl;
import com.example.dbadmin.service.ConnectionService;
import com.example.dbadmin.service.ai.llm.LlmClient;
import com.example.dbadmin.service.ai.llm.LlmClientFactory;
import com.example.dbadmin.service.ai.llm.LlmException;
import com.example.dbadmin.service.ai.llm.LlmRequest;
import com.example.dbadmin.service.ai.llm.LlmResponse;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * AI 问答的编排。
 *
 * <p>每个入口都是同一套顺序：过两道闸门（功能启用、连接授权）→ 取结构上下文 → 组提示 →
 * 调模型 → 写审计。顺序不能变，尤其是闸门必须在取结构之前。</p>
 *
 * <p>审计只记「发生了什么、发了多少」：动作、连接、共享档位、模型、字符数。用户输入的自然
 * 语言与 SQL 原文不进审计表 —— 审计本身是会被导出的，把输入原样存进去等于开第二个泄露口。</p>
 */
@Service
public class AiAssistantService {
    private static final Logger log = LoggerFactory.getLogger(AiAssistantService.class);
    /** 流式连接的空闲上限：模型停止推送之后还挂着的连接对谁都没用。 */
    private static final long STREAM_TIMEOUT_MS = 300_000;

    /**
     * 审计动作码。
     *
     * <p>抽成常量不是为了复用，而是为了可校验：这里的审计调用传的是变量，
     * {@code AuditActionLabelCoverageTest} 那条正则只认字面量，扫不到它们。
     * {@link com.example.dbadmin.service.ai.AiAuditActionsTest} 盯着这个数组。</p>
     */
    public static final String ACTION_GENERATE = "AI_GENERATE_SQL";
    public static final String ACTION_EXPLAIN = "AI_EXPLAIN_INSIGHT";
    public static final String ACTION_INTERPRET = "AI_INTERPRET_RESULT";
    public static final String ACTION_DOCUMENT = "AI_DOCUMENT_SCHEMA";
    public static final String ACTION_REVIEW_SCRIPT = "AI_REVIEW_SCRIPT";

    public static final List<String> AUDIT_ACTIONS = List.of(
            ACTION_GENERATE, ACTION_EXPLAIN, ACTION_INTERPRET, ACTION_DOCUMENT, ACTION_REVIEW_SCRIPT,
            AiSqlAgentService.ACTION_CHAT);

    /** 一次文档任务最多覆盖多少张表：再多就该分几次写，而不是攒一个跑十分钟的长回答。 */
    public static final int MAX_DOCUMENT_TABLES = 20;

    private final AiSettingsService settings;
    private final SchemaContextBuilder contexts;
    private final ConnectionService connections;
    private final LlmClientFactory clients;
    private final AuditRepository audit;
    private final BackgroundTaskControl tasks;
    private final ExecutorService streamWorkers = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "ai-stream");
        thread.setDaemon(true);
        return thread;
    });

    public AiAssistantService(
            AiSettingsService settings,
            SchemaContextBuilder contexts,
            ConnectionService connections,
            LlmClientFactory clients,
            AuditRepository audit,
            BackgroundTaskControl tasks
    ) {
        this.settings = settings;
        this.contexts = contexts;
        this.connections = connections;
        this.clients = clients;
        this.audit = audit;
        this.tasks = tasks;
    }

    @PreDestroy
    void shutdown() {
        streamWorkers.shutdownNow();
        try {
            streamWorkers.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 自然语言转 SQL。产出只回到编辑器，这里没有任何执行入口。 */
    public String generate(long connectionId, String schemaName, String question, String actor) {
        Prepared prepared = prepareGenerate(connectionId, schemaName, question, actor);
        LlmResponse response = prepared.client().complete(prepared.request());
        writeAudit(ACTION_GENERATE, connectionId, prepared, response, actor);
        return response.text();
    }

    public SseEmitter generateStream(long connectionId, String schemaName, String question, String actor) {
        Prepared prepared = prepareGenerate(connectionId, schemaName, question, actor);
        return stream(prepared, ACTION_GENERATE, connectionId, actor);
    }

    private Prepared prepareGenerate(long connectionId, String schemaName, String question, String actor) {
        AiSettings current = settings.requireEnabled(actor);
        AiConnectionPolicy policy = settings.requireSharedConnection(connectionId);
        DbConnection connection = connections.require(connectionId);
        SchemaContext context = contexts.forQuestion(connectionId, schemaName, question, policy);
        LlmRequest request = LlmRequest.of(
                AiPromptBuilder.system(context, dialectHint(connection)),
                AiPromptBuilder.generate(question, connection.readonly()));
        return new Prepared(clients.create(current), request, current, policy, context);
    }

    /**
     * 解读一批查询结果。
     *
     * <p>这是唯一会把真实数据发出去的入口，因此额外要求连接开了样本档：只读结构的连接
     * 在这里直接拒绝，而不是悄悄发一份「反正只有几行」的数据。</p>
     */
    public String interpret(long connectionId, String schemaName, String sql, String preview, String chartCandidates, String actor) {
        Prepared prepared = prepareInterpret(connectionId, schemaName, sql, preview, chartCandidates, actor);
        LlmResponse response = prepared.client().complete(prepared.request());
        writeAudit(ACTION_INTERPRET, connectionId, prepared, response, actor);
        return response.text();
    }

    public SseEmitter interpretStream(long connectionId, String schemaName, String sql, String preview, String chartCandidates, String actor) {
        Prepared prepared = prepareInterpret(connectionId, schemaName, sql, preview, chartCandidates, actor);
        return stream(prepared, ACTION_INTERPRET, connectionId, actor);
    }

    private Prepared prepareInterpret(long connectionId, String schemaName, String sql, String preview, String chartCandidates, String actor) {
        AiSettings current = settings.requireEnabled(actor);
        AiConnectionPolicy policy = settings.requireSharedConnection(connectionId);
        if (!policy.sharing().allowsSample()) {
            throw new ApiProblemException(HttpStatus.FORBIDDEN, "AI_SAMPLE_NOT_ALLOWED",
                    "解读结果需要把查询结果发送给模型，这条连接只授权了结构。请在「AI 助手」设置中改为「结构 + 样本行」，生产连接不支持该档位。",
                    Map.of("connectionId", connectionId));
        }
        DbConnection connection = connections.require(connectionId);
        SchemaContext context = contexts.forSql(connectionId, schemaName, sql, policy);
        LlmRequest request = LlmRequest.of(
                AiPromptBuilder.system(context, dialectHint(connection)),
                AiPromptBuilder.interpret(sql, preview, chartCandidates));
        return new Prepared(clients.create(current), request, current, policy, context);
    }

    /**
     * 为一批表生成数据字典。
     *
     * <p>批量意味着一次会读很多张表的结构并跑一次长回答，所以走 {@link BackgroundTaskControl}
     * 的并发闸门：同一条连接上同时只允许一个文档任务，否则几个人各点一次就能把目标库的元数据
     * 查询压满。</p>
     */
    public SseEmitter documentStream(long connectionId, String schemaName, List<String> tables, String actor) {
        AiSettings current = settings.requireEnabled(actor);
        AiConnectionPolicy policy = settings.requireSharedConnection(connectionId);
        DbConnection connection = connections.require(connectionId);
        List<String> targets = tables == null ? List.of() : tables.stream()
                .filter(name -> name != null && !name.isBlank())
                .limit(MAX_DOCUMENT_TABLES)
                .toList();
        if (targets.isEmpty()) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, "AI_NO_TABLES", "请先选择要写进文档的表。");
        }
        String operationKey = "ai-document:" + connectionId;
        if (!tasks.tryAcquire(connectionId, operationKey)) {
            throw new ApiProblemException(HttpStatus.CONFLICT, "AI_DOCUMENT_BUSY",
                    "这条连接上已经有一个文档任务在跑，请等它结束。");
        }
        try {
            SchemaContext context = contexts.forTables(connectionId, schemaName, new LinkedHashSet<>(targets), policy);
            LlmRequest request = LlmRequest.of(
                    AiPromptBuilder.system(context, dialectHint(connection)),
                    AiPromptBuilder.document(schemaName, String.join("、", targets)));
            Prepared prepared = new Prepared(clients.create(current), request, current, policy, context);
            return stream(prepared, ACTION_DOCUMENT, connectionId, actor, () -> tasks.release(connectionId, operationKey));
        } catch (RuntimeException e) {
            tasks.release(connectionId, operationKey);
            throw e;
        }
    }

    /**
     * 解读结构同步脚本的风险。
     *
     * <p>脚本是结构对比按目标端方言生成的，这里只读不改。不取结构上下文：脚本本身已经写明了
     * 表名与列名，再查一遍元数据只是多花时间。</p>
     */
    public SseEmitter reviewScriptStream(long connectionId, String script, String actor) {
        AiSettings current = settings.requireEnabled(actor);
        AiConnectionPolicy policy = settings.requireSharedConnection(connectionId);
        DbConnection connection = connections.require(connectionId);
        LlmRequest request = LlmRequest.of(
                AiPromptBuilder.system(SchemaContext.empty(connection.dbType(), null), dialectHint(connection)),
                AiPromptBuilder.reviewScript(script));
        Prepared prepared = new Prepared(clients.create(current), request, current, policy, SchemaContext.empty(connection.dbType(), null));
        return stream(prepared, ACTION_REVIEW_SCRIPT, connectionId, actor);
    }

    /**
     * 解读执行计划。
     *
     * <p>计划文本与确定性规则已经得出的结论都由前端回传：那些结论是 {@code explainInsights.ts}
     * 算出来的，模型只在它们之上解释「为什么慢、建什么索引」，不重新判断一遍。</p>
     */
    public String explain(long connectionId, String schemaName, String sql, String plan, String findings, String actor) {
        Prepared prepared = prepareExplain(connectionId, schemaName, sql, plan, findings, actor);
        LlmResponse response = prepared.client().complete(prepared.request());
        writeAudit(ACTION_EXPLAIN, connectionId, prepared, response, actor);
        return response.text();
    }

    public SseEmitter explainStream(long connectionId, String schemaName, String sql, String plan, String findings, String actor) {
        Prepared prepared = prepareExplain(connectionId, schemaName, sql, plan, findings, actor);
        return stream(prepared, ACTION_EXPLAIN, connectionId, actor);
    }

    private Prepared prepareExplain(long connectionId, String schemaName, String sql, String plan, String findings, String actor) {
        AiSettings current = settings.requireEnabled(actor);
        AiConnectionPolicy policy = settings.requireSharedConnection(connectionId);
        DbConnection connection = connections.require(connectionId);
        SchemaContext context = contexts.forSql(connectionId, schemaName, sql, policy);
        LlmRequest request = LlmRequest.of(
                AiPromptBuilder.system(context, dialectHint(connection)),
                AiPromptBuilder.explain(sql, plan, findings));
        return new Prepared(clients.create(current), request, current, policy, context);
    }

    private SseEmitter stream(Prepared prepared, String action, long connectionId, String actor) {
        return stream(prepared, action, connectionId, actor, () -> { });
    }

    /**
     * @param onFinish 无论成功失败都要跑的收尾（释放并发闸门等）
     */
    private SseEmitter stream(Prepared prepared, String action, long connectionId, String actor, Runnable onFinish) {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        streamWorkers.submit(() -> {
            try {
                LlmResponse response = prepared.client().stream(prepared.request(), delta -> send(emitter, "delta", Map.of("text", delta)));
                writeAudit(action, connectionId, prepared, response, actor);
                send(emitter, "done", Map.of("ok", true));
                emitter.complete();
            } catch (LlmException e) {
                audit.onConnection(actor, action, connectionId, "failed status=" + e.upstreamStatus());
                send(emitter, "failed", Map.of("message", e.getMessage()));
                emitter.complete();
            } catch (RuntimeException e) {
                log.warn("AI 流式响应失败", e);
                send(emitter, "failed", Map.of("message", "AI 调用失败：" + e.getMessage()));
                emitter.complete();
            } finally {
                onFinish.run();
            }
        });
        return emitter;
    }

    private void send(SseEmitter emitter, String event, Map<String, Object> payload) {
        try {
            emitter.send(SseEmitter.event().name(event).data(payload, MediaType.APPLICATION_JSON));
        } catch (Exception e) {
            // 浏览器关掉标签页是常态，不值得记成错误；模型那一侧的循环会在下一次 send 时结束。
            log.debug("AI 流式推送中断：{}", e.toString());
            throw new IllegalStateException("客户端已断开", e);
        }
    }

    private void writeAudit(String action, long connectionId, Prepared prepared, LlmResponse response, String actor) {
        // 单轮问答此前只记字符数。字符不是计费单位，也不能和 Agent 那条路的数相加 —— 要卡额度，
        // 两条路就得记同一样东西。
        settings.recordUsage(actor, prepared.settings().model(),
                response.inputTokens(), response.outputTokens(), response.cacheReadTokens());
        audit.onConnection(actor, action, connectionId,
                "sharing=" + prepared.policy().sharing()
                        + " tables=" + prepared.context().tables().size()
                        + " sample=" + prepared.policy().sharing().allowsSample()
                        + " model=" + prepared.settings().model()
                        + " promptChars=" + (prepared.request().systemPrompt() == null ? 0 : prepared.request().systemPrompt().length())
                        + " answerChars=" + (response.text() == null ? 0 : response.text().length())
                        + " inputTokens=" + response.inputTokens()
                        + " outputTokens=" + response.outputTokens()
                        + " cacheReadTokens=" + response.cacheReadTokens());
    }

    /** 方言提示只给类型，不给 JDBC URL —— URL 里常带主机名与库名，没必要发出去。 */
    private static String dialectHint(DbConnection connection) {
        return connection.dbType() == null ? "未知数据库" : connection.dbType();
    }

    private record Prepared(LlmClient client, LlmRequest request, AiSettings settings, AiConnectionPolicy policy, SchemaContext context) {
    }
}
