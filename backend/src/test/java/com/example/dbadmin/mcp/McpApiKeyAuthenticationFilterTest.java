package com.example.dbadmin.mcp;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class McpApiKeyAuthenticationFilterTest {
    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void rejectsMissingCredentialAndUntrustedOrigin() throws Exception {
        McpApiKeyAuthenticationFilter filter = filter();

        MockHttpServletResponse unauthorized = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("POST", "/mcp"), unauthorized, new MockFilterChain());
        assertThat(unauthorized.getStatus()).isEqualTo(401);
        assertThat(unauthorized.getHeader("WWW-Authenticate")).contains("Bearer");

        MockHttpServletRequest badOrigin = authorizedRequest("agent-a.secret-a");
        badOrigin.addHeader("Origin", "https://evil.example");
        MockHttpServletResponse forbidden = new MockHttpServletResponse();
        filter.doFilter(badOrigin, forbidden, new MockFilterChain());
        assertThat(forbidden.getStatus()).isEqualTo(403);
    }

    @Test
    void authenticatesEveryRequestAndPreventsCrossAgentSessionReuse() throws Exception {
        McpApiKeyAuthenticationFilter filter = filter();
        MockHttpServletRequest initialize = authorizedRequest("agent-a.secret-a");
        MockHttpServletResponse initialized = new MockHttpServletResponse();
        filter.doFilter(initialize, initialized, (request, response) -> {
            ((MockHttpServletResponse) response).setStatus(200);
            ((MockHttpServletResponse) response).setHeader(McpApiKeyAuthenticationFilter.SESSION_HEADER, "session-a");
        });
        assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal())
                .isEqualTo(new McpAgentPrincipal("agent-a", java.util.Set.of(1L), false));

        SecurityContextHolder.clearContext();
        MockHttpServletRequest sameAgent = authorizedRequest("agent-a.secret-a");
        sameAgent.addHeader(McpApiKeyAuthenticationFilter.SESSION_HEADER, "session-a");
        MockHttpServletResponse accepted = new MockHttpServletResponse();
        filter.doFilter(sameAgent, accepted, new MockFilterChain());
        assertThat(accepted.getStatus()).isEqualTo(200);

        SecurityContextHolder.clearContext();
        MockHttpServletRequest otherAgent = authorizedRequest("agent-b.secret-b");
        otherAgent.addHeader(McpApiKeyAuthenticationFilter.SESSION_HEADER, "session-a");
        MockHttpServletResponse rejected = new MockHttpServletResponse();
        filter.doFilter(otherAgent, rejected, new MockFilterChain());
        assertThat(rejected.getStatus()).isEqualTo(403);
    }

    private McpApiKeyAuthenticationFilter filter() {
        McpConfigurationService configuration = mock(McpConfigurationService.class);
        McpRuntimeConfig.Agent agentA = agent(1L, "agent-a", "secret-a", 1L);
        McpRuntimeConfig.Agent agentB = agent(2L, "agent-b", "secret-b", 2L);
        when(configuration.snapshot()).thenReturn(new McpRuntimeConfig(
                new McpRuntimeConfig.Settings(true, 100, 1_000, 50_000, 2_000_000,
                        20_000, 100_000, 30, 100, 500, 100, 500, 30),
                Set.of("https://trusted.example"),
                Map.of(agentA.agentId(), agentA, agentB.agentId(), agentB)
        ));
        return new McpApiKeyAuthenticationFilter(
                new McpApiKeyRegistry(configuration),
                new McpSessionOwnershipStore(configuration),
                configuration,
                new SimpleMeterRegistry()
        );
    }

    private McpRuntimeConfig.Agent agent(long numericId, String id, String secret, long connectionId) {
        return new McpRuntimeConfig.Agent(
                numericId, id, new BCryptPasswordEncoder(4).encode(secret), true,
                false, Set.of(connectionId), Instant.EPOCH, Instant.EPOCH
        );
    }

    private MockHttpServletRequest authorizedRequest(String credential) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
        request.addHeader("Authorization", "Bearer " + credential);
        return request;
    }
}
