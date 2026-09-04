package com.example.dbadmin.service.ai;

import com.example.dbadmin.api.ApiProblemException;
import com.example.dbadmin.dto.AiDtos.AiConnectionPolicyRequest;
import com.example.dbadmin.dto.AiDtos.AiConnectionPolicyResponse;
import com.example.dbadmin.dto.AiDtos.AiProbeResponse;
import com.example.dbadmin.dto.AiDtos.AiSettingsResponse;
import com.example.dbadmin.dto.AiDtos.AiSettingsUpdateRequest;
import com.example.dbadmin.dto.ApiDtos.ConnectionResponse;
import com.example.dbadmin.repo.AiSettingsRepository;
import com.example.dbadmin.repo.AuditRepository;
import com.example.dbadmin.service.ConnectionService;
import com.example.dbadmin.service.CryptoService;
import com.example.dbadmin.service.ai.llm.LlmClient;
import com.example.dbadmin.service.ai.llm.LlmClientFactory;
import com.example.dbadmin.service.ai.llm.LlmException;
import com.example.dbadmin.service.ai.llm.LlmRequest;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * AI 配置与连接共享策略。
 *
 * <p>这是 AI 功能的唯一开关面：{@link #requireEnabled()} 与 {@link #requireSharedConnection(long)}
 * 是后续所有 AI 接口的前置闸门，任何绕过它们取结构的代码都会破坏对用户的隐私承诺。</p>
 */
@Service
@DependsOnDatabaseInitialization
public class AiSettingsService {
    private final AiSettingsRepository repository;
    private final ConnectionService connections;
    private final AuditRepository audit;
    private final CryptoService crypto;
    private final LlmClientFactory clients;
    private final AtomicReference<AiSettings> current = new AtomicReference<>(AiSettings.disabled());

    public AiSettingsService(
            AiSettingsRepository repository,
            ConnectionService connections,
            AuditRepository audit,
            CryptoService crypto,
            LlmClientFactory clients
    ) {
        this.repository = repository;
        this.connections = connections;
        this.audit = audit;
        this.crypto = crypto;
        this.clients = clients;
    }

    @PostConstruct
    void initialize() {
        AiSettings settings = repository.findSettings().orElse(null);
        if (settings == null) {
            settings = AiSettings.disabled();
            repository.insertSettings(settings);
        }
        current.set(settings);
    }

    public AiSettings snapshot() {
        return current.get();
    }

    public AiSettingsResponse settingsResponse() {
        return AiSettingsProfile.summarize(snapshot());
    }

    public AiSettingsResponse updateSettings(AiSettingsUpdateRequest request, String actor) {
        AiSettings settings = AiSettingsProfile.toSettings(request, snapshot(), crypto::encrypt);
        repository.updateSettings(settings);
        current.set(settings);
        audit.global(actor, "AI_SETTINGS_UPDATE", "ai",
                "enabled=" + settings.enabled()
                        + " provider=" + settings.provider()
                        + " model=" + settings.model()
                        + " effort=" + settings.effort()
                        + " apiKey=" + (settings.hasApiKey() ? "configured" : "empty"));
        return AiSettingsProfile.summarize(settings);
    }

    /**
     * 连通性测试。
     *
     * <p>发一条最短的请求，只为确认「地址、Key、模型名」三者能走通；失败时把上游原因带回
     * 界面，而不是让管理员对着一个红叉猜。</p>
     */
    public AiProbeResponse test(String actor) {
        AiSettings settings = snapshot();
        if (settings.provider() == AiProvider.ANTHROPIC && !settings.hasApiKey()) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, "AI_DISABLED", "请先填写并保存 API Key。");
        }
        LlmClient client = clients.create(settings);
        long started = System.nanoTime();
        try {
            client.complete(new LlmRequest(null, "回答一个字：好", 16));
            long millis = (System.nanoTime() - started) / 1_000_000;
            audit.global(actor, "AI_SETTINGS_TEST", "ai", "provider=" + settings.provider() + " ok=true");
            return new AiProbeResponse(true, settings.provider().name(), settings.model(), millis, "连接正常。");
        } catch (LlmException e) {
            long millis = (System.nanoTime() - started) / 1_000_000;
            audit.global(actor, "AI_SETTINGS_TEST", "ai",
                    "provider=" + settings.provider() + " ok=false status=" + e.upstreamStatus());
            return new AiProbeResponse(false, settings.provider().name(), settings.model(), millis, e.getMessage());
        }
    }

    /** 所有连接的策略；没配置过的连接也列出来，档位是 NONE。 */
    public List<AiConnectionPolicyResponse> policies() {
        Map<Long, AiConnectionPolicy> stored = repository.findPolicies().stream()
                .collect(Collectors.toMap(AiConnectionPolicy::connectionId, Function.identity()));
        return connections.list().stream().map(connection -> {
            boolean production = isProduction(connection.environment());
            AiConnectionPolicy policy = AiSharingRules.effective(stored.get(connection.id()), production);
            AiConnectionPolicy effective = policy == null ? AiConnectionPolicy.none(connection.id()) : policy;
            return new AiConnectionPolicyResponse(
                    connection.id(),
                    connection.name(),
                    connection.dbType(),
                    connection.environment(),
                    production,
                    effective.sharing().name(),
                    effective.sampleRowLimit()
            );
        }).toList();
    }

    public AiConnectionPolicyResponse updatePolicy(long connectionId, AiConnectionPolicyRequest request, String actor) {
        ConnectionResponse connection = connections.list().stream()
                .filter(item -> item.id() == connectionId)
                .findFirst()
                .orElseThrow(() -> new ApiProblemException(HttpStatus.NOT_FOUND, "CONNECTION_NOT_FOUND", "连接不存在。"));
        boolean production = isProduction(connection.environment());
        AiConnectionPolicy policy = AiSharingRules.normalize(connectionId, request.sharing(), request.sampleRowLimit(), production);
        repository.upsertPolicy(policy);
        audit.onConnection(actor, "AI_POLICY_UPDATE", connectionId,
                "sharing=" + policy.sharing() + " sampleRows=" + policy.sampleRowLimit());
        return new AiConnectionPolicyResponse(
                connectionId,
                connection.name(),
                connection.dbType(),
                connection.environment(),
                production,
                policy.sharing().name(),
                policy.sampleRowLimit()
        );
    }

    /**
     * AI 功能的总闸门。后续每个 AI 接口的第一行都该是它。
     */
    public AiSettings requireEnabled() {
        AiSettings settings = snapshot();
        if (!settings.enabled()) {
            throw new ApiProblemException(HttpStatus.CONFLICT, "AI_DISABLED",
                    "AI 功能尚未启用，请联系管理员在「AI 助手」设置中开启。");
        }
        return settings;
    }

    /**
     * 连接级闸门：返回这条连接允许发送的范围，未授权直接拒绝。
     */
    public AiConnectionPolicy requireSharedConnection(long connectionId) {
        ConnectionResponse connection = connections.list().stream()
                .filter(item -> item.id() == connectionId)
                .findFirst()
                .orElseThrow(() -> new ApiProblemException(HttpStatus.NOT_FOUND, "CONNECTION_NOT_FOUND", "连接不存在。"));
        AiConnectionPolicy stored = repository.findPolicy(connectionId).orElse(null);
        AiConnectionPolicy policy = AiSharingRules.effective(stored, isProduction(connection.environment()));
        if (policy == null || !policy.sharing().allowsStructure()) {
            throw new ApiProblemException(HttpStatus.FORBIDDEN, "AI_CONNECTION_NOT_SHARED",
                    "这条连接尚未授权给 AI 使用，请在「AI 助手」设置中为它选择共享档位。",
                    Map.of("connectionId", connectionId));
        }
        return policy;
    }

    private static boolean isProduction(String environment) {
        return "prod".equalsIgnoreCase(environment);
    }
}
