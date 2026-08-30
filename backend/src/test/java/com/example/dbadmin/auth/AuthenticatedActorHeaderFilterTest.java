package com.example.dbadmin.auth;

import com.example.dbadmin.config.AppProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class AuthenticatedActorHeaderFilterTest {
    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void replacesSpoofedAuditActorWithAuthenticatedPrincipal() throws Exception {
        AppProperties properties = new AppProperties();
        properties.getAuth().setMode("LOCAL");
        WebIdentity identity = new WebIdentity(1L, "LOCAL", "operator", "operator", "Operator", "ADMIN", 0L);
        WebIdentityProvider provider = new WebIdentityProvider() {
            @Override
            public String id() {
                return "LOCAL";
            }

            @Override
            public Optional<WebIdentity> authenticate(String username, String credential) {
                return Optional.empty();
            }

            @Override
            public Optional<WebIdentity> refresh(WebIdentity current) {
                return Optional.of(identity);
            }
        };
        WebAuthenticationService service = new WebAuthenticationService(properties, List.of(provider), Clock.systemUTC());
        service.validateConfiguration();
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                identity, null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
        ));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/data/commit");
        request.addHeader("X-User", "spoofed-user");
        MockFilterChain chain = new MockFilterChain();

        new AuthenticatedActorHeaderFilter(service).doFilterInternal(request, new MockHttpServletResponse(), chain);

        assertThat(((HttpServletRequest) chain.getRequest()).getHeader("X-User")).isEqualTo("operator");
    }

    @Test
    void clearsThirdPartyAuthenticationUntilOidcIsMappedToWebIdentity() throws Exception {
        AppProperties properties = new AppProperties();
        properties.getAuth().setMode("LOCAL");
        WebIdentityProvider provider = new WebIdentityProvider() {
            @Override public String id() { return "LOCAL"; }
            @Override public Optional<WebIdentity> authenticate(String username, String credential) { return Optional.empty(); }
        };
        WebAuthenticationService service = new WebAuthenticationService(properties, List.of(provider), Clock.systemUTC());
        service.validateConfiguration();
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                "foreign-principal", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));

        new AuthenticatedActorHeaderFilter(service).doFilterInternal(
                new MockHttpServletRequest("GET", "/api/connections"), new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
