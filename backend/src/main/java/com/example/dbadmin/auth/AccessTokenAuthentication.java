package com.example.dbadmin.auth;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;
import java.util.Set;

/**
 * 个人访问令牌（PAT）认证结果。
 *
 * <p>主体仍然是标准 {@link WebIdentity}，以便所有业务控制器共用同一身份模型；
 * 同时携带不可伪造的 {@code tokenId} 与连接白名单范围，供连接授权与审计归属读取。</p>
 */
public final class AccessTokenAuthentication extends AbstractAuthenticationToken {
    private final WebIdentity principal;
    private final String tokenId;
    private final String tokenName;
    private final String connectionScope;
    private final Set<Long> allowedConnectionIds;

    public AccessTokenAuthentication(
            WebIdentity principal,
            String tokenId,
            String tokenName,
            String connectionScope,
            Set<Long> allowedConnectionIds
    ) {
        super(List.of(new SimpleGrantedAuthority("ROLE_" + principal.role())));
        this.principal = principal;
        this.tokenId = tokenId;
        this.tokenName = tokenName;
        this.connectionScope = connectionScope;
        this.allowedConnectionIds = allowedConnectionIds == null ? Set.of() : Set.copyOf(allowedConnectionIds);
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public WebIdentity getPrincipal() {
        return principal;
    }

    @Override
    public String getName() {
        return principal.username();
    }

    public String tokenId() {
        return tokenId;
    }

    public String tokenName() {
        return tokenName;
    }

    public String connectionScope() {
        return connectionScope;
    }

    public boolean isListedScope() {
        return AccessToken.SCOPE_LISTED.equals(connectionScope);
    }

    public Set<Long> allowedConnectionIds() {
        return allowedConnectionIds;
    }
}
