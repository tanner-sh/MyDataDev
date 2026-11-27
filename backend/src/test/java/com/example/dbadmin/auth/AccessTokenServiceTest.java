package com.example.dbadmin.auth;

import com.example.dbadmin.api.ApiProblemException;
import com.example.dbadmin.config.AppProperties;
import com.example.dbadmin.dto.AccessTokenDtos.CreateAccessTokenRequest;
import com.example.dbadmin.repo.AccessTokenRepository;
import com.example.dbadmin.repo.AuditRepository;
import com.example.dbadmin.repo.ConnectionAccessRepository;
import com.example.dbadmin.repo.ConnectionRepository;
import com.example.dbadmin.repo.MetadataWriteQueue;
import com.example.dbadmin.repo.UserAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AccessTokenServiceTest {
    private AccessTokenRepository tokenRepository;
    private UserAccountRepository userRepository;
    private ConnectionRepository connectionRepository;
    private ConnectionAccessRepository connectionAccessRepository;
    private AuditRepository audit;
    private MetadataWriteQueue writes;
    private MutableClock clock;

    private UserAccount localUser;
    private AccessToken storedToken;

    @BeforeEach
    void setUp() {
        tokenRepository = mock(AccessTokenRepository.class);
        userRepository = mock(UserAccountRepository.class);
        connectionRepository = mock(ConnectionRepository.class);
        connectionAccessRepository = mock(ConnectionAccessRepository.class);
        audit = mock(AuditRepository.class);
        writes = mock(MetadataWriteQueue.class);
        clock = new MutableClock(Instant.parse("2026-11-27T00:00:00Z"));

        localUser = new UserAccount(
                7L, "LOCAL", "alice", "alice", "Alice", "$2a$12$hash", "OPERATOR", true, 3L,
                null, clock.instant(), clock.instant()
        );
        when(userRepository.findById(7L)).thenAnswer(invocation -> Optional.of(localUser));
        when(connectionAccessRepository.existingConnectionIds(any())).thenReturn(Set.of(10L, 11L));
        when(connectionAccessRepository.allowedConnectionIds(7L)).thenReturn(Set.of(10L));
        when(tokenRepository.findById(anyString())).thenAnswer(invocation -> {
            String id = invocation.getArgument(0);
            return storedToken != null && storedToken.id().equals(id) ? Optional.of(storedToken) : Optional.empty();
        });
    }

    private AccessTokenService serviceWithMode(String mode, String oidcIssuer) {
        AppProperties properties = new AppProperties();
        properties.getAuth().setMode(mode);
        if (oidcIssuer != null) {
            properties.getAuth().getOidc().setIssuerUri(oidcIssuer);
        }
        LocalDatabaseIdentityProvider local = new LocalDatabaseIdentityProvider(userRepository, properties);
        OidcIdentityProvider oidc = new OidcIdentityProvider(userRepository, properties);
        WebAuthenticationService authService = new WebAuthenticationService(properties, List.of(local, oidc), clock);

        AccessTokenService service = new AccessTokenService(
                tokenRepository, userRepository, connectionRepository, connectionAccessRepository,
                authService, audit, writes, clock
        );

        org.mockito.Mockito.doAnswer(invocation -> {
            String id = invocation.getArgument(0);
            long userId = invocation.getArgument(1);
            String name = invocation.getArgument(2);
            String secretHash = invocation.getArgument(3);
            long issuedAuthVersion = invocation.getArgument(4);
            String issuedProvider = invocation.getArgument(5);
            String scope = invocation.getArgument(6);
            @SuppressWarnings("unchecked")
            var connIds = (java.util.Collection<Long>) invocation.getArgument(7);
            Instant expiresAt = invocation.getArgument(8);
            storedToken = new AccessToken(
                    id, userId, name, secretHash, issuedAuthVersion, issuedProvider,
                    scope, Set.copyOf(connIds), expiresAt, null, null, null, clock.instant()
            );
            return null;
        }).when(tokenRepository).insert(anyString(), anyLong(), anyString(), anyString(), anyLong(), anyString(), anyString(), any(), any());

        when(tokenRepository.revoke(anyString(), any())).thenAnswer(invocation -> {
            String id = invocation.getArgument(0);
            Instant revokedAt = invocation.getArgument(1);
            if (storedToken != null && storedToken.id().equals(id) && storedToken.revokedAt() == null) {
                storedToken = new AccessToken(
                        storedToken.id(), storedToken.userId(), storedToken.name(), storedToken.secretHash(),
                        storedToken.issuedAuthVersion(), storedToken.issuedProvider(), storedToken.connectionScope(),
                        storedToken.connectionIds(), storedToken.expiresAt(), storedToken.lastUsedAt(),
                        storedToken.lastUsedIp(), revokedAt, storedToken.createdAt()
                );
                return true;
            }
            return false;
        });

        return service;
    }

    @Test
    void createsHashedTokenAndAuthenticatesUntilExpiredRevokedDisabledOrAuthVersionChanged() {
        AccessTokenService service = serviceWithMode("LOCAL", null);
        var created = service.create(new CreateAccessTokenRequest("ci-token", 30, "LISTED", List.of(10L)), localUser.identity());

        assertThat(created.token()).startsWith("mdd_pat_" + created.tokenInfo().id() + "_");
        assertThat(storedToken.secretHash()).hasSize(64).doesNotContain(created.token());
        assertThat(storedToken.issuedAuthVersion()).isEqualTo(3L);
        assertThat(storedToken.connectionIds()).containsExactly(10L);

        // 正常认证通过
        Optional<AccessTokenAuthentication> auth = service.authenticate(created.token(), "127.0.0.1");
        assertThat(auth).isPresent();
        assertThat(auth.get().getName()).isEqualTo("alice");
        assertThat(auth.get().tokenId()).isEqualTo(created.tokenInfo().id());
        assertThat(auth.get().isListedScope()).isTrue();
        assertThat(auth.get().allowedConnectionIds()).containsExactly(10L);

        // 修改密码/角色后 auth_version 递增，旧令牌立即失效
        localUser = new UserAccount(
                7L, "LOCAL", "alice", "alice", "Alice", "$2a$12$new", "OPERATOR", true, 4L,
                null, clock.instant(), clock.instant()
        );
        assertThat(service.authenticate(created.token(), "127.0.0.1")).isEmpty();

        // 恢复 auth_version 但停用账号，仍然失效
        localUser = new UserAccount(
                7L, "LOCAL", "alice", "alice", "Alice", "$2a$12$hash", "OPERATOR", false, 3L,
                null, clock.instant(), clock.instant()
        );
        assertThat(service.authenticate(created.token(), "127.0.0.1")).isEmpty();

        // 恢复启用但推进时钟超过有效期，立即过期
        localUser = new UserAccount(
                7L, "LOCAL", "alice", "alice", "Alice", "$2a$12$hash", "OPERATOR", true, 3L,
                null, clock.instant(), clock.instant()
        );
        clock.advance(Duration.ofDays(31));
        assertThat(service.authenticate(created.token(), "127.0.0.1")).isEmpty();

        // 回退时间并吊销后立即失效
        clock.advance(Duration.ofDays(-30));
        assertThat(service.authenticate(created.token(), "127.0.0.1")).isPresent();
        service.revokeOwn(created.tokenInfo().id(), localUser.identity());
        assertThat(service.authenticate(created.token(), "127.0.0.1")).isEmpty();
    }

    @Test
    void rejectsExpiryOutOfRangeAndInaccessibleWhitelistConnections() {
        AccessTokenService service = serviceWithMode("LOCAL", null);
        assertThatThrownBy(() -> service.create(new CreateAccessTokenRequest("too-long", 366, "ALL", List.of()), localUser.identity()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create(new CreateAccessTokenRequest("empty-list", 30, "LISTED", List.of()), localUser.identity()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create(new CreateAccessTokenRequest("forbidden-conn", 30, "LISTED", List.of(11L)), localUser.identity()))
                .isInstanceOf(ApiProblemException.class)
                .satisfies(ex -> assertThat(((ApiProblemException) ex).status()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void oidcTokenInvalidatedWhenConfiguredIssuerChanges() {
        String issuerA = "https://idp-a.example.com";
        String subjectA = "iss1:" + AccessTokenService.sha256Hex(issuerA) + ":subhash";
        localUser = new UserAccount(
                7L, "OIDC", subjectA, "alice", "Alice", null, "OPERATOR", true, 1L,
                null, clock.instant(), clock.instant()
        );
        AccessTokenService serviceA = serviceWithMode("OIDC", issuerA);
        var created = serviceA.create(new CreateAccessTokenRequest("sso-ci", 30, "LISTED", List.of(10L)), localUser.identity());
        assertThat(serviceA.authenticate(created.token(), "127.0.0.1")).isPresent();

        // 切换 OIDC issuer 后旧令牌失效
        AccessTokenService serviceB = serviceWithMode("OIDC", "https://idp-b.example.com");
        assertThat(serviceB.authenticate(created.token(), "127.0.0.1")).isEmpty();
    }

    @Test
    void returnsConflictWhenAuthIsDisabled() {
        AccessTokenService service = serviceWithMode("DISABLED", null);
        assertThatThrownBy(() -> service.listForUser(localUser.identity()))
                .isInstanceOf(ApiProblemException.class)
                .satisfies(ex -> assertThat(((ApiProblemException) ex).status()).isEqualTo(HttpStatus.CONFLICT));
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> current;

        MutableClock(Instant initial) {
            this.current = new AtomicReference<>(initial);
        }

        void advance(Duration delta) {
            current.updateAndGet(now -> now.plus(delta));
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current.get();
        }
    }
}
