package com.example.dbadmin.mcp;

import com.example.dbadmin.config.AppProperties;
import com.example.dbadmin.dto.ApiDtos.ConnectionResponse;
import com.example.dbadmin.dto.ConfigArchiveDtos.ArchiveMcpImportResult;
import com.example.dbadmin.dto.ConfigArchiveDtos.ArchivedMcpAgent;
import com.example.dbadmin.dto.ConfigArchiveDtos.ArchivedMcpConfig;
import com.example.dbadmin.dto.ConfigArchiveDtos.ArchivedMcpGrant;
import com.example.dbadmin.dto.McpAdminDtos.McpAgentCreateRequest;
import com.example.dbadmin.dto.McpAdminDtos.McpAgentGrant;
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
import java.util.ArrayList;
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
    /** 与全局 app.sql.timeout-seconds 的默认值一致：db_execute 原来就用那个值。 */
    private static final int DEFAULT_EXECUTE_TIMEOUT_SECONDS = 60;
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
                // 配置文件里的 Agent 没有档位字段，一律按只读导入；要写权限得在界面上显式授予。
                Map<Long, McpAccessLevel> grants = new LinkedHashMap<>();
                for (Long connectionId : connectionIds) grants.put(connectionId, McpAccessLevel.READ_ONLY);
                repository.insertAgent(agentId, keyHash, true, agent.isAllowProduction(), grants);
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
                limits.tablePageSize(), limits.maxTablePageSize(), limits.sessionTtlMinutes(),
                limits.maxScriptsPerConnection() == null ? old.maxScriptsPerConnection() : limits.maxScriptsPerConnection(),
                limits.executeTimeoutSeconds() == null ? old.executeTimeoutSeconds() : limits.executeTimeoutSeconds()
        );
        validateSettings(updated);
        Set<String> origins = normalizeOrigins(request.allowedOrigins());
        transactions.executeWithoutResult(ignored -> {
            repository.updateSettings(updated);
            repository.replaceOrigins(origins);
        });
        reload();
        audit.global(actor, "MCP_CONFIG_UPDATE", "mcp", "origins=" + origins.size());
        return configResponse();
    }

    public McpConfigResponse updateStatus(boolean enabled, String actor) {
        Settings old = snapshot().settings();
        Settings updated = new Settings(
                enabled, old.defaultQueryRows(), old.maxQueryRows(), old.maxResultCells(), old.maxResultTextChars(),
                old.maxCellTextChars(), old.maxSqlChars(), old.queryTimeoutSeconds(), old.metadataPageSize(),
                old.maxMetadataPageSize(), old.tablePageSize(), old.maxTablePageSize(), old.sessionTtlMinutes(), old.maxScriptsPerConnection(),
                old.executeTimeoutSeconds()
        );
        transactions.executeWithoutResult(ignored -> repository.updateSettings(updated));
        reload();
        audit.global(actor, enabled ? "MCP_ENABLE" : "MCP_DISABLE", "mcp", "enabled=" + enabled);
        return configResponse();
    }

    public McpCredentialResponse createAgent(McpAgentCreateRequest request, String actor) {
        String agentId = normalizeAgentId(request.agentId());
        if (snapshot().agents().containsKey(agentId)) throw new IllegalArgumentException("MCP Agent ID 已存在");
        Map<Long, McpAccessLevel> grants = validateGrants(request.grants(), request.allowProduction());
        String secret = generateSecret();
        String hash = encoder.encode(secret);
        long id = transactions.execute(status -> repository.insertAgent(agentId, hash, true, request.allowProduction(), grants));
        reload();
        Agent agent = requireAgent(id);
        audit.global(actor, "MCP_AGENT_CREATE", "mcp-agent:" + agentId,
                "connections=" + grants.size() + ";" + grantSummary(grants));
        return new McpCredentialResponse(toResponse(agent), agentId + "." + secret);
    }

    public McpAgentResponse updateAgent(long id, McpAgentUpdateRequest request, String actor) {
        Agent existing = requireAgent(id);
        Map<Long, McpAccessLevel> grants = validateGrants(request.grants(), request.allowProduction());
        transactions.executeWithoutResult(ignored -> repository.updateAgent(id, request.enabled(), request.allowProduction(), grants));
        reload();
        // 档位变化是安全事件，审计里必须能看出改成了什么，而不只是「改了几条连接」。
        audit.global(actor, "MCP_AGENT_UPDATE", "mcp-agent:" + existing.agentId(),
                "enabled=" + request.enabled() + ";connections=" + grants.size()
                        + ";" + grantSummary(grants) + ";before=" + grantSummary(existing.connectionLevels()));
        return toResponse(requireAgent(id));
    }

    public McpCredentialResponse rotateAgentKey(long id, String actor) {
        Agent existing = requireAgent(id);
        String secret = generateSecret();
        String hash = encoder.encode(secret);
        transactions.executeWithoutResult(ignored -> repository.updateAgentKey(id, hash));
        reload();
        audit.global(actor, "MCP_AGENT_ROTATE_KEY", "mcp-agent:" + existing.agentId(), "rotated=true");
        return new McpCredentialResponse(toResponse(requireAgent(id)), existing.agentId() + "." + secret);
    }

    public String deleteAgent(long id, String actor) {
        Agent existing = requireAgent(id);
        transactions.executeWithoutResult(ignored -> repository.deleteAgent(id));
        reload();
        audit.global(actor, "MCP_AGENT_DELETE", "mcp-agent:" + existing.agentId(), "deleted=true");
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

    /**
     * 校验连接授权与档位。
     *
     * <p>只读连接上给写档位是没有意义的：{@code ExecutionGuard} 会在执行时一律拒绝，让它保存成功
     * 只会让管理员以为授权生效了。生产连接上的写档位则要求同时开启生产环境访问 —— 这是管理员的
     * 一次显式决定，Agent 自己拿不到。</p>
     */
    private Map<Long, McpAccessLevel> validateGrants(List<McpAgentGrant> requested, boolean allowProduction) {
        if (requested == null || requested.isEmpty()) throw new IllegalArgumentException("MCP Agent 至少需要授权一个连接");
        Map<Long, ConnectionResponse> available = connections.list().stream()
                .collect(java.util.stream.Collectors.toMap(ConnectionResponse::id, connection -> connection));
        Map<Long, McpAccessLevel> grants = new LinkedHashMap<>();
        for (McpAgentGrant grant : requested) {
            Long id = grant == null ? null : grant.connectionId();
            ConnectionResponse connection = id == null ? null : available.get(id);
            if (connection == null) throw new IllegalArgumentException("连接不存在：" + id);
            McpAccessLevel level = McpAccessLevel.parse(grant.accessLevel());
            String problem = grantProblem(connection, level, allowProduction);
            if (problem != null) throw new IllegalArgumentException(problem);
            if (grants.putIfAbsent(id, level) != null) {
                throw new IllegalArgumentException("连接重复授权：" + connection.name());
            }
        }
        return grants;
    }

    /** 一条授权本身是否成立；返回 null 表示成立。界面保存与归档导入共用这份判定。 */
    private static String grantProblem(ConnectionResponse connection, McpAccessLevel level, boolean allowProduction) {
        boolean production = "prod".equalsIgnoreCase(connection.environment());
        if (production && !allowProduction) return "授权生产连接必须开启生产环境访问权限";
        if (level != McpAccessLevel.READ_ONLY && connection.readonly()) {
            return "连接「" + connection.name() + "」是只读连接，只能授予只读档位。";
        }
        return null;
    }

    /**
     * 把 MCP 配置打进连接归档。
     *
     * <p>授权只保留 {@code connectionNames} 里的连接（即这次一起导出的那些）：连接没进归档，
     * 目标端就不会有它，按名字带过去只会在导入时被丢掉，或者更糟 —— 挂到目标端一条恰好同名的连接上。</p>
     *
     * @param connectionNames 本次导出的连接 id → 连接名
     */
    public ArchivedMcpConfig exportForArchive(Map<Long, String> connectionNames) {
        McpRuntimeConfig config = snapshot();
        List<ArchivedMcpAgent> agents = new ArrayList<>();
        for (Agent agent : config.agentList()) {
            List<ArchivedMcpGrant> grants = agent.connectionLevels().entrySet().stream()
                    .filter(entry -> connectionNames.containsKey(entry.getKey()))
                    .sorted(Map.Entry.comparingByKey())
                    .map(entry -> new ArchivedMcpGrant(connectionNames.get(entry.getKey()), entry.getValue().name()))
                    .toList();
            agents.add(new ArchivedMcpAgent(
                    agent.agentId(), agent.keyHash(), agent.enabled(), agent.allowProduction(), grants));
        }
        return new ArchivedMcpConfig(config.settings().enabled(), toLimits(config.settings()), agents);
    }

    /**
     * 从连接归档导入 MCP 配置。
     *
     * <p>四条规则：</p>
     * <ul>
     *   <li><b>Key 原样保留</b>：写入的是归档里的 BCrypt 哈希，AI 客户端里的 Key 不用改。</li>
     *   <li><b>同名 Agent 跳过</b>：覆盖会让本机那个 Agent 手里的 Key 立刻失效。</li>
     *   <li><b>授权只挂到这次导入新建的连接上</b>（{@code importedConnections}）。因重名被跳过的连接，
     *       本机那条同名连接不一定是同一个库，挂上去等于给 Agent 开一条没人确认过的连接。</li>
     *   <li><b>全局设置只在本机还没有任何 Agent 时套用</b>：已经在用 MCP 的装机，上限是那边的人定的。</li>
     * </ul>
     *
     * <p>逐条跳过而不是整体失败：连接此时已经导入完了，因为一个 Agent 的问题让整次导入报错，
     * 用户看到的会是「失败」，而库里其实已经多了一批连接。</p>
     *
     * @param importedConnections 归档里的连接名 → 本次导入新建的连接 id
     */
    public ArchiveMcpImportResult importFromArchive(
            ArchivedMcpConfig archived, Map<String, Long> importedConnections, String actor
    ) {
        McpRuntimeConfig before = snapshot();
        Map<Long, ConnectionResponse> available = connections.list().stream()
                .collect(java.util.stream.Collectors.toMap(ConnectionResponse::id, connection -> connection));

        Settings settings = null;
        String settingsNote;
        if (!before.agents().isEmpty()) {
            settingsNote = "本机已有 MCP Agent，保留本机的全局设置";
        } else if (archived.limits() == null) {
            settingsNote = "归档里没有全局设置";
        } else {
            Settings candidate = fromLimits(archived.enabled(), archived.limits(), before.settings());
            try {
                validateSettings(candidate);
                settings = candidate;
                settingsNote = archived.enabled() ? "已套用归档里的全局设置，MCP 已启用" : "已套用归档里的全局设置，MCP 保持关闭";
            } catch (IllegalArgumentException invalid) {
                settingsNote = "归档里的全局设置无效（" + invalid.getMessage() + "），保留本机设置";
            }
        }

        List<String> imported = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<String> dropped = new ArrayList<>();
        List<PendingAgent> pending = new ArrayList<>();
        Set<String> taken = new LinkedHashSet<>(before.agents().keySet());
        for (ArchivedMcpAgent agent : archived.agents() == null ? List.<ArchivedMcpAgent>of() : archived.agents()) {
            String agentId = agent == null || agent.agentId() == null ? "" : agent.agentId().trim();
            if (!AGENT_ID.matcher(agentId).matches()) {
                skipped.add((agentId.isEmpty() ? "(未命名)" : agentId) + "：Agent ID 不合法");
                continue;
            }
            if (taken.contains(agentId)) {
                skipped.add(agentId + "：本机已有同名 Agent，保留本机的");
                continue;
            }
            String hash = agent.keyHash() == null ? "" : agent.keyHash().trim();
            if (!BCRYPT.matcher(hash).matches()) {
                skipped.add(agentId + "：Key 哈希无效");
                continue;
            }
            Map<Long, McpAccessLevel> grants = new LinkedHashMap<>();
            for (ArchivedMcpGrant grant : agent.grants() == null ? List.<ArchivedMcpGrant>of() : agent.grants()) {
                String name = grant == null || grant.connectionName() == null ? "" : grant.connectionName().trim();
                Long connectionId = importedConnections.get(name);
                ConnectionResponse connection = connectionId == null ? null : available.get(connectionId);
                if (connection == null) {
                    dropped.add(agentId + " → " + name + "：这条连接本次没有导入（通常是本机已有同名连接）");
                    continue;
                }
                McpAccessLevel level;
                try {
                    level = McpAccessLevel.parse(grant.accessLevel());
                } catch (IllegalArgumentException invalid) {
                    dropped.add(agentId + " → " + name + "：访问档位无效");
                    continue;
                }
                String problem = grantProblem(connection, level, agent.allowProduction());
                if (problem != null) {
                    dropped.add(agentId + " → " + name + "：" + problem);
                    continue;
                }
                grants.putIfAbsent(connectionId, level);
            }
            if (grants.isEmpty()) {
                skipped.add(agentId + "：没有可以挂上的连接授权");
                continue;
            }
            taken.add(agentId);
            pending.add(new PendingAgent(agentId, hash, agent.enabled(), agent.allowProduction(), grants));
        }

        Settings applied = settings;
        transactions.executeWithoutResult(ignored -> {
            if (applied != null) repository.updateSettings(applied);
            for (PendingAgent agent : pending) {
                repository.insertAgent(agent.agentId(), agent.keyHash(), agent.enabled(), agent.allowProduction(), agent.grants());
            }
        });
        reload();

        if (applied != null) {
            audit.global(actor, "MCP_CONFIG_IMPORT", "mcp", "enabled=" + applied.enabled() + ";source=archive");
        }
        for (PendingAgent agent : pending) {
            imported.add(agent.agentId());
            // 与新建 Agent 一样写清档位；另注明 Key 沿用归档里的哈希 —— 这个 Agent 的 Key 不是在本机发出的。
            audit.global(actor, "MCP_AGENT_IMPORT", "mcp-agent:" + agent.agentId(),
                    "connections=" + agent.grants().size() + ";" + grantSummary(agent.grants()) + ";key=archived-hash");
        }
        return new ArchiveMcpImportResult(applied != null, settingsNote, imported, skipped, dropped);
    }

    private record PendingAgent(
            String agentId, String keyHash, boolean enabled, boolean allowProduction, Map<Long, McpAccessLevel> grants
    ) {
    }

    private static Settings fromLimits(boolean enabled, McpLimits limits, Settings fallback) {
        return new Settings(
                enabled,
                limits.defaultQueryRows(), limits.maxQueryRows(), limits.maxResultCells(),
                limits.maxResultTextChars(), limits.maxCellTextChars(), limits.maxSqlChars(),
                limits.queryTimeoutSeconds(), limits.metadataPageSize(), limits.maxMetadataPageSize(),
                limits.tablePageSize(), limits.maxTablePageSize(), limits.sessionTtlMinutes(),
                limits.maxScriptsPerConnection() == null ? fallback.maxScriptsPerConnection() : limits.maxScriptsPerConnection(),
                limits.executeTimeoutSeconds() == null ? fallback.executeTimeoutSeconds() : limits.executeTimeoutSeconds()
        );
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
        between(settings.maxScriptsPerConnection(), 1, 16, "同连接脚本并发上限");
        positive(settings.defaultQueryRows(), "查询默认行数");
        between(settings.maxQueryRows(), 1, MAX_QUERY_ROWS, "查询最大行数");
        if (settings.defaultQueryRows() > settings.maxQueryRows()) throw new IllegalArgumentException("查询默认行数不能大于最大行数");
        between(settings.maxResultCells(), 1, MAX_RESULT_CELLS, "结果单元格上限");
        between(settings.maxResultTextChars(), 1, MAX_RESULT_TEXT_CHARS, "结果文本总量上限");
        between(settings.maxCellTextChars(), 1, MAX_CELL_TEXT_CHARS, "单元文本上限");
        if (settings.maxCellTextChars() > settings.maxResultTextChars()) throw new IllegalArgumentException("单元文本上限不能大于结果文本总量上限");
        between(settings.maxSqlChars(), 1, MAX_SQL_CHARS, "SQL 长度上限");
        between(settings.queryTimeoutSeconds(), 1, MAX_QUERY_TIMEOUT_SECONDS, "查询超时");
        between(settings.executeTimeoutSeconds(), 1, MAX_QUERY_TIMEOUT_SECONDS, "写操作超时");
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
                source.getTablePageSize(), source.getMaxTablePageSize(), source.getSessionTtlMinutes(), 1, DEFAULT_EXECUTE_TIMEOUT_SECONDS
        );
    }

    private McpLimits toLimits(Settings settings) {
        return new McpLimits(
                settings.defaultQueryRows(), settings.maxQueryRows(), settings.maxResultCells(),
                settings.maxResultTextChars(), settings.maxCellTextChars(), settings.maxSqlChars(),
                settings.queryTimeoutSeconds(), settings.metadataPageSize(), settings.maxMetadataPageSize(),
                settings.tablePageSize(), settings.maxTablePageSize(), settings.sessionTtlMinutes(), settings.maxScriptsPerConnection(),
                settings.executeTimeoutSeconds()
        );
    }

    private McpAgentResponse toResponse(Agent agent) {
        List<McpAgentGrant> grants = agent.connectionLevels().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new McpAgentGrant(entry.getKey(), entry.getValue().name()))
                .toList();
        return new McpAgentResponse(
                agent.id(), agent.agentId(), agent.enabled(), agent.allowProduction(),
                grants, agent.createdAt(), agent.updatedAt()
        );
    }

    private String grantSummary(Map<Long, McpAccessLevel> grants) {
        return "levels=" + grants.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + ":" + entry.getValue().name())
                .collect(java.util.stream.Collectors.joining(","));
    }

    private String generateSecret() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
