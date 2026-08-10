package com.example.dbadmin.mcp;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class McpSessionOwnershipStore {
    private final ConcurrentHashMap<String, Ownership> sessions = new ConcurrentHashMap<>();
    private final McpConfigurationService configuration;

    public McpSessionOwnershipStore(McpConfigurationService configuration) {
        this.configuration = configuration;
    }

    public boolean belongsTo(String sessionId, String agentId) {
        cleanupExpired();
        Ownership ownership = sessions.get(sessionId);
        if (ownership == null || !ownership.agentId().equals(agentId)) return false;
        sessions.replace(sessionId, ownership, new Ownership(agentId, Instant.now().plus(ttl())));
        return true;
    }

    public boolean bind(String sessionId, String agentId) {
        cleanupExpired();
        Ownership requested = new Ownership(agentId, Instant.now().plus(ttl()));
        Ownership existing = sessions.putIfAbsent(sessionId, requested);
        if (existing == null) return true;
        if (!existing.agentId().equals(agentId)) return false;
        sessions.replace(sessionId, existing, requested);
        return true;
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

    private void cleanupExpired() {
        Instant now = Instant.now();
        sessions.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
    }

    private record Ownership(String agentId, Instant expiresAt) {
    }
}
