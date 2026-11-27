package com.example.dbadmin.audit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
// 比 ClientDisconnectFilter 靠里一档：那一条要在最外层收住对端断开时的写失败，
// 而这里只是给这次请求准备审计上下文，谁先谁后不影响它。
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class AuditRequestContextFilter extends OncePerRequestFilter {
    public static final String REQUEST_ID_HEADER = "X-Request-ID";
    public static final String CLIENT_HEADER = "X-MyDataDev-Client";
    public static final String CLIENT_HOST_HEADER = "X-MyDataDev-Client-Host";
    private static final Pattern SAFE_REQUEST_ID = Pattern.compile("[A-Za-z0-9._:-]{1,120}");
    private static final Pattern SAFE_CLIENT_HOST = Pattern.compile("[A-Za-z0-9._@:-]{1,120}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String supplied = request.getHeader(REQUEST_ID_HEADER);
        String requestId = supplied != null && SAFE_REQUEST_ID.matcher(supplied).matches()
                ? supplied : UUID.randomUUID().toString();
        String channel = "/mcp".equals(request.getRequestURI()) ? "mcp" : "web";
        String clientDeclared = parseClientDeclared(request);
        AuditRequestContext.set(new AuditRequestContext(
                request.getAttribute(com.example.dbadmin.config.TrustedForwardedHeaders.PEER) instanceof String peer ? peer : request.getRemoteAddr(),
                request.getAttribute(com.example.dbadmin.config.TrustedForwardedHeaders.DECLARED_FOR) instanceof String forwarded ? (forwarded.isEmpty() ? null : forwarded) : request.getHeader("X-Forwarded-For"),
                request.getHeader("User-Agent"),
                requestId,
                channel,
                null,
                clientDeclared
        ));
        response.setHeader(REQUEST_ID_HEADER, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            AuditRequestContext.clear();
        }
    }

    private static String parseClientDeclared(HttpServletRequest request) {
        String client = request.getHeader(CLIENT_HEADER);
        if (client == null || !"cli".equalsIgnoreCase(client.trim())) return null;
        String host = request.getHeader(CLIENT_HOST_HEADER);
        if (host != null) {
            String trimmed = host.trim();
            if (SAFE_CLIENT_HOST.matcher(trimmed).matches()) {
                return "cli(" + trimmed + ")";
            }
        }
        return "cli";
    }
}
