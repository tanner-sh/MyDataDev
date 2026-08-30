package com.example.dbadmin.mcp;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record McpRuntimeConfig(
        Settings settings,
        Set<String> allowedOrigins,
        Map<String, Agent> agents
) {
    public McpRuntimeConfig {
        allowedOrigins = allowedOrigins == null ? Set.of() : Set.copyOf(allowedOrigins);
        agents = agents == null ? Map.of() : Map.copyOf(agents);
    }

    public record Settings(
            boolean enabled,
            int defaultQueryRows,
            int maxQueryRows,
            int maxResultCells,
            long maxResultTextChars,
            int maxCellTextChars,
            int maxSqlChars,
            int queryTimeoutSeconds,
            int metadataPageSize,
            int maxMetadataPageSize,
            int tablePageSize,
            int maxTablePageSize,
            int sessionTtlMinutes
    ) {
    }

    public record Agent(
            long id,
            String agentId,
            String keyHash,
            boolean enabled,
            boolean allowProduction,
            Map<Long, McpAccessLevel> connectionLevels,
            Instant createdAt,
            Instant updatedAt
    ) {
        public Agent {
            connectionLevels = connectionLevels == null ? Map.of() : Map.copyOf(connectionLevels);
        }

        public Set<Long> connectionIds() {
            return connectionLevels.keySet();
        }
    }

    public List<Agent> agentList() {
        return agents.values().stream()
                .sorted(java.util.Comparator.comparing(Agent::agentId, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }
}
