package com.example.dbadmin.config;

import com.example.dbadmin.auth.AuthenticatedActorHeaderFilter;
import com.example.dbadmin.auth.WebAuthenticationService;
import com.example.dbadmin.mcp.McpApiKeyAuthenticationFilter;
import com.example.dbadmin.repo.AuditRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;

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
            AuditRepository audit
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
        return http
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
                        .requestMatchers(HttpMethod.GET, "/api/auth/status", "/actuator/health").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/storage-profiles/**").authenticated()
                        .requestMatchers("/api/admin/**", "/api/audit/**", "/api/mcp/**", "/api/storage-profiles/**").hasRole("ADMIN")
                        .requestMatchers("/api/**").authenticated()
                        .requestMatchers("/actuator/**", "/h2-console/**").authenticated()
                        .anyRequest().permitAll())
                .addFilterAfter(actorHeaderFilter, AnonymousAuthenticationFilter.class)
                .build();
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
