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
        return requireConnection(connectionId, McpAccessLevel.READ_ONLY);
    }

    /**
     * 校验连接授权并要求达到指定档位。
     *
     * <p>「未授权」与「档位不够」返回不同的错误：前者连连接存在与否都不该透露，后者是配置问题，
     * 说清楚缺什么才能让调用方去找管理员，而它本来就知道这条连接存在。</p>
     */
    public DbConnection requireConnection(long connectionId, McpAccessLevel required) {
        McpAgentPrincipal principal = principal();
        // 白名单先判：未授权的连接连查都不该查，存在与否本身就是信息。
        McpAccessLevel granted = principal.levelFor(connectionId);
        if (granted == null) throw unavailable();
        DbConnection connection;
        try {
            connection = connections.require(connectionId);
        } catch (RuntimeException ignored) {
            throw unavailable();
        }
        if (!allowed(principal, connection.id(), connection.environment())) {
            throw unavailable();
        }
        if (!granted.covers(required)) {
            // 档位不够和未授权要分开报：调用方已经知道这条连接存在，说清缺什么才好去找管理员。
            throw new IllegalArgumentException(
                    "当前 MCP agent 在该连接上的访问档位是「" + granted.label()
                            + "」，该操作需要「" + required.label() + "」。请让管理员在 MCP 设置中调整授权。");
        }
        return connection;
    }

    /** 该连接上授予的档位；未授权时返回 READ_ONLY 以便展示，实际访问仍由 requireConnection 判定。 */
    public McpAccessLevel levelFor(long connectionId) {
        McpAccessLevel granted = principal().levelFor(connectionId);
        return granted == null ? McpAccessLevel.READ_ONLY : granted;
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
