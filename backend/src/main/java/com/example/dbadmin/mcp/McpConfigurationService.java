package com.example.dbadmin.mcp;

import com.example.dbadmin.config.AppProperties;
import com.example.dbadmin.dto.ApiDtos.ConnectionResponse;
import com.example.dbadmin.dto.McpAdminDtos.McpAgentCreateRequest;
import com.example.dbadmin.dto.McpAdminDtos.McpAgentResponse;
import com.example.dbadmin.dto.McpAdminDtos.McpAgentUpdateRequest;
import com.example.dbadmin.dto.McpAdminDtos.McpConfigResponse;
import com.example.dbadmin.dto.McpAdminDtos.McpConfigUpdateRequest;
import com.example.dbadmin.dto.McpAdminDtos.McpConnectionOption;
import com.example.dbadmin.dto.McpAdminDtos.McpCredentialResponse;
import com.example.dbadmin.dto.McpAdminDtos.McpLimits;
import com.example.dbadmin.mcp.McpRuntimeConfig.Agent;
import com.example.dbadmin.mcp.McpRuntimeConfig.Settings;
import com.example.dbadmin.repo.AuditRepository;
import com.example.dbadmin.repo.McpConfigurationRepository;
import com.example.dbadmin.service.ConnectionService;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

@Service
@DependsOnDatabaseInitialization
public class McpConfigurationService {
    static final Pattern AGENT_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    static final Pattern BCRYPT = Pattern.compile("\\$2[aby]\\$(?:0[4-9]|[12][0-9]|3[01])\\$[./A-Za-z0-9]{53}");
    private static final int MAX_QUERY_ROWS = 5_000;
    private static final int MAX_RESULT_CELLS = 200_000;
    private static final long MAX_RESULT_TEXT_CHARS = 20_000_000;
    private static final int MAX_CELL_TEXT_CHARS = 100_000;
    private static final int MAX_SQL_CHARS = 1_000_000;
    private static final int MAX_QUERY_TIMEOUT_SECONDS = 300;
    private static final int MAX_PAGE_SIZE = 1_000;
    private static final int MAX_SESSION_TTL_MINUTES = 1_440;

