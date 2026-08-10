package com.example.dbadmin.mcp;

import com.example.dbadmin.dto.ApiDtos.ConnectionResponse;
import com.example.dbadmin.dto.ApiDtos.DatabaseCapabilities;
import com.example.dbadmin.model.DbConnection;
import com.example.dbadmin.service.ConnectionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class McpAccessServiceTest {
    private final ConnectionService connections = mock(ConnectionService.class);
    private final McpAccessService access = new McpAccessService(connections);

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void exposesAllowlistedReadonlyAndWritableNonProductionConnectionsByDefault() {
        authenticate(new McpAgentPrincipal("agent", Set.of(1L, 2L, 3L), false));
        when(connections.list()).thenReturn(List.of(
                response(1L, "dev-read", "dev", true),
                response(2L, "dev-write", "dev", false),
                response(3L, "prod-read", "prod", true),
                response(4L, "not-allowlisted", "dev", true)
        ));

        assertThat(access.authorizedConnections()).extracting(ConnectionResponse::id).containsExactly(1L, 2L);
    }

    @Test
    void permitsExplicitlyAuthorizedProductionConnectionRegardlessOfReadonlyFlag() {
        authenticate(new McpAgentPrincipal("agent", Set.of(3L), true));
        DbConnection production = model(3L, "prod-write", "prod", false);
        when(connections.require(3L)).thenReturn(production);

        assertThat(access.requireConnection(3L)).isSameAs(production);
        assertThat(access.actor()).isEqualTo("mcp:agent");
    }

    @Test
    void permitsWritableConnectionAndHidesMissingAndUnauthorizedConnectionsBehindSameError() {
        authenticate(new McpAgentPrincipal("agent", Set.of(1L, 2L), false));
        DbConnection writable = model(1L, "write", "dev", false);
        when(connections.require(1L)).thenReturn(writable);
        when(connections.require(2L)).thenThrow(new IllegalArgumentException("not found"));

        assertThat(access.requireConnection(1L)).isSameAs(writable);
        assertThatThrownBy(() -> access.requireConnection(2L)).hasMessage("连接不可用或当前 MCP agent 未获授权");
        assertThatThrownBy(() -> access.requireConnection(9L)).hasMessage("连接不可用或当前 MCP agent 未获授权");
    }

    private void authenticate(McpAgentPrincipal principal) {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of())
        );
    }

    private ConnectionResponse response(long id, String name, String environment, boolean readonly) {
        return new ConnectionResponse(
                id, name, "h2", "jdbc:h2:mem:test", "sa", environment, readonly,
                new DatabaseCapabilities(true, true, true, true, List.of(), List.of())
        );
    }

    private DbConnection model(long id, String name, String environment, boolean readonly) {
        return new DbConnection(id, name, "h2", "jdbc:h2:mem:test", "sa", "", environment, readonly, Instant.now(), Instant.now());
    }
}
