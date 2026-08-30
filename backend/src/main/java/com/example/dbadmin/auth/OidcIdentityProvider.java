package com.example.dbadmin.auth;

import com.example.dbadmin.config.AppProperties;
import com.example.dbadmin.repo.UserAccountRepository;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** 标准 OIDC 身份落库：provider + sub 是稳定关联键，用户名变化不会创建重复账号。 */
@Component
@DependsOnDatabaseInitialization
public class OidcIdentityProvider implements WebIdentityProvider {
    public static final String PROVIDER = "OIDC";
    private final UserAccountRepository repository;
    private final AppProperties.Oidc properties;

    public OidcIdentityProvider(UserAccountRepository repository, AppProperties appProperties) {
        this.repository = repository;
        this.properties = appProperties.getAuth().getOidc();
    }

    @Override public String id() { return PROVIDER; }
    @Override public Optional<WebIdentity> authenticate(String username, String credential) { return Optional.empty(); }
    @Override public boolean passwordLogin() { return false; }
    @Override public String loginUrl() { return "/oauth2/authorization/mydatadev"; }

    @Override
    public Optional<WebIdentity> refresh(WebIdentity identity) {
        if (!PROVIDER.equalsIgnoreCase(identity.provider())) return Optional.empty();
        return repository.findById(identity.userId()).filter(UserAccount::enabled)
                .filter(account -> PROVIDER.equalsIgnoreCase(account.provider()))
                .filter(account -> account.authVersion() == identity.authVersion())
                .map(UserAccount::identity);
    }

    @Transactional
    public Optional<WebIdentity> login(OidcUser oidcUser) {
        String subject = trim(oidcUser.getSubject());
        if (subject == null) throw new IllegalArgumentException("OIDC 身份缺少 sub 声明");
        Optional<UserAccount> existing = repository.findByProviderSubject(PROVIDER, subject);
        if (existing.isPresent() && !existing.get().enabled()) return Optional.empty();

        Set<String> externalGroups = claimValues(oidcUser.getClaims().get(properties.getGroupsClaim()));
        String role = intersects(externalGroups, properties.getAdminGroups()) ? "ADMIN" : "OPERATOR";
        String preferred = claim(oidcUser, properties.getUsernameClaim());
        if (preferred == null) preferred = claim(oidcUser, "email");
        if (preferred == null) preferred = "oidc-" + shortHash(subject);
        String username = availableUsername(preferred, existing.map(UserAccount::id).orElse(null), subject);
        String displayName = claim(oidcUser, properties.getDisplayNameClaim());
        if (displayName == null) displayName = username;
        if (displayName.length() > 120) displayName = displayName.substring(0, 120);

        long id;
        if (existing.isEmpty()) {
            try {
                id = repository.insert(PROVIDER, subject, username, displayName, null, role, true);
            } catch (DuplicateKeyException race) {
                id = repository.findByProviderSubject(PROVIDER, subject).orElseThrow(() -> race).id();
            }
        } else id = existing.get().id();
        repository.updateExternalProfile(id, username, displayName, role);
        repository.syncExternalGroups(id, PROVIDER, mappedGroups(externalGroups));
        return repository.findById(id).filter(UserAccount::enabled).map(UserAccount::identity);
    }

    private List<String> mappedGroups(Set<String> externalGroups) {
        List<String> result = new ArrayList<>();
        properties.getGroupMappings().forEach((external, local) -> {
            if (externalGroups.contains(external)) result.add(local);
        });
        return result;
    }

    private String availableUsername(String raw, Long userId, String subject) {
        String base = LocalDatabaseIdentityProvider.normalizeUsername(raw).replaceAll("\\s+", "-");
        if (base.isBlank()) base = "oidc-" + shortHash(subject);
        if (base.length() > 120) base = base.substring(0, 120);
        if (!repository.usernameBelongsToOther(base, userId)) return base;
        String suffix = "-" + shortHash(subject);
        String candidate = base.substring(0, Math.min(base.length(), 120 - suffix.length())) + suffix;
        if (!repository.usernameBelongsToOther(candidate, userId)) return candidate;
        throw new IllegalStateException("无法为 OIDC 身份生成唯一用户名");
    }

    private static boolean intersects(Set<String> actual, Collection<String> configured) {
        Set<String> normalized = new LinkedHashSet<>();
        for (String value : actual) normalized.add(value.toLowerCase(Locale.ROOT));
        for (String value : configured) if (value != null && normalized.contains(value.trim().toLowerCase(Locale.ROOT))) return true;
        return false;
    }

    private static Set<String> claimValues(Object value) {
        Set<String> result = new LinkedHashSet<>();
        if (value instanceof Collection<?> values) values.forEach(item -> { if (item != null) result.add(item.toString()); });
        else if (value instanceof String string) for (String item : string.split("[, ]+")) if (!item.isBlank()) result.add(item.trim());
        return result;
    }

    private static String claim(OidcUser user, String name) {
        if (name == null || name.isBlank()) return null;
        Object value = user.getClaims().get(name);
        return value == null ? null : trim(value.toString());
    }

    private static String trim(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String shortHash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest, 0, 4);
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
}
