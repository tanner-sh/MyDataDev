package com.example.dbadmin.mcp;

import com.example.dbadmin.config.AppProperties;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@ConditionalOnProperty(prefix = "app.mcp", name = "enabled", havingValue = "true")
public class McpApiKeyAuthenticationFilter extends OncePerRequestFilter {
    static final String SESSION_HEADER = "Mcp-Session-Id";

    private final McpApiKeyRegistry keys;
    private final McpSessionOwnershipStore sessions;
    private final MeterRegistry metrics;
    private final Set<String> allowedOrigins;

    public McpApiKeyAuthenticationFilter(
            McpApiKeyRegistry keys,
            McpSessionOwnershipStore sessions,
            AppProperties properties,
            MeterRegistry metrics
    ) {
        this.keys = keys;
        this.sessions = sessions;
        this.metrics = metrics;
        this.allowedOrigins = properties.getMcp().getAllowedOrigins().stream()
                .filter(origin -> origin != null && !origin.isBlank())
                .map(McpApiKeyAuthenticationFilter::normalizeOrigin)
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String origin = request.getHeader(HttpHeaders.ORIGIN);
        if (origin != null && !allowedOrigins.contains(normalizeOrigin(origin))) {
            metrics.counter("dbadmin.mcp.security.denied", "reason", "origin").increment();
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Forbidden");
            return;
        }

        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        String credential = bearerCredential(authorization);
        McpAgentPrincipal principal = keys.authenticate(credential).orElse(null);
        if (principal == null) {
            metrics.counter("dbadmin.mcp.security.denied", "reason", "credential").increment();
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"mydatadev-mcp\"");
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized");
            return;
        }

        String requestSessionId = trimToNull(request.getHeader(SESSION_HEADER));
        if (requestSessionId != null && !sessions.belongsTo(requestSessionId, principal.id())) {
            metrics.counter("dbadmin.mcp.security.denied", "reason", "session-owner").increment();
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Forbidden");
            return;
        }

        var authentication = UsernamePasswordAuthenticationToken.authenticated(
                principal,
                null,
                List.of(new SimpleGrantedAuthority("ROLE_MCP_AGENT"))
        );
        SecurityContextHolder.getContext().setAuthentication(authentication);
        chain.doFilter(request, response);

        String responseSessionId = trimToNull(response.getHeader(SESSION_HEADER));
        if (response.getStatus() < 400 && responseSessionId != null && !sessions.bind(responseSessionId, principal.id())) {
            metrics.counter("dbadmin.mcp.security.denied", "reason", "session-bind").increment();
        }
        if ("DELETE".equalsIgnoreCase(request.getMethod()) && requestSessionId != null && response.getStatus() < 400) {
            sessions.remove(requestSessionId, principal.id());
        }
    }

    private String bearerCredential(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) return null;
        return trimToNull(authorization.substring(7));
    }

    private static String normalizeOrigin(String origin) {
        String normalized = origin == null ? "" : origin.trim().toLowerCase(Locale.ROOT);
        while (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
        return normalized;
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
    }
}
