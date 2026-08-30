package com.example.dbadmin.auth;

import com.example.dbadmin.config.AppProperties;
import com.example.dbadmin.repo.UserAccountRepository;
import jakarta.annotation.PostConstruct;
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
    private final AppProperties.Auth properties;
    private final AppProperties.Oidc oidc;

    public OidcIdentityProvider(UserAccountRepository repository, AppProperties appProperties) {
        this.repository = repository;
        this.properties = appProperties.getAuth();
        this.oidc = properties.getOidc();
    }

    /**
     * OIDC 模式下没配管理员组 = 这套部署永远不会有管理员，只能拦在启动阶段。
     *
     * <p>角色完全由 {@code admin-groups} 与 groups 声明的交集决定，而且每次 SSO 登录都会用
     * 这个结果覆写 {@code app_user.role} —— 组为空时人人都是 OPERATOR，连之前从 LOCAL 模式
     * 迁移过来的管理员都会在下一次登录时被降级。界面上又没有别的补救口子：
     * {@code /api/admin/**}、{@code /api/audit/**}、{@code /api/mcp/**} 全要 ADMIN，而 SSO
     * 账号的角色不接受手工修改。等到那时候只能改配置重启，不如现在就说清楚。</p>
     */
    @PostConstruct
    void requireReachableAdministrator() {
        if (!PROVIDER.equalsIgnoreCase(properties.getMode())) return;
        boolean hasAdminGroup = oidc.getAdminGroups().stream().anyMatch(group -> group != null && !group.isBlank());
        if (hasAdminGroup) return;
        throw new IllegalStateException(
                "启用 OIDC 认证时必须配置 app.auth.oidc.admin-groups（APP_AUTH_OIDC_ADMIN_GROUPS），"
                        + "否则所有 SSO 用户都会是 OPERATOR，没有人能进入管理、审计与 MCP 功能。"
        );
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

        Set<String> externalGroups = claimValues(oidcUser.getClaims().get(oidc.getGroupsClaim()));
        String role = intersects(externalGroups, oidc.getAdminGroups()) ? "ADMIN" : "OPERATOR";
        String preferred = claim(oidcUser, oidc.getUsernameClaim());
        if (preferred == null) preferred = claim(oidcUser, "email");
        if (preferred == null) preferred = "oidc-" + shortHash(subject);
        String username = availableUsername(preferred, existing.map(UserAccount::id).orElse(null), subject);
        String displayName = claim(oidcUser, oidc.getDisplayNameClaim());
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
        oidc.getGroupMappings().forEach((external, local) -> {
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
