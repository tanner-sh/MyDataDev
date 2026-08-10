package com.example.dbadmin.mcp;

import com.example.dbadmin.config.AppProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

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
        AppProperties properties = new AppProperties();
        properties.getMcp().setAllowedOrigins(List.of("https://trusted.example/"));
        properties.getMcp().setAgents(List.of(
                agent("agent-a", "secret-a", 1L),
                agent("agent-b", "secret-b", 2L)
        ));
        return new McpApiKeyAuthenticationFilter(
                new McpApiKeyRegistry(properties),
                new McpSessionOwnershipStore(properties),
                properties,
                new SimpleMeterRegistry()
        );
    }

    private AppProperties.McpAgent agent(String id, String secret, long connectionId) {
        AppProperties.McpAgent agent = new AppProperties.McpAgent();
        agent.setId(id);
        agent.setKeyHash(new BCryptPasswordEncoder(4).encode(secret));
        agent.setConnectionIds(List.of(connectionId));
        return agent;
    }

    private MockHttpServletRequest authorizedRequest(String credential) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
        request.addHeader("Authorization", "Bearer " + credential);
        return request;
    }
}
