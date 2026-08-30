package com.example.dbadmin.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

@Component
public class AuthenticatedActorHeaderFilter extends OncePerRequestFilter {
    private static final String ACTOR_HEADER = "X-User";
    private final WebAuthenticationService authenticationService;

    public AuthenticatedActorHeaderFilter(WebAuthenticationService authenticationService) {
        this.authenticationService = authenticationService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!authenticationService.enabled() || authentication == null || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getPrincipal())) {
            filterChain.doFilter(request, response);
            return;
        }
        if (authentication.getPrincipal() instanceof WebIdentity identity) {
            java.util.Optional<WebIdentity> refreshed = authenticationService.refresh(identity);
            if (refreshed.isEmpty()) {
                SecurityContextHolder.clearContext();
                filterChain.doFilter(request, response);
                return;
            }
            WebIdentity current = refreshed.get();
            authentication = org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(
                    current, null, List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_" + current.role()))
            );
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }
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
    }
}
