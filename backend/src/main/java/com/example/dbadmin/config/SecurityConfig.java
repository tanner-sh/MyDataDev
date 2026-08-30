package com.example.dbadmin.config;

import com.example.dbadmin.auth.AuthenticatedActorHeaderFilter;
import com.example.dbadmin.auth.WebAuthenticationService;
import com.example.dbadmin.auth.OidcIdentityProvider;
import com.example.dbadmin.auth.WebIdentity;
import com.example.dbadmin.mcp.McpApiKeyAuthenticationFilter;
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
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
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
    @Order(1)
    SecurityFilterChain mcpSecurity(HttpSecurity http, McpApiKeyAuthenticationFilter apiKeyFilter) throws Exception {
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
                .build();
    }

    @Bean
    @Order(2)
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
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/api/auth/status", "/actuator/health", "/oauth2/**", "/login/oauth2/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/storage-profiles/**").authenticated()
                        .requestMatchers("/api/admin/**", "/api/audit/**", "/api/mcp/**", "/api/storage-profiles/**").hasRole("ADMIN")
                        .requestMatchers("/api/**").authenticated()
                        .requestMatchers("/actuator/**", "/h2-console/**").authenticated()
                        .anyRequest().permitAll())
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
}
