package com.example.dbadmin.service.ai;

import com.example.dbadmin.model.DbConnection;
import com.example.dbadmin.repo.AuditRepository;
import com.example.dbadmin.service.ConnectionService;
import com.example.dbadmin.service.ai.llm.LlmClient;
import com.example.dbadmin.service.ai.llm.LlmClientFactory;
import com.example.dbadmin.service.ai.llm.LlmException;
import com.example.dbadmin.service.ai.llm.LlmRequest;
import com.example.dbadmin.service.ai.llm.LlmResponse;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

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
    public static final String ACTION_DIAGNOSE = "AI_DIAGNOSE_ERROR";

    public static final List<String> AUDIT_ACTIONS = List.of(ACTION_DIAGNOSE);

    private final AiSettingsService settings;
    private final SchemaContextBuilder contexts;
    private final ConnectionService connections;
    private final LlmClientFactory clients;
    private final AuditRepository audit;
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
            AuditRepository audit
    ) {
        this.settings = settings;
        this.contexts = contexts;
        this.connections = connections;
        this.clients = clients;
        this.audit = audit;
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

    /** 诊断一条执行失败的 SQL。 */
    public String diagnose(long connectionId, String schemaName, String sql, String errorMessage, String actor) {
        Prepared prepared = prepareDiagnose(connectionId, schemaName, sql, errorMessage);
        LlmResponse response = prepared.client().complete(prepared.request());
        writeAudit(ACTION_DIAGNOSE, connectionId, prepared, response, actor);
        return response.text();
    }

    /** 诊断的流式变体：增量直接推给浏览器，用户不用对着转圈等一整段。 */
    public SseEmitter diagnoseStream(long connectionId, String schemaName, String sql, String errorMessage, String actor) {
        Prepared prepared = prepareDiagnose(connectionId, schemaName, sql, errorMessage);
        return stream(prepared, ACTION_DIAGNOSE, connectionId, actor);
    }

    private Prepared prepareDiagnose(long connectionId, String schemaName, String sql, String errorMessage) {
        AiSettings current = settings.requireEnabled();
        AiConnectionPolicy policy = settings.requireSharedConnection(connectionId);
        DbConnection connection = connections.require(connectionId);
        SchemaContext context = contexts.forSql(connectionId, schemaName, sql, policy);
        LlmRequest request = LlmRequest.of(
                AiPromptBuilder.system(context, dialectHint(connection)),
                AiPromptBuilder.diagnose(sql, errorMessage));
        return new Prepared(clients.create(current), request, current, policy, context);
    }

    private SseEmitter stream(Prepared prepared, String action, long connectionId, String actor) {
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
        audit.onConnection(actor, action, connectionId,
                "sharing=" + prepared.policy().sharing()
                        + " tables=" + prepared.context().tables().size()
                        + " sample=" + prepared.policy().sharing().allowsSample()
                        + " model=" + prepared.settings().model()
                        + " promptChars=" + (prepared.request().systemPrompt() == null ? 0 : prepared.request().systemPrompt().length())
                        + " answerChars=" + (response.text() == null ? 0 : response.text().length()));
    }

    /** 方言提示只给类型，不给 JDBC URL —— URL 里常带主机名与库名，没必要发出去。 */
    private static String dialectHint(DbConnection connection) {
        return connection.dbType() == null ? "未知数据库" : connection.dbType();
    }

    private record Prepared(LlmClient client, LlmRequest request, AiSettings settings, AiConnectionPolicy policy, SchemaContext context) {
    }
}
