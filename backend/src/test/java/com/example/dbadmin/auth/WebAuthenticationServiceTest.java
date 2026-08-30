package com.example.dbadmin.auth;

import com.example.dbadmin.config.AppProperties;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebAuthenticationServiceTest {
    @Test
    void refusesConfiguredModeWithoutMatchingIdentityProvider() {
        AppProperties properties = properties();

        assertThatThrownBy(() -> new WebAuthenticationService(properties, List.of(), Clock.systemUTC()).validateConfiguration())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("没有可用的 Web 身份提供器");
    }

    @Test
    void locksRemoteAddressAfterConfiguredFailures() {
        AppProperties properties = properties();
        properties.getAuth().setMaxFailedAttempts(2);
        WebAuthenticationService service = new WebAuthenticationService(
                properties, List.of(localProvider()),
                Clock.fixed(Instant.parse("2026-08-29T12:00:00Z"), ZoneOffset.UTC)
        );
        service.validateConfiguration();

        assertThat(service.authenticate("127.0.0.1", "operator", "wrong").locked()).isFalse();
        assertThat(service.authenticate("127.0.0.1", "someone-else", "wrong").locked()).isTrue();
        assertThat(service.authenticate("127.0.0.1", "operator", "correct-horse-battery-staple").locked()).isTrue();
    }

    private AppProperties properties() {
        AppProperties properties = new AppProperties();
        properties.getAuth().setMode("LOCAL");
        return properties;
    }

    private WebIdentityProvider localProvider() {
        WebIdentity identity = new WebIdentity(1L, "LOCAL", "operator", "operator", "Operator", "ADMIN", 0L);
        return new WebIdentityProvider() {
            @Override
            public String id() {
                return "LOCAL";
            }

            @Override
            public Optional<WebIdentity> authenticate(String username, String credential) {
                return "operator".equals(username) && "correct-horse-battery-staple".equals(credential)
                        ? Optional.of(identity) : Optional.empty();
            }
        };
    }
}
