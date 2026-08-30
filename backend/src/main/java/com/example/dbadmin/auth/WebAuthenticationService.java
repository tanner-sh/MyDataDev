package com.example.dbadmin.auth;

import com.example.dbadmin.config.AppProperties;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class WebAuthenticationService {
    private static final long ATTEMPT_WINDOW_MILLIS = 5 * 60 * 1_000L;
    private static final int MAX_TRACKED_ATTEMPTS = 20_000;
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

    /**
     * 校验口令并做失败限流。
     *
     * <p>按两个维度分别计数：来源地址和用户名。只按地址计数是不够的 —— web profile 开了
     * {@code server.forward-headers-strategy: framework}，{@code getRemoteAddr()} 会被
     * {@code X-Forwarded-For} 改写，攻击者每次换一个伪造地址就能把锁定完全绕过，顺带把计数表
     * 撑爆。用户名维度换不掉：要爆破某个账号就必须一直送那个用户名。</p>
     */
    public LoginResult authenticate(String remoteAddress, String username, String password) {
        if (!enabled()) return LoginResult.invalid();
        long now = clock.millis();
        List<String> keys = attemptKeys(remoteAddress, username);
        long lockedUntil = keys.stream()
                .map(attempts::get)
                .filter(state -> state != null && state.lockedUntil > now)
                .mapToLong(AttemptState::lockedUntil)
                .max().orElse(0);
        if (lockedUntil > now) return LoginResult.locked(Math.max(1, (lockedUntil - now + 999) / 1_000));

        Optional<WebIdentity> identity = provider().authenticate(username, password);
        if (identity.isPresent()) {
            keys.forEach(attempts::remove);
            return LoginResult.success(identity.get());
        }
        evictStale(now);
        boolean locked = false;
        for (String key : keys) {
            AttemptState failed = attempts.compute(key, (ignored, state) -> {
                boolean fresh = state == null || now - state.windowStarted > ATTEMPT_WINDOW_MILLIS;
                int count = fresh ? 1 : state.failures + 1;
                long windowStarted = fresh ? now : state.windowStarted;
                long until = count >= properties.getMaxFailedAttempts() ? now + properties.getLockSeconds() * 1_000L : 0;
                return new AttemptState(count, windowStarted, until);
            });
            locked |= failed.lockedUntil > now;
        }
        if (locked) return LoginResult.locked(properties.getLockSeconds());
        return LoginResult.invalid();
    }

    private List<String> attemptKeys(String remoteAddress, String username) {
        String normalized = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
        return List.of(
                "addr:" + (remoteAddress == null ? "" : remoteAddress),
                "user:" + (normalized.length() <= 120 ? normalized : normalized.substring(0, 120))
        );
    }

    /**
     * 计数表的上限。条目只在登录成功时被删，失败计数本身是匿名请求就能创建的，不清理的话
     * 换地址或换用户名刷一遍就能把堆吃光。先扔掉窗口和锁定都过期的，还超就按窗口起始时间
     * 从旧到新淘汰 —— 淘汰旧条目最多让一次爆破多试几轮，留着不管则是进程被打死。
     */
    private void evictStale(long now) {
        if (attempts.size() <= MAX_TRACKED_ATTEMPTS) return;
        attempts.entrySet().removeIf(entry -> {
            AttemptState state = entry.getValue();
            return state.lockedUntil <= now && now - state.windowStarted > ATTEMPT_WINDOW_MILLIS;
        });
        if (attempts.size() <= MAX_TRACKED_ATTEMPTS) return;
        attempts.entrySet().stream()
                .sorted(Comparator.comparingLong(entry -> entry.getValue().windowStarted))
                .limit(attempts.size() - MAX_TRACKED_ATTEMPTS)
                .map(Map.Entry::getKey)
                .toList()
                .forEach(attempts::remove);
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
