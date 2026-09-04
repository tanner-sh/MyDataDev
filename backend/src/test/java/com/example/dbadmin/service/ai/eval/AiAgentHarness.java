package com.example.dbadmin.service.ai.eval;

import com.example.dbadmin.config.AppProperties;
import com.example.dbadmin.core.DialectRegistry;
import com.example.dbadmin.dto.AiDtos.AiChatMessageResponse;
import com.example.dbadmin.dto.AiDtos.AiChatRequest;
import com.example.dbadmin.model.DbConnection;
import com.example.dbadmin.repo.AuditRepository;
import com.example.dbadmin.service.ConnectionService;
import com.example.dbadmin.service.ExecutionGuard;
import com.example.dbadmin.service.MetadataCacheService;
import com.example.dbadmin.service.MetadataService;
import com.example.dbadmin.service.SqlScriptSplitter;
import com.example.dbadmin.service.SqlStatementClassifier;
import com.example.dbadmin.service.ai.AiAgentCoordinator;
import com.example.dbadmin.service.ai.AiAgentMetrics;
import com.example.dbadmin.service.ai.AiConnectionPolicy;
import com.example.dbadmin.service.ai.AiConversationStore;
import com.example.dbadmin.service.ai.AiGlossaryService;
import com.example.dbadmin.service.ai.AiSchemaSharing;
import com.example.dbadmin.service.ai.AiSchemaTools;
import com.example.dbadmin.service.ai.AiSettings;
import com.example.dbadmin.service.ai.AiSettingsService;
import com.example.dbadmin.service.ai.AiSqlAgentService;
import com.example.dbadmin.service.ai.AiSqlValidationService;
import com.example.dbadmin.service.ai.llm.LlmClient;
import com.example.dbadmin.service.ai.llm.LlmClientFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.ObjectProvider;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 在一个固定的 H2 库上跑真实的 {@link AiSqlAgentService}，只替换掉配置、鉴权和模型这三处。
 *
 * <p>元数据工具、编译校验、会话、并发闸门、审计埋点都是生产代码本身，所以评测量的是真东西，
 * 不是一套平行实现。</p>
 *
 * <p>完成信号取自审计：每次请求无论成功、失败还是取消都会在 finally 里写一条
 * {@code AI_AGENT_CHAT}，那条 detail 又正好带着轮次、工具次数和 token。这里顺带也就钉住了
 * 「审计一定会写」这条约定 —— 它要是漏了，评测会直接超时。</p>
 */
public final class AiAgentHarness implements AutoCloseable {
    public static final long CONNECTION_ID = 4_2L;
    public static final String SCHEMA = "PUBLIC";
    private static final String OWNER = "user:eval";

    private final String jdbcUrl;
    private final Connection keepAlive;
    private final AiSqlAgentService agent;
    private final AiConversationStore conversations;
    private final AuditRepository audit;
    private final MeterRegistry registry = new SimpleMeterRegistry();

    public AiAgentHarness(LlmClient client, List<com.example.dbadmin.service.ai.AiBusinessTerm> glossaryTerms) throws Exception {
        this(client, glossaryTerms, "eval-model");
    }

    public AiAgentHarness(
            LlmClient client,
            List<com.example.dbadmin.service.ai.AiBusinessTerm> glossaryTerms,
            String modelLabel
    ) throws Exception {
        jdbcUrl = "jdbc:h2:mem:ai-eval-" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        keepAlive = DriverManager.getConnection(jdbcUrl, "sa", "");
        loadSchema();

        ConnectionService connections = mock(ConnectionService.class);
        DbConnection connection = new DbConnection(CONNECTION_ID, "评测库", "h2", jdbcUrl, "sa", "",
                "dev", false, Instant.now(), Instant.now());
        when(connections.require(anyLong())).thenReturn(connection);
        when(connections.open(anyLong())).thenAnswer(ignored -> DriverManager.getConnection(jdbcUrl, "sa", ""));
        when(connections.open(anyLong(), nullable(String.class)))
                .thenAnswer(ignored -> DriverManager.getConnection(jdbcUrl, "sa", ""));

        audit = mock(AuditRepository.class);
        MetadataCacheService metadataCache = new MetadataCacheService();
        MetadataService metadata = new MetadataService(
                connections, new DialectRegistry(), audit, metadataCache, new ExecutionGuard());
        AiGlossaryService glossary = mock(AiGlossaryService.class);
        when(glossary.terms(anyLong())).thenReturn(glossaryTerms);

        AppProperties properties = new AppProperties();
        AiSqlValidationService validator = new AiSqlValidationService(connections, new DialectRegistry(),
                new SqlScriptSplitter(), new SqlStatementClassifier(), properties);
        AiSchemaTools tools = new AiSchemaTools(connections, new DialectRegistry(), metadata, metadataCache,
                glossary, validator, new ObjectMapper());

        AiSettingsService settings = mock(AiSettingsService.class);
        when(settings.requireEnabled()).thenReturn(new AiSettings(true,
                com.example.dbadmin.service.ai.AiProvider.ANTHROPIC, null, modelLabel, null,
                com.example.dbadmin.service.ai.AiEffort.HIGH));
        when(settings.requireSharedConnection(anyLong()))
                .thenReturn(new AiConnectionPolicy(CONNECTION_ID, AiSchemaSharing.STRUCTURE, 0));

        LlmClientFactory clients = mock(LlmClientFactory.class);
        when(clients.create(any())).thenReturn(client);

        conversations = new AiConversationStore(properties);
        agent = new AiSqlAgentService(settings, connections, clients, tools, audit, new SqlScriptSplitter(),
                new SqlStatementClassifier(), validator, conversations, metadataCache,
                new AiAgentCoordinator(properties, provider(registry)), new AiAgentMetrics(provider(registry)),
                properties);
    }

