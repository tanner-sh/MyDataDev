package com.example.dbadmin.mcp;

import java.util.Set;

public record McpAgentPrincipal(String id, Set<Long> connectionIds, boolean allowProduction) {
    public McpAgentPrincipal {
        connectionIds = connectionIds == null ? Set.of() : Set.copyOf(connectionIds);
    }

    public String actor() {
        return "mcp:" + id;
    }
}
