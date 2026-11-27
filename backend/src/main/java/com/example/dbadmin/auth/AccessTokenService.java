package com.example.dbadmin.auth;

import com.example.dbadmin.api.ApiProblemException;
import com.example.dbadmin.dto.AccessTokenDtos.AccessTokenResponse;
import com.example.dbadmin.dto.AccessTokenDtos.CreateAccessTokenRequest;
import com.example.dbadmin.dto.AccessTokenDtos.CreatedAccessTokenResponse;
import com.example.dbadmin.dto.AccessTokenDtos.TokenConnectionOption;
import com.example.dbadmin.dto.AccessTokenDtos.UpdateAccessTokenConnectionsRequest;
import com.example.dbadmin.repo.AccessTokenRepository;
import com.example.dbadmin.repo.AuditRepository;
import com.example.dbadmin.repo.ConnectionAccessRepository;
import com.example.dbadmin.repo.ConnectionRepository;
import com.example.dbadmin.repo.MetadataWriteQueue;
import com.example.dbadmin.repo.UserAccountRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class AccessTokenService {
    public static final String TOKEN_PREFIX = "mdd_pat_";
    public static final int DEFAULT_EXPIRES_IN_DAYS = 90;
    public static final int MAX_EXPIRES_IN_DAYS = 365;
    private static final long LAST_USED_THROTTLE_MILLIS = 60_000L;
    private static final int MAX_TRACKED_TOUCHES = 10_000;

    private final AccessTokenRepository repository;
    private final UserAccountRepository users;
    private final ConnectionRepository connections;
    private final ConnectionAccessRepository connectionAccess;
    private final WebAuthenticationService authenticationService;
    private final AuditRepository audit;
    private final MetadataWriteQueue writes;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Long> lastTouchMillis = new ConcurrentHashMap<>();

    @Autowired
    public AccessTokenService(
            AccessTokenRepository repository,
            UserAccountRepository users,
            ConnectionRepository connections,
            ConnectionAccessRepository connectionAccess,
            WebAuthenticationService authenticationService,
            AuditRepository audit,
            MetadataWriteQueue writes
    ) {
        this(repository, users, connections, connectionAccess, authenticationService, audit, writes, Clock.systemUTC());
    }

    AccessTokenService(
            AccessTokenRepository repository,
            UserAccountRepository users,
            ConnectionRepository connections,
            ConnectionAccessRepository connectionAccess,
            WebAuthenticationService authenticationService,
            AuditRepository audit,
            MetadataWriteQueue writes,
            Clock clock
    ) {
        this.repository = repository;
        this.users = users;
        this.connections = connections;
        this.connectionAccess = connectionAccess;
        this.authenticationService = authenticationService;
        this.audit = audit;
        this.writes = writes;
        this.clock = clock;
    }

    @Transactional
    public CreatedAccessTokenResponse create(CreateAccessTokenRequest request, WebIdentity actor) {
        requireAuthEnabled();
        UserAccount account = users.findById(actor.userId())
                .filter(UserAccount::enabled)
                .orElseThrow(() -> new ApiProblemException(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", "当前账号不可用。"));

        String name = normalizeName(request.name());
        int expiresInDays = request.expiresInDays() == null ? DEFAULT_EXPIRES_IN_DAYS : request.expiresInDays();
        if (expiresInDays < 1 || expiresInDays > MAX_EXPIRES_IN_DAYS) {
            throw new IllegalArgumentException("令牌有效期必须在 1 到 " + MAX_EXPIRES_IN_DAYS + " 天之间。");
        }
        String scope = normalizeScope(request.connectionScope());
        Set<Long> connectionIds = validateConnections(scope, request.connectionIds(), account);

        String id = generateId();
        String secret = generateSecret();
        String plaintext = TOKEN_PREFIX + id + "_" + secret;
        Instant expiresAt = clock.instant().plus(Duration.ofDays(expiresInDays));

        repository.insert(
                id,
                account.id(),
                name,
                sha256Hex(secret),
                account.authVersion(),
                account.provider(),
                scope,
                connectionIds,
                expiresAt
        );
        audit.global(actor.username(), "ACCESS_TOKEN_CREATE", "pat:" + id,
                "name=" + name + ", scope=" + scope + ", connections=" + connectionIds.size()
                        + ", expiresInDays=" + expiresInDays);

        AccessToken stored = repository.findById(id).orElseThrow();
        return new CreatedAccessTokenResponse(toResponse(stored, account), plaintext);
    }

    public List<AccessTokenResponse> listForUser(WebIdentity actor) {
        requireAuthEnabled();
        UserAccount account = users.findById(actor.userId()).orElse(null);
        return repository.findByUserId(actor.userId()).stream()
                .map(token -> toResponse(token, account))
                .toList();
    }

    public List<AccessTokenResponse> listAll() {
        requireAuthEnabled();
        Map<Long, UserAccount> byId = users.findAll().stream()
                .collect(Collectors.toMap(UserAccount::id, account -> account, (a, b) -> a));
        return repository.findAll().stream()
                .map(token -> toResponse(token, byId.get(token.userId())))
                .toList();
    }

    public List<TokenConnectionOption> availableConnections(WebIdentity actor) {
        requireAuthEnabled();
        boolean admin = "ADMIN".equals(actor.role());
        Set<Long> visibleIds = admin ? null : connectionAccess.allowedConnectionIds(actor.userId());
        return connections.findAll().stream()
                .filter(conn -> admin || visibleIds.contains(conn.id()))
                .map(conn -> {
                    boolean shared = connectionAccess.findPolicy(conn.id())
                            .map(policy -> !"RESTRICTED".equalsIgnoreCase(policy.accessMode()))
                            .orElse(true);
                    return new TokenConnectionOption(conn.id(), conn.name(), conn.dbType(), conn.environment(), shared);
                })
                .toList();
    }

    @Transactional
    public AccessTokenResponse updateConnections(String tokenId, UpdateAccessTokenConnectionsRequest request, WebIdentity actor) {
        requireAuthEnabled();
        AccessToken existing = repository.findById(tokenId)
                .orElseThrow(() -> new IllegalArgumentException("访问令牌不存在。"));
        if (existing.userId() != actor.userId()) {
            throw new ApiProblemException(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "只能修改自己创建的访问令牌。");
        }
        if (existing.isRevoked()) {
            throw new IllegalArgumentException("已吊销的访问令牌不能修改。");
        }
        UserAccount account = users.findById(actor.userId())
                .filter(UserAccount::enabled)
                .orElseThrow(() -> new ApiProblemException(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", "当前账号不可用。"));

        String scope = normalizeScope(request.connectionScope());
        Set<Long> connectionIds = validateConnections(scope, request.connectionIds(), account);
        repository.updateConnections(tokenId, scope, connectionIds);
        audit.global(actor.username(), "ACCESS_TOKEN_UPDATE_SCOPE", "pat:" + tokenId,
                "scope=" + scope + ", connections=" + connectionIds.size());
        return toResponse(repository.findById(tokenId).orElseThrow(), account);
    }

    @Transactional
    public void revokeOwn(String tokenId, WebIdentity actor) {
        requireAuthEnabled();
        AccessToken existing = repository.findById(tokenId)
                .orElseThrow(() -> new IllegalArgumentException("访问令牌不存在。"));
        if (existing.userId() != actor.userId()) {
            throw new ApiProblemException(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "只能吊销自己创建的访问令牌。");
        }
        if (repository.revoke(tokenId, clock.instant())) {
            audit.global(actor.username(), "ACCESS_TOKEN_REVOKE", "pat:" + tokenId, "name=" + existing.name());
        }
    }

    @Transactional
    public void revokeByAdmin(String tokenId, WebIdentity actor) {
        requireAuthEnabled();
        AccessToken existing = repository.findById(tokenId)
                .orElseThrow(() -> new IllegalArgumentException("访问令牌不存在。"));
        if (repository.revoke(tokenId, clock.instant())) {
            audit.global(actor.username(), "ACCESS_TOKEN_REVOKE", "pat:" + tokenId,
                    "name=" + existing.name() + ", ownerUserId=" + existing.userId() + ", byAdmin=true");
        }
    }

    /**
     * 校验个人访问令牌：
     * <ol>
     *   <li>格式、SHA-256 常量时间比对、未吊销、未过期；</li>
     *   <li>通过活动身份提供器的 {@code refresh} 核对账号启用状态、provider、OIDC issuer 以及
     *       签发时的 {@code issued_auth_version} —— 改密码、改角色或切换身份配置后旧令牌立即失效。</li>
     * </ol>
     */
    public Optional<AccessTokenAuthentication> authenticate(String rawToken, String remoteAddress) {
        if (!authenticationService.enabled() || rawToken == null || !rawToken.startsWith(TOKEN_PREFIX)) {
            return Optional.empty();
        }
        String rest = rawToken.substring(TOKEN_PREFIX.length());
        int separator = rest.indexOf('_');
        if (separator <= 0 || separator >= rest.length() - 1) return Optional.empty();
        String id = rest.substring(0, separator);
        String secret = rest.substring(separator + 1);
        if (id.length() > 24 || secret.isEmpty()) return Optional.empty();

        Optional<AccessToken> candidate = repository.findById(id);
        if (candidate.isEmpty()) return Optional.empty();
        AccessToken token = candidate.get();
        Instant now = clock.instant();
        if (!token.isActive(now)) return Optional.empty();

        String computedHash = sha256Hex(secret);
        if (!MessageDigest.isEqual(
                computedHash.getBytes(StandardCharsets.US_ASCII),
                token.secretHash().trim().getBytes(StandardCharsets.US_ASCII))) {
            return Optional.empty();
        }

        Optional<UserAccount> accountOpt = users.findById(token.userId());
        if (accountOpt.isEmpty()) return Optional.empty();
        UserAccount account = accountOpt.get();
        if (!token.issuedProvider().equalsIgnoreCase(account.provider())) return Optional.empty();

        // 使用签发时的 issuedAuthVersion 构造待刷新身份，复用 Local / OIDC 身份提供器的全部校验逻辑
        WebIdentity issuedIdentity = new WebIdentity(
                account.id(),
                token.issuedProvider(),
                account.subject(),
                account.username(),
                account.displayName(),
                account.role(),
                token.issuedAuthVersion()
        );
        Optional<WebIdentity> refreshed = authenticationService.refresh(issuedIdentity);
        if (refreshed.isEmpty()) return Optional.empty();

        recordUsage(token.id(), now, remoteAddress);
        return Optional.of(new AccessTokenAuthentication(
                refreshed.get(),
                token.id(),
                token.name(),
                token.connectionScope(),
                token.connectionIds()
        ));
    }

    public static String safeTokenIdHint(String rawToken) {
        if (rawToken == null || !rawToken.startsWith(TOKEN_PREFIX)) return "unknown";
        String rest = rawToken.substring(TOKEN_PREFIX.length());
        int separator = rest.indexOf('_');
        String id = separator > 0 ? rest.substring(0, separator) : rest;
        String clean = id.replaceAll("[^A-Za-z0-9_-]", "");
        if (clean.isEmpty()) return "unknown";
        return clean.length() <= 24 ? clean : clean.substring(0, 24);
    }

    private void recordUsage(String tokenId, Instant now, String remoteAddress) {
        long nowMillis = now.toEpochMilli();
        Long previous = lastTouchMillis.get(tokenId);
        if (previous != null && nowMillis - previous < LAST_USED_THROTTLE_MILLIS) return;
        if (lastTouchMillis.size() > MAX_TRACKED_TOUCHES) lastTouchMillis.clear();
        lastTouchMillis.put(tokenId, nowMillis);
        writes.submit(() -> {
            try {
                repository.touchLastUsed(tokenId, now, remoteAddress);
            } catch (RuntimeException ignored) {
                // 令牌已成功认证，不因元数据写入瞬时故障阻断请求
            }
        });
    }

    private void requireAuthEnabled() {
        if (!authenticationService.enabled()) {
            throw new ApiProblemException(HttpStatus.CONFLICT, "AUTH_DISABLED", "服务端未启用用户认证，无法管理访问令牌。");
        }
    }

    private String normalizeName(String raw) {
        String name = raw == null ? "" : raw.trim();
        if (name.isEmpty()) throw new IllegalArgumentException("令牌名称不能为空。");
        if (name.length() > 120) throw new IllegalArgumentException("令牌名称不能超过 120 个字符。");
        return name;
    }

    private String normalizeScope(String raw) {
        if (raw == null || raw.isBlank()) return AccessToken.SCOPE_LISTED;
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        if (!AccessToken.SCOPE_ALL.equals(normalized) && !AccessToken.SCOPE_LISTED.equals(normalized)) {
            throw new IllegalArgumentException("连接范围仅支持 LISTED 或 ALL。");
        }
        return normalized;
    }

    private Set<Long> validateConnections(String scope, Collection<Long> rawIds, UserAccount account) {
        if (AccessToken.SCOPE_ALL.equals(scope)) return Set.of();
        Set<Long> requested = new LinkedHashSet<>();
        if (rawIds != null) {
            for (Long id : rawIds) {
                if (id != null) requested.add(id);
            }
        }
        if (requested.isEmpty()) {
            throw new IllegalArgumentException("仅指定连接模式下请至少选择一条连接。");
        }
        Set<Long> existing = connectionAccess.existingConnectionIds(requested);
        for (Long id : requested) {
            if (!existing.contains(id)) {
                throw new IllegalArgumentException("连接不存在：" + id);
            }
        }
        if (!"ADMIN".equals(account.role())) {
            Set<Long> visible = connectionAccess.allowedConnectionIds(account.id());
            for (Long id : requested) {
                if (!visible.contains(id)) {
                    throw new ApiProblemException(HttpStatus.FORBIDDEN, "CONNECTION_ACCESS_DENIED",
                            "当前账号无权将连接 " + id + " 加入令牌白名单。");
                }
            }
        }
        return requested;
    }

    private AccessTokenResponse toResponse(AccessToken token, UserAccount account) {
        return new AccessTokenResponse(
                token.id(),
                token.userId(),
                account == null ? null : account.username(),
                account == null ? null : account.displayName(),
                token.name(),
                token.connectionScope(),
                token.connectionIds().stream().sorted().toList(),
                token.expiresAt(),
                token.lastUsedAt(),
                token.lastUsedIp(),
                token.revokedAt(),
                token.createdAt(),
                token.isActive(clock.instant())
        );
    }

    private String generateId() {
        byte[] bytes = new byte[10];
        random.nextBytes(bytes);
        // 令牌格式为 mdd_pat_<id>_<secret>，id 绝不能包含下划线，使用 20 位十六进制串既满足 VARCHAR(24) 又避免分隔符冲突。
        return HexFormat.of().formatHex(bytes);
    }

    private String generateSecret() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception impossible) {
            throw new IllegalStateException("JVM 不支持 SHA-256", impossible);
        }
    }
}
