package com.example.dbadmin.mcp;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class McpSessionOwnershipStore {
    public enum Access { ALLOWED, NOT_FOUND, FORBIDDEN }

    private final ConcurrentHashMap<String, Ownership> sessions = new ConcurrentHashMap<>();
    private final McpConfigurationService configuration;
    private final Clock clock;

    @Autowired
    public McpSessionOwnershipStore(McpConfigurationService configuration) {
        this(configuration, Clock.systemUTC());
    }

    McpSessionOwnershipStore(McpConfigurationService configuration, Clock clock) {
        this.configuration = configuration;
        this.clock = clock;
    }

    /** 区分失效会话与跨 Agent 访问，让客户端能收到重新初始化所需的 404。 */
    public Access checkAccess(String sessionId, String agentId) {
        Instant now = clock.instant();
        cleanupExpired(now);
        Ownership ownership = sessions.computeIfPresent(sessionId, (ignored, existing) -> {
            if (!existing.expiresAt().isAfter(now)) return null;
            if (!existing.agentId().equals(agentId)) return existing;
            return new Ownership(agentId, now.plus(ttl()));
        });
        if (ownership == null) return Access.NOT_FOUND;
        return ownership.agentId().equals(agentId) ? Access.ALLOWED : Access.FORBIDDEN;
    }

    public boolean bind(String sessionId, String agentId) {
        Instant now = clock.instant();
        cleanupExpired(now);
        Ownership requested = new Ownership(agentId, now.plus(ttl()));
        Ownership ownership = sessions.compute(sessionId, (ignored, existing) ->
                existing != null && existing.expiresAt().isAfter(now) && !existing.agentId().equals(agentId)
                        ? existing : requested);
        return ownership.agentId().equals(agentId);
    }

    public void remove(String sessionId, String agentId) {
        sessions.computeIfPresent(sessionId, (ignored, ownership) -> ownership.agentId().equals(agentId) ? null : ownership);
    }

    public void removeAgent(String agentId) {
        sessions.entrySet().removeIf(entry -> entry.getValue().agentId().equals(agentId));
    }

    public void clear() {
        sessions.clear();
    }

    private Duration ttl() {
        return Duration.ofMinutes(Math.max(1, configuration.snapshot().settings().sessionTtlMinutes()));
    }

    private void cleanupExpired(Instant now) {
        sessions.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
    }

    private record Ownership(String agentId, Instant expiresAt) {
    }
}