    /** 问一句话，等它跑完，把回答、结构依据和这次的运行数据一起带回来。 */
    public Run ask(String question) throws Exception {
        CountDownLatch finished = new CountDownLatch(1);
        List<String> details = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        doAnswer(invocation -> {
            if (AiSqlAgentService.ACTION_CHAT.equals(invocation.getArgument(1))) {
                details.add(invocation.getArgument(3));
                finished.countDown();
            }
            return null;
        }).when(audit).onConnection(nullable(String.class), anyString(), anyLong(), anyString());

        long started = System.nanoTime();
        agent.chatStream(new AiChatRequest(CONNECTION_ID, SCHEMA, null, question, null), "eval", OWNER);
        if (!finished.await(5, TimeUnit.MINUTES)) throw new IllegalStateException("Agent 请求超时，且没有写审计");
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        Map<String, String> stats = parseAuditDetail(details.get(details.size() - 1));
        AiChatMessageResponse answer = lastAnswer(stats.get("conversation"));
        return new Run(
                answer == null ? "" : answer.text(),
                answer != null && answer.grounding() != null && answer.grounding().validated(),
                stats,
                elapsed);
    }

    private AiChatMessageResponse lastAnswer(String conversationId) {
        if (conversationId == null) return null;
        List<AiChatMessageResponse> messages =
                conversations.get(conversationId, OWNER, CONNECTION_ID, SCHEMA).messages();
        for (int index = messages.size() - 1; index >= 0; index--) {
            if ("ASSISTANT".equals(messages.get(index).role())) return messages.get(index);
        }
        return null;
    }

    /** 审计 detail 是 {@code key=value} 空格分隔的，这里按同一个约定读回来。 */
    static Map<String, String> parseAuditDetail(String detail) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String token : detail.split(" ")) {
            int equals = token.indexOf('=');
            if (equals > 0) values.put(token.substring(0, equals), token.substring(equals + 1));
        }
        return values;
    }

    private void loadSchema() throws Exception {
        try (InputStream stream = AiAgentHarness.class.getResourceAsStream("/ai-eval-schema.sql")) {
            if (stream == null) throw new IllegalStateException("找不到 ai-eval-schema.sql");
            String script = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            try (Statement statement = keepAlive.createStatement()) {
                for (String sql : script.split(";")) {
                    String trimmed = stripComments(sql).trim();
                    if (!trimmed.isEmpty()) statement.execute(trimmed);
                }
            }
        }
    }

    private static String stripComments(String sql) {
        StringBuilder result = new StringBuilder();
        for (String line : sql.split("\n")) {
            if (!line.trim().startsWith("--")) result.append(line).append('\n');
        }
        return result.toString();
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<MeterRegistry> provider(MeterRegistry registry) {
        ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(registry);
        return provider;
    }

    @Override
    public void close() throws Exception {
        keepAlive.close();
    }

    /**
     * @param stats 审计 detail 解析出的运行数据：outcome、rounds、tools、objects、各类 token
     */
    public record Run(String answer, boolean validated, Map<String, String> stats, Duration elapsed) {
        public int number(String key) {
            String value = stats.get(key);
            try {
                return value == null ? 0 : Integer.parseInt(value);
            } catch (NumberFormatException e) {
                return 0;
            }
        }

        public String outcome() {
            return stats.getOrDefault("outcome", "unknown");
        }
    }
}
