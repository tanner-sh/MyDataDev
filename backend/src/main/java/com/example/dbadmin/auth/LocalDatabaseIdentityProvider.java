package com.example.dbadmin.auth;

import com.example.dbadmin.config.AppProperties;
import com.example.dbadmin.repo.UserAccountRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Optional;

@Component
@DependsOnDatabaseInitialization
public class LocalDatabaseIdentityProvider implements WebIdentityProvider {
    public static final String PROVIDER = "LOCAL";
    private final UserAccountRepository repository;
    private final AppProperties.Auth properties;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);
    private final String dummyHash = encoder.encode("mydatadev-dummy-password-never-valid");

    public LocalDatabaseIdentityProvider(UserAccountRepository repository, AppProperties properties) {
        this.repository = repository;
        this.properties = properties.getAuth();
    }

    @PostConstruct
    void bootstrapFirstAdministrator() {
        if (!PROVIDER.equalsIgnoreCase(properties.getMode())) return;
        if (repository.count() > 0) {
            // 初始化密码只在空账号库时使用，升级完成后不需要长期留在进程配置中。
            properties.setPassword(null);
            return;
        }
        String username = normalizeUsername(properties.getUsername());
        if (username.isBlank()) throw new IllegalStateException("首次启用 Web 认证时必须配置 DB_ADMIN_WEB_USERNAME");
        if (properties.getPassword() == null || properties.getPassword().length() < 12) {
            throw new IllegalStateException("首次启用 Web 认证时必须配置至少 12 位的 DB_ADMIN_WEB_PASSWORD");
        }
        repository.insert(PROVIDER, username, username, username, encoder.encode(properties.getPassword()), "ADMIN", true);
        properties.setPassword(null);
    }

    @Override
    public String id() {
        return PROVIDER;
    }

    @Override
    public Optional<WebIdentity> authenticate(String username, String credential) {
        Optional<UserAccount> candidate = repository.findByProviderSubject(PROVIDER, normalizeUsername(username));
        String hash = candidate.map(UserAccount::passwordHash).filter(value -> value != null && !value.isBlank()).orElse(dummyHash);
        boolean passwordMatches = credential != null && encoder.matches(credential, hash);
        if (candidate.isEmpty() || !candidate.get().enabled() || !passwordMatches) return Optional.empty();
        repository.recordLogin(candidate.get().id());
        return Optional.of(candidate.get().identity());
    }

    @Override
    public Optional<WebIdentity> refresh(WebIdentity identity) {
        if (!PROVIDER.equalsIgnoreCase(identity.provider())) return Optional.empty();
        return repository.findById(identity.userId())
                .filter(UserAccount::enabled)
                .filter(account -> PROVIDER.equalsIgnoreCase(account.provider()))
                .filter(account -> account.authVersion() == identity.authVersion())
                .map(UserAccount::identity);
    }

    public String encodePassword(String password) {
        if (password == null || password.length() < 12) throw new IllegalArgumentException("密码至少需要 12 位");
        return encoder.encode(password);
    }

    public static String normalizeUsername(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
