package com.example.dbadmin.config;

import com.example.dbadmin.auth.AccessTokenAuthenticationFilter;
import com.example.dbadmin.auth.AuthenticatedActorHeaderFilter;
import com.example.dbadmin.auth.WebAuthenticationService;
import com.example.dbadmin.auth.OidcIdentityProvider;
import com.example.dbadmin.auth.WebIdentity;
import com.example.dbadmin.mcp.McpApiKeyAuthenticationFilter;
import com.example.dbadmin.mcp.McpCancellationFilter;
import com.example.dbadmin.mcp.McpStreamableHttpFilter;
import com.example.dbadmin.repo.AuditRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

import java.io.IOException;

@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfig {
    private static final String[] ADMIN_API_PATTERNS = {
            "/api/admin/**", "/api/audit/**", "/api/mcp/**",
            "/api/ai/settings/**", "/api/ai/connections/**", "/api/ai/usage",
            "/api/storage-profiles/**"
    };

    @Bean
    FilterRegistrationBean<McpApiKeyAuthenticationFilter> disableGlobalMcpFilterRegistration(
            McpApiKeyAuthenticationFilter apiKeyFilter
    ) {
        FilterRegistrationBean<McpApiKeyAuthenticationFilter> registration = new FilterRegistrationBean<>(apiKeyFilter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    FilterRegistrationBean<AuthenticatedActorHeaderFilter> disableGlobalActorFilterRegistration(
            AuthenticatedActorHeaderFilter actorHeaderFilter
    ) {
        FilterRegistrationBean<AuthenticatedActorHeaderFilter> registration = new FilterRegistrationBean<>(actorHeaderFilter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    FilterRegistrationBean<AccessTokenAuthenticationFilter> disableGlobalPatFilterRegistration(
            AccessTokenAuthenticationFilter patFilter
    ) {
        FilterRegistrationBean<AccessTokenAuthenticationFilter> registration = new FilterRegistrationBean<>(patFilter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    @Order(1)
    SecurityFilterChain mcpSecurity(HttpSecurity http, McpApiKeyAuthenticationFilter apiKeyFilter,
            com.fasterxml.jackson.databind.ObjectMapper mapper, com.example.dbadmin.mcp.McpCancellationRegistry cancellations) throws Exception {
        return http
                .securityMatcher("/mcp")
                .csrf(AbstractHttpConfigurer::disable)
                .cors(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().hasRole("MCP_AGENT"))
                .addFilterBefore(apiKeyFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(new McpCancellationFilter(mapper, cancellations), McpApiKeyAuthenticationFilter.class)
                .addFilterAfter(new McpStreamableHttpFilter(), McpCancellationFilter.class)
                .build();
    }

    /**
     * 个人访问令牌（PAT）专用无状态安全链。
     *
     * <p>只在认证启用且请求指向 {@code /api/**} 且携带 {@code Authorization: Bearer mdd_pat_...}
     * 时命中：完全不读不写 HttpSession（因此同时携带 Cookie 时按 PAT 处理），不需要 CSRF，
     * 并且显式拒绝管理员路由与令牌签发/管理接口。</p>
     */
    @Bean
    @Order(2)
    SecurityFilterChain patApiSecurity(
            HttpSecurity http,
            WebAuthenticationService authenticationService,
            AccessTokenAuthenticationFilter patFilter,
            AuditRepository audit
    ) throws Exception {
        return http
                .securityMatcher(request -> authenticationService.enabled()
                        && isApiPath(request)
                        && AccessTokenAuthenticationFilter.hasPatAuthorizationHeader(request))
                .cors(cors -> {})
                .csrf(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .securityContext(sc -> sc.securityContextRepository(new NullSecurityContextRepository()))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) -> writeUnauthorized(response))
                        .accessDeniedHandler((request, response, exception) -> {
                            org.springframework.security.core.Authentication authentication =
                                    SecurityContextHolder.getContext().getAuthentication();
                            String actor = authentication == null ? "anonymous" : authentication.getName();
                            audit.global(actor, "AUTHORIZATION_DENIED", request.getMethod() + " " + request.getRequestURI(),
                                    "reason=" + exception.getClass().getSimpleName());
                            writePatForbidden(response);
                        }))
                .authorizeHttpRequests(auth -> applyApiAuthorization(auth, true))
                .addFilterBefore(patFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    @Bean
    @Order(3)
    SecurityFilterChain existingApplicationSecurity(
            HttpSecurity http,
            WebAuthenticationService authenticationService,
            AuthenticatedActorHeaderFilter actorHeaderFilter,
            AuditRepository audit,
            OidcIdentityProvider oidcIdentityProvider,
            ObjectProvider<ClientRegistrationRepository> clientRegistrations
    ) throws Exception {
        http
                .cors(cors -> {})
                .requestCache(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .headers(headers -> headers.frameOptions(frameOptions -> frameOptions.sameOrigin()));
        if (!authenticationService.enabled()) {
            return http
                    .csrf(AbstractHttpConfigurer::disable)
                    .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                    .build();
        }
        CookieCsrfTokenRepository csrfRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfRepository.setCookiePath("/");
        csrfRepository.setCookieCustomizer(cookie -> cookie.secure(authenticationService.cookieSecure()));
        http
                .csrf(csrf -> csrf.csrfTokenRepository(csrfRepository))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) -> writeUnauthorized(response))
                        .accessDeniedHandler((request, response, exception) -> {
                            org.springframework.security.core.Authentication authentication =
                                    org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
                            String actor = authentication == null ? "anonymous" : authentication.getName();
                            audit.global(actor, "AUTHORIZATION_DENIED", request.getMethod() + " " + request.getRequestURI(),
                                    "reason=" + exception.getClass().getSimpleName());
                            writeForbidden(response);
                        }))
                .authorizeHttpRequests(auth -> {
                    applyApiAuthorization(auth, false);
                    auth.requestMatchers("/actuator/**", "/h2-console/**").authenticated()
                            .anyRequest().permitAll();
                })
                .addFilterAfter(actorHeaderFilter, AnonymousAuthenticationFilter.class);
        if ("OIDC".equalsIgnoreCase(authenticationService.providerId())) {
            if (clientRegistrations.getIfAvailable() == null) {
                throw new IllegalStateException("OIDC 模式缺少 ClientRegistrationRepository");
            }
            HttpSessionSecurityContextRepository contexts = new HttpSessionSecurityContextRepository();
            http.oauth2Login(oauth -> oauth
                    .successHandler((request, response, authentication) -> {
                        if (!(authentication.getPrincipal() instanceof OidcUser oidcUser)) {
                            SecurityContextHolder.clearContext();
                            jakarta.servlet.http.HttpSession session = request.getSession(false);
                            if (session != null) session.invalidate();
                            response.sendError(401, "OIDC 登录未返回标准用户声明");
                            return;
                        }
                        java.util.Optional<WebIdentity> resolved = oidcIdentityProvider.login(oidcUser);
                        if (resolved.isEmpty()) {
                            audit.global("anonymous", "AUTH_LOGIN_FAILED", "oidc", "reason=account_disabled");
                            SecurityContextHolder.clearContext();
                            jakarta.servlet.http.HttpSession session = request.getSession(false);
                            if (session != null) session.invalidate();
                            response.sendError(403, "SSO 账号已被停用");
                            return;
                        }
                        WebIdentity identity = resolved.get();
                        UsernamePasswordAuthenticationToken appAuthentication = UsernamePasswordAuthenticationToken.authenticated(
                                identity, null, java.util.List.of(new SimpleGrantedAuthority("ROLE_" + identity.role())));
                        org.springframework.security.core.context.SecurityContext context = SecurityContextHolder.createEmptyContext();
                        context.setAuthentication(appAuthentication);
                        SecurityContextHolder.setContext(context);
                        contexts.saveContext(context, request, response);
                        audit.global(identity.username(), "AUTH_LOGIN", "user:" + identity.username(),
                                "provider=OIDC, role=" + identity.role());
                        response.sendRedirect(request.getContextPath() + "/");
                    })
                    .failureHandler((request, response, exception) -> {
                        audit.global("anonymous", "AUTH_LOGIN_FAILED", "oidc",
                                "reason=" + exception.getClass().getSimpleName());
                        response.sendRedirect(request.getContextPath() + "/?ssoError=1");
                    }));
        }
        return http.build();
    }

    /** Web 会话链与 PAT 无状态链共用同一份 API 路由授权规则，避免两边日后漂移。 */
    private void applyApiAuthorization(
            AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry auth,
            boolean patChain
    ) {
        if (patChain) {
            // StreamingResponseBody 等异步响应在初始 REQUEST 阶段已完成 PAT 认证与授权；
            // 无状态链不保存 Session SecurityContext，异步恢复分派时需放行容器内部 ASYNC 分派。
            auth.dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ASYNC).permitAll();
            auth.requestMatchers("/api/auth/tokens/**", "/api/auth/login", "/api/auth/logout").denyAll();
        } else {
            auth.requestMatchers(HttpMethod.GET, "/api/system/info", "/api/auth/status", "/actuator/health", "/oauth2/**", "/login/oauth2/**").permitAll();
            auth.requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll();
        }
        auth.requestMatchers(HttpMethod.GET, "/api/storage-profiles/**").authenticated();
        if (patChain) {
            // 第一期 PAT 不开放全局管理路由（含管理员签发的 PAT）
            auth.requestMatchers(ADMIN_API_PATTERNS).denyAll();
        } else {
            auth.requestMatchers(ADMIN_API_PATTERNS).hasRole("ADMIN");
        }
        auth.requestMatchers("/api/**").authenticated();
    }

    private static boolean isApiPath(jakarta.servlet.http.HttpServletRequest request) {
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }
        return path.startsWith("/api/");
    }

    private void writeUnauthorized(jakarta.servlet.http.HttpServletResponse response) throws IOException {
        response.setStatus(401);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json");
        response.getWriter().write("{\"ok\":false,\"code\":\"AUTH_REQUIRED\",\"message\":\"请先登录。\"}");
    }

    private void writeForbidden(jakarta.servlet.http.HttpServletResponse response) throws IOException {
        response.setStatus(403);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json");
        response.getWriter().write("{\"ok\":false,\"code\":\"ACCESS_DENIED\",\"message\":\"请求缺少有效的安全令牌，请刷新页面后重试。\"}");
    }

    private void writePatForbidden(jakarta.servlet.http.HttpServletResponse response) throws IOException {
        response.setStatus(403);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json");
        response.getWriter().write("{\"ok\":false,\"code\":\"ACCESS_DENIED\",\"message\":\"当前访问令牌无权调用该接口。\"}");
    }
}
