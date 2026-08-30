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

    /**
     * web profile 下 getRemoteAddr() 会被 X-Forwarded-For 改写，所以来源地址是攻击者可控的。
     * 用户名维度换不掉：针对某个账号的爆破必须一直送同一个用户名。
     */
    @Test
    void rotatingRemoteAddressDoesNotBypassTheLockForOneAccount() {
        AppProperties properties = properties();
        properties.getAuth().setMaxFailedAttempts(3);
        WebAuthenticationService service = new WebAuthenticationService(
                properties, List.of(localProvider()),
                Clock.fixed(Instant.parse("2026-08-29T12:00:00Z"), ZoneOffset.UTC)
        );
        service.validateConfiguration();

        assertThat(service.authenticate("10.0.0.1", "operator", "wrong").locked()).isFalse();
        assertThat(service.authenticate("10.0.0.2", "operator", "wrong").locked()).isFalse();
        assertThat(service.authenticate("10.0.0.3", "operator", "wrong").locked()).isTrue();
        // 换一个全新的地址也拿不到尝试机会，而且正确口令同样被挡住。
        assertThat(service.authenticate("10.0.0.4", "operator", "correct-horse-battery-staple").locked()).isTrue();
        // 锁的是这个账号，不是整台服务器。
        assertThat(service.authenticate("10.0.0.4", "someone-else", "wrong").locked()).isFalse();
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