    private final McpConfigurationRepository repository;
    private final ConnectionService connections;
    private final AuditRepository audit;
    private final AppProperties properties;
    private final TransactionTemplate transactions;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);
    private final SecureRandom random = new SecureRandom();
    private final AtomicReference<McpRuntimeConfig> current = new AtomicReference<>();

    public McpConfigurationService(
            McpConfigurationRepository repository,
            ConnectionService connections,
            AuditRepository audit,
            AppProperties properties,
            PlatformTransactionManager transactionManager
    ) {
        this.repository = repository;
        this.connections = connections;
        this.audit = audit;
        this.properties = properties;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @PostConstruct
    void initialize() {
        transactions.executeWithoutResult(ignored -> {
            if (repository.findSettings().isPresent()) return;
            AppProperties.Mcp source = properties.getMcp();
            Settings settings = settingsFromProperties(source);
            validateSettings(settings);
            repository.insertSettings(settings);
            repository.replaceOrigins(normalizeOrigins(source.getAllowedOrigins()));
            Set<Long> existingConnectionIds = connections.list().stream().map(ConnectionResponse::id).collect(java.util.stream.Collectors.toSet());
            for (AppProperties.McpAgent agent : source.getAgents()) {
                String agentId = normalizeAgentId(agent.getId());
                String keyHash = requireHash(agentId, agent.getKeyHash());
                Set<Long> connectionIds = new LinkedHashSet<>();
                for (Long connectionId : agent.getConnectionIds()) {
                    if (connectionId == null || connectionId <= 0) {
                        throw new IllegalStateException("MCP agent " + agentId + " 包含无效的 connection-id");
                    }
                    if (existingConnectionIds.contains(connectionId)) connectionIds.add(connectionId);
                }
                repository.insertAgent(agentId, keyHash, true, agent.isAllowProduction(), connectionIds);
            }
        });
        reload();
    }

    public McpRuntimeConfig snapshot() {
        McpRuntimeConfig config = current.get();
        if (config == null) throw new IllegalStateException("MCP 配置尚未初始化");
        return config;
    }

    public McpConfigResponse configResponse() {
        // A connection deletion can cascade its MCP whitelist rows at the database
        // level, so refresh before rendering the administrative view.
        reload();
        McpRuntimeConfig config = snapshot();
        List<McpConnectionOption> connectionOptions = connections.list().stream()
                .map(connection -> new McpConnectionOption(
                        connection.id(), connection.name(), connection.dbType(), connection.environment(), connection.readonly()
                ))
                .toList();
        return new McpConfigResponse(
                config.settings().enabled(),
                "/mcp",
                config.allowedOrigins().stream().sorted().toList(),
                toLimits(config.settings()),
                config.agentList().stream().map(this::toResponse).toList(),
                connectionOptions
        );
    }

    public McpConfigResponse updateConfig(McpConfigUpdateRequest request, String actor) {
        Settings old = snapshot().settings();
        McpLimits limits = request.limits();
        Settings updated = new Settings(
                old.enabled(),
                limits.defaultQueryRows(), limits.maxQueryRows(), limits.maxResultCells(),
                limits.maxResultTextChars(), limits.maxCellTextChars(), limits.maxSqlChars(),
                limits.queryTimeoutSeconds(), limits.metadataPageSize(), limits.maxMetadataPageSize(),
                limits.tablePageSize(), limits.maxTablePageSize(), limits.sessionTtlMinutes()
        );
        validateSettings(updated);
        Set<String> origins = normalizeOrigins(request.allowedOrigins());
        transactions.executeWithoutResult(ignored -> {
            repository.updateSettings(updated);
            repository.replaceOrigins(origins);
        });
        reload();
        audit.log(actor, "MCP_CONFIG_UPDATE", "mcp", "origins=" + origins.size());
        return configResponse();
    }

    public McpConfigResponse updateStatus(boolean enabled, String actor) {
        Settings old = snapshot().settings();
        Settings updated = new Settings(
                enabled, old.defaultQueryRows(), old.maxQueryRows(), old.maxResultCells(), old.maxResultTextChars(),
                old.maxCellTextChars(), old.maxSqlChars(), old.queryTimeoutSeconds(), old.metadataPageSize(),
                old.maxMetadataPageSize(), old.tablePageSize(), old.maxTablePageSize(), old.sessionTtlMinutes()
        );
        transactions.executeWithoutResult(ignored -> repository.updateSettings(updated));
        reload();
        audit.log(actor, enabled ? "MCP_ENABLE" : "MCP_DISABLE", "mcp", "enabled=" + enabled);
        return configResponse();
    }

    public McpCredentialResponse createAgent(McpAgentCreateRequest request, String actor) {
        String agentId = normalizeAgentId(request.agentId());
        if (snapshot().agents().containsKey(agentId)) throw new IllegalArgumentException("MCP Agent ID 已存在");
        Set<Long> connectionIds = validateConnections(request.connectionIds(), request.allowProduction());
        String secret = generateSecret();
        String hash = encoder.encode(secret);
        long id = transactions.execute(status -> repository.insertAgent(agentId, hash, true, request.allowProduction(), connectionIds));
        reload();
        Agent agent = requireAgent(id);
        audit.log(actor, "MCP_AGENT_CREATE", "mcp-agent:" + agentId, "connections=" + connectionIds.size());
        return new McpCredentialResponse(toResponse(agent), agentId + "." + secret);
    }

    public McpAgentResponse updateAgent(long id, McpAgentUpdateRequest request, String actor) {
        Agent existing = requireAgent(id);
        Set<Long> connectionIds = validateConnections(request.connectionIds(), request.allowProduction());
        transactions.executeWithoutResult(ignored -> repository.updateAgent(id, request.enabled(), request.allowProduction(), connectionIds));
        reload();
        audit.log(actor, "MCP_AGENT_UPDATE", "mcp-agent:" + existing.agentId(),
                "enabled=" + request.enabled() + ";connections=" + connectionIds.size());
        return toResponse(requireAgent(id));
    }

    public McpCredentialResponse rotateAgentKey(long id, String actor) {
        Agent existing = requireAgent(id);
        String secret = generateSecret();
        String hash = encoder.encode(secret);
        transactions.executeWithoutResult(ignored -> repository.updateAgentKey(id, hash));
        reload();
        audit.log(actor, "MCP_AGENT_ROTATE_KEY", "mcp-agent:" + existing.agentId(), "rotated=true");
        return new McpCredentialResponse(toResponse(requireAgent(id)), existing.agentId() + "." + secret);
    }

    public String deleteAgent(long id, String actor) {
        Agent existing = requireAgent(id);
        transactions.executeWithoutResult(ignored -> repository.deleteAgent(id));
        reload();
        audit.log(actor, "MCP_AGENT_DELETE", "mcp-agent:" + existing.agentId(), "deleted=true");
        return existing.agentId();
    }

    private void reload() {
        Settings settings = repository.findSettings().orElseThrow(() -> new IllegalStateException("MCP 配置不存在"));
        Map<String, Agent> agents = new LinkedHashMap<>();
        for (Agent agent : repository.findAgents()) {
            Agent previous = agents.putIfAbsent(agent.agentId(), agent);
            if (previous != null) throw new IllegalStateException("MCP Agent ID 重复：" + agent.agentId());
        }
        current.set(new McpRuntimeConfig(settings, repository.findOrigins(), agents));
    }

    private Set<Long> validateConnections(List<Long> requested, boolean allowProduction) {
        Set<Long> ids = new LinkedHashSet<>(requested == null ? List.of() : requested);
        if (ids.isEmpty()) throw new IllegalArgumentException("MCP Agent 至少需要授权一个连接");
        Map<Long, ConnectionResponse> available = connections.list().stream()
                .collect(java.util.stream.Collectors.toMap(ConnectionResponse::id, connection -> connection));
        for (Long id : ids) {
            ConnectionResponse connection = id == null ? null : available.get(id);
            if (connection == null) throw new IllegalArgumentException("连接不存在：" + id);
            if ("prod".equalsIgnoreCase(connection.environment()) && !allowProduction) {
                throw new IllegalArgumentException("授权生产连接必须开启生产环境访问权限");
            }
        }
        return ids;
    }

    private Agent requireAgent(long id) {
        return snapshot().agents().values().stream().filter(agent -> agent.id() == id).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("MCP Agent 不存在"));
    }

    private String normalizeAgentId(String value) {
        String agentId = value == null ? "" : value.trim();
        if (!AGENT_ID.matcher(agentId).matches()) {
            throw new IllegalArgumentException("Agent ID 只能包含字母、数字、下划线或短横线，长度为 1-64");
        }
        return agentId;
    }

    private String requireHash(String agentId, String value) {
        String hash = value == null ? "" : value.trim();
        if (!BCRYPT.matcher(hash).matches()) {
            throw new IllegalStateException("MCP agent " + agentId + " 的 key-hash 不是有效的 BCrypt 哈希");
        }
        return hash;
    }

    private Set<String> normalizeOrigins(List<String> values) {
        Set<String> origins = new LinkedHashSet<>();
        for (String value : values == null ? List.<String>of() : values) {
            String raw = value == null ? "" : value.trim();
            if (raw.isEmpty()) continue;
            try {
                URI uri = URI.create(raw);
                String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
                String path = uri.getRawPath();
                if (!("http".equals(scheme) || "https".equals(scheme)) || uri.getHost() == null
                        || uri.getRawUserInfo() != null || path != null && !path.isEmpty() && !"/".equals(path)
                        || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                    throw new IllegalArgumentException("Origin 必须是仅包含协议、主机和可选端口的 http/https 地址：" + raw);
                }
                String normalized = scheme + "://" + uri.getHost().toLowerCase(Locale.ROOT)
                        + (uri.getPort() < 0 ? "" : ":" + uri.getPort());
                origins.add(normalized);
            } catch (IllegalArgumentException error) {
                if (error.getMessage() != null && error.getMessage().startsWith("Origin 必须")) throw error;
                throw new IllegalArgumentException("无效的 Origin：" + raw);
            }
        }
        return origins;
    }

    private void validateSettings(Settings settings) {
        positive(settings.defaultQueryRows(), "查询默认行数");
        between(settings.maxQueryRows(), 1, MAX_QUERY_ROWS, "查询最大行数");
        if (settings.defaultQueryRows() > settings.maxQueryRows()) throw new IllegalArgumentException("查询默认行数不能大于最大行数");
        between(settings.maxResultCells(), 1, MAX_RESULT_CELLS, "结果单元格上限");
        between(settings.maxResultTextChars(), 1, MAX_RESULT_TEXT_CHARS, "结果文本总量上限");
        between(settings.maxCellTextChars(), 1, MAX_CELL_TEXT_CHARS, "单元文本上限");
        if (settings.maxCellTextChars() > settings.maxResultTextChars()) throw new IllegalArgumentException("单元文本上限不能大于结果文本总量上限");
        between(settings.maxSqlChars(), 1, MAX_SQL_CHARS, "SQL 长度上限");
        between(settings.queryTimeoutSeconds(), 1, MAX_QUERY_TIMEOUT_SECONDS, "查询超时");
        positive(settings.metadataPageSize(), "元数据默认分页");
        between(settings.maxMetadataPageSize(), 1, MAX_PAGE_SIZE, "元数据最大分页");
        if (settings.metadataPageSize() > settings.maxMetadataPageSize()) throw new IllegalArgumentException("元数据默认分页不能大于最大分页");
        positive(settings.tablePageSize(), "表数据默认分页");
        between(settings.maxTablePageSize(), 1, MAX_PAGE_SIZE, "表数据最大分页");
        if (settings.tablePageSize() > settings.maxTablePageSize()) throw new IllegalArgumentException("表数据默认分页不能大于最大分页");
        between(settings.sessionTtlMinutes(), 1, MAX_SESSION_TTL_MINUTES, "Session TTL");
    }

    private void positive(long value, String name) {
        if (value <= 0) throw new IllegalArgumentException(name + "必须大于 0");
    }

    private void between(long value, long minimum, long maximum, String name) {
        if (value < minimum || value > maximum) throw new IllegalArgumentException(name + "必须在 " + minimum + " 到 " + maximum + " 之间");
    }

    private Settings settingsFromProperties(AppProperties.Mcp source) {
        return new Settings(
                source.isEnabled(), source.getDefaultQueryRows(), source.getMaxQueryRows(), source.getMaxResultCells(),
                source.getMaxResultTextChars(), source.getMaxCellTextChars(), source.getMaxSqlChars(),
                source.getQueryTimeoutSeconds(), source.getMetadataPageSize(), source.getMaxMetadataPageSize(),
                source.getTablePageSize(), source.getMaxTablePageSize(), source.getSessionTtlMinutes()
        );
    }

    private McpLimits toLimits(Settings settings) {
        return new McpLimits(
                settings.defaultQueryRows(), settings.maxQueryRows(), settings.maxResultCells(),
                settings.maxResultTextChars(), settings.maxCellTextChars(), settings.maxSqlChars(),
                settings.queryTimeoutSeconds(), settings.metadataPageSize(), settings.maxMetadataPageSize(),
                settings.tablePageSize(), settings.maxTablePageSize(), settings.sessionTtlMinutes()
        );
    }

    private McpAgentResponse toResponse(Agent agent) {
        return new McpAgentResponse(
                agent.id(), agent.agentId(), agent.enabled(), agent.allowProduction(),
                agent.connectionIds().stream().sorted().toList(), agent.createdAt(), agent.updatedAt()
        );
    }

    private String generateSecret() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
