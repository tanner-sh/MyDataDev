package com.example.dbadmin.auth;

import com.example.dbadmin.config.AppProperties;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class WebAuthenticationService {
    private static final long ATTEMPT_WINDOW_MILLIS = 5 * 60 * 1_000L;
    private final AppProperties.Auth properties;
    private final List<WebIdentityProvider> providers;
    private final Map<String, AttemptState> attempts = new ConcurrentHashMap<>();
    private final Clock clock;

    @Autowired
    public WebAuthenticationService(AppProperties properties, List<WebIdentityProvider> providers) {
        this(properties, providers, Clock.systemUTC());
    }

    WebAuthenticationService(AppProperties properties, List<WebIdentityProvider> providers, Clock clock) {
        this.properties = properties.getAuth();
        this.providers = List.copyOf(providers);
        this.clock = clock;
    }

    @PostConstruct
    void validateConfiguration() {
        String mode = normalizedMode();
        if (!mode.equals("DISABLED")) {
            if (providers.stream().noneMatch(provider -> provider.id().equalsIgnoreCase(mode))) {
                throw new IllegalStateException("没有可用的 Web 身份提供器：" + mode);
            }
            if (properties.getMaxFailedAttempts() < 1 || properties.getLockSeconds() < 1) {
                throw new IllegalStateException("Web 认证的失败次数和锁定时间必须大于 0");
            }
        }
    }

    public boolean enabled() {
        return !normalizedMode().equals("DISABLED");
    }

    public boolean cookieSecure() {
        return properties.isCookieSecure();
    }

    public LoginResult authenticate(String remoteAddress, String username, String password) {
        if (!enabled()) return LoginResult.invalid();
        long now = clock.millis();
        String key = remoteAddress == null ? "" : remoteAddress;
        AttemptState previous = attempts.get(key);
        if (previous != null && previous.lockedUntil > now) {
            return LoginResult.locked(Math.max(1, (previous.lockedUntil - now + 999) / 1_000));
        }
        Optional<WebIdentity> identity = provider().authenticate(username, password);
        if (identity.isPresent()) {
            attempts.remove(key);
            return LoginResult.success(identity.get());
        }
        AttemptState failed = attempts.compute(key, (ignored, state) -> {
            int count = state == null || now - state.windowStarted > ATTEMPT_WINDOW_MILLIS ? 1 : state.failures + 1;
            long windowStarted = state == null || now - state.windowStarted > ATTEMPT_WINDOW_MILLIS ? now : state.windowStarted;
            long lockedUntil = count >= properties.getMaxFailedAttempts() ? now + properties.getLockSeconds() * 1_000L : 0;
            return new AttemptState(count, windowStarted, lockedUntil);
        });
        if (failed.lockedUntil > now) return LoginResult.locked(properties.getLockSeconds());
        return LoginResult.invalid();
    }

    public Optional<WebIdentity> refresh(WebIdentity identity) {
        if (!enabled() || identity == null) return Optional.empty();
        return provider().refresh(identity);
    }

    public String providerId() {
        return enabled() ? provider().id() : "DISABLED";
    }

    public boolean passwordLogin() {
        return !enabled() || provider().passwordLogin();
    }

    public String loginUrl() {
        return enabled() ? provider().loginUrl() : null;
    }

    private WebIdentityProvider provider() {
        return providers.stream().filter(candidate -> candidate.id().equalsIgnoreCase(normalizedMode()))
                .findFirst().orElseThrow(() -> new IllegalStateException("Web 身份提供器不可用：" + normalizedMode()));
    }

    private String normalizedMode() {
        return properties.getMode() == null ? "DISABLED" : properties.getMode().trim().toUpperCase(Locale.ROOT);
    }

    private record AttemptState(int failures, long windowStarted, long lockedUntil) {}

    public record LoginResult(boolean authenticated, boolean locked, long retryAfterSeconds, WebIdentity identity) {
        static LoginResult success(WebIdentity identity) { return new LoginResult(true, false, 0, identity); }
        static LoginResult invalid() { return new LoginResult(false, false, 0, null); }
        static LoginResult locked(long seconds) { return new LoginResult(false, true, seconds, null); }
    }
}
