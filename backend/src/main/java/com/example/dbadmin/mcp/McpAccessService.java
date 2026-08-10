package com.example.dbadmin.mcp;

import com.example.dbadmin.dto.ApiDtos.ConnectionResponse;
import com.example.dbadmin.model.DbConnection;
import com.example.dbadmin.service.ConnectionService;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class McpAccessService {
    private final ConnectionService connections;

    public McpAccessService(ConnectionService connections) {
        this.connections = connections;
    }

    public List<ConnectionResponse> authorizedConnections() {
        McpAgentPrincipal principal = principal();
        return connections.list().stream()
                .filter(connection -> allowed(principal, connection.id(), connection.environment()))
                .toList();
    }

    public DbConnection requireConnection(long connectionId) {
        McpAgentPrincipal principal = principal();
        if (!principal.connectionIds().contains(connectionId)) {
            throw unavailable();
        }
        DbConnection connection;
        try {
            connection = connections.require(connectionId);
        } catch (RuntimeException ignored) {
            throw unavailable();
        }
        if (!allowed(principal, connection.id(), connection.environment())) {
            throw unavailable();
        }
        return connection;
    }

    public McpAgentPrincipal principal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof McpAgentPrincipal principal)) {
            throw new IllegalStateException("MCP 调用身份不可用");
        }
        return principal;
    }

    public String actor() {
        return principal().actor();
    }

    private boolean allowed(McpAgentPrincipal principal, long connectionId, String environment) {
        if (!principal.connectionIds().contains(connectionId)) return false;
        return !"prod".equalsIgnoreCase(environment) || principal.allowProduction();
    }

    private IllegalArgumentException unavailable() {
        return new IllegalArgumentException("连接不可用或当前 MCP agent 未获授权");
    }
}
