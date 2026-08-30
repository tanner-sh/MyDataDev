package com.example.dbadmin.mcp;

import java.util.Map;
import java.util.Set;

/**
 * 通过 API Key 认证出来的 MCP Agent 身份。
 *
 * <p>授权是「连接 → 档位」的映射而不是一份 id 名单：同一个 Agent 完全可能在开发库上有写权限、
 * 在生产库上只读，扁平的名单表达不了这件事。</p>
 */
public record McpAgentPrincipal(String id, Map<Long, McpAccessLevel> connectionLevels, boolean allowProduction) {
    public McpAgentPrincipal {
        connectionLevels = connectionLevels == null ? Map.of() : Map.copyOf(connectionLevels);
    }

    public Set<Long> connectionIds() {
        return connectionLevels.keySet();
    }

    /** 该连接上授予的档位；未授权时返回 null。 */
    public McpAccessLevel levelFor(long connectionId) {
        return connectionLevels.get(connectionId);
    }

    public String actor() {
        return "mcp:" + id;
    }
}
