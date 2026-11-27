package com.example.dbadmin.auth;

import com.example.dbadmin.audit.AuditRequestContext;
import com.example.dbadmin.config.TrustedForwardedHeaders;
import com.example.dbadmin.repo.AuditRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Optional;

/**
 * 个人访问令牌（PAT）无状态认证过滤器。
 *
 * <p>仅挂载在独立的 PAT 安全链上：只处理携带 {@code Authorization: Bearer mdd_pat_...} 的请求，
 * 不读取也不创建 HttpSession；认证失败立即返回 401，不回退到匿名身份或 Cookie 会话。</p>
 */
@Component
public class AccessTokenAuthenticationFilter extends OncePerRequestFilter {
    private static final String ACTOR_HEADER = "X-User";
    private static final String BEARER_PREFIX = "Bearer ";

    private final AccessTokenService accessTokenService;
    private final AuditRepository audit;

    public AccessTokenAuthenticationFilter(AccessTokenService accessTokenService, AuditRepository audit) {
        this.accessTokenService = accessTokenService;
        this.audit = audit;
    }

    public static boolean hasPatAuthorizationHeader(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || header.length() <= BEARER_PREFIX.length()) return false;
        if (!header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) return false;
        String token = header.substring(BEARER_PREFIX.length()).trim();
        return token.startsWith(AccessTokenService.TOKEN_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String rawToken = extractToken(request);
        String remoteAddress = request.getAttribute(TrustedForwardedHeaders.PEER) instanceof String peer
                ? peer : request.getRemoteAddr();
        Optional<AccessTokenAuthentication> authenticated = accessTokenService.authenticate(rawToken, remoteAddress);
        if (authenticated.isEmpty()) {
            SecurityContextHolder.clearContext();
            audit.global("anonymous", "AUTH_LOGIN_FAILED",
                    "pat:" + AccessTokenService.safeTokenIdHint(rawToken), "reason=invalid_token");
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setCharacterEncoding("UTF-8");
            response.setContentType("application/json");
            response.getWriter().write("{\"ok\":false,\"code\":\"INVALID_ACCESS_TOKEN\",\"message\":\"访问令牌无效、已过期或已吊销。\"}");
            return;
        }

        AccessTokenAuthentication authentication = authenticated.get();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);

        AuditRequestContext previousAudit = AuditRequestContext.current();
        AuditRequestContext updatedAudit = previousAudit != null
                ? previousAudit.withPat(authentication.tokenId())
                : new AuditRequestContext(remoteAddress, null, request.getHeader("User-Agent"), null,
                "cli", authentication.tokenId(), null);
        AuditRequestContext.set(updatedAudit);
        try {
            String actor = authentication.getName();
            filterChain.doFilter(new HttpServletRequestWrapper(request) {
                @Override
                public String getHeader(String name) {
                    return ACTOR_HEADER.equalsIgnoreCase(name) ? actor : super.getHeader(name);
                }

                @Override
                public Enumeration<String> getHeaders(String name) {
                    return ACTOR_HEADER.equalsIgnoreCase(name)
                            ? Collections.enumeration(List.of(actor)) : super.getHeaders(name);
                }

                @Override
                public Enumeration<String> getHeaderNames() {
                    List<String> names = new ArrayList<>(Collections.list(super.getHeaderNames()));
                    if (names.stream().noneMatch(ACTOR_HEADER::equalsIgnoreCase)) names.add(ACTOR_HEADER);
                    return Collections.enumeration(names);
                }
            }, response);
        } finally {
            SecurityContextHolder.clearContext();
            if (previousAudit == null) AuditRequestContext.clear();
            else AuditRequestContext.set(previousAudit);
        }
    }

    private static String extractToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || header.length() <= BEARER_PREFIX.length()) return null;
        if (!header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) return null;
        return header.substring(BEARER_PREFIX.length()).trim();
    }
}
