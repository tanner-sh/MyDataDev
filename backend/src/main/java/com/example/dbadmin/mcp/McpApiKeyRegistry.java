package com.example.dbadmin.mcp;

import com.example.dbadmin.config.AppProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

@Component
@ConditionalOnProperty(prefix = "app.mcp", name = "enabled", havingValue = "true")
public class McpApiKeyRegistry {
    private static final Pattern AGENT_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Pattern BCRYPT = Pattern.compile("\\$2[aby]\\$[0-3][0-9]\\$[./A-Za-z0-9]{53}");

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);
    private final String dummyHash = encoder.encode("unused-mcp-key");
    private final Map<String, ConfiguredAgent> agents;

    public McpApiKeyRegistry(AppProperties properties) {
        Map<String, ConfiguredAgent> configured = new LinkedHashMap<>();
        for (AppProperties.McpAgent source : properties.getMcp().getAgents()) {
            String id = source.getId() == null ? "" : source.getId().trim();
            String hash = source.getKeyHash() == null ? "" : source.getKeyHash().trim();
            if (!AGENT_ID.matcher(id).matches()) {
                throw new IllegalStateException("MCP agent id 只能包含字母、数字、下划线或短横线，长度为 1-64");
            }
            if (!BCRYPT.matcher(hash).matches()) {
                throw new IllegalStateException("MCP agent " + id + " 的 key-hash 不是有效的 BCrypt 哈希");
            }
            Set<Long> connectionIds = new LinkedHashSet<>();
            for (Long connectionId : source.getConnectionIds()) {
                if (connectionId == null || connectionId <= 0) {
                    throw new IllegalStateException("MCP agent " + id + " 包含无效的 connection-id");
                }
                connectionIds.add(connectionId);
            }
            ConfiguredAgent previous = configured.putIfAbsent(
                    id,
                    new ConfiguredAgent(hash, new McpAgentPrincipal(id, connectionIds, source.isAllowProduction()))
            );
            if (previous != null) {
                throw new IllegalStateException("MCP agent id 重复：" + id);
            }
        }
        if (configured.isEmpty()) {
            throw new IllegalStateException("MCP 已启用，但没有配置 app.mcp.agents");
        }
        agents = Map.copyOf(configured);
    }

    public Optional<McpAgentPrincipal> authenticate(String credential) {
        int separator = credential == null ? -1 : credential.indexOf('.');
        if (separator <= 0 || separator == credential.length() - 1) {
            encoder.matches("invalid", dummyHash);
            return Optional.empty();
        }
        String id = credential.substring(0, separator);
        String secret = credential.substring(separator + 1);
        ConfiguredAgent configured = agents.get(id);
        String hash = configured == null ? dummyHash : configured.keyHash();
        if (!encoder.matches(secret, hash) || configured == null) {
            return Optional.empty();
        }
        return Optional.of(configured.principal());
    }

    private record ConfiguredAgent(String keyHash, McpAgentPrincipal principal) {
    }
}
