package com.example.dbadmin.auth;

import java.util.Optional;

/**
 * Web 身份提供器扩展点。LOCAL 使用账号密码；未来 OIDC/SSO 实现可返回同一个 WebIdentity，
 * 后续的 Session、角色校验和审计无需感知认证来源。
 */
public interface WebIdentityProvider {
    String id();

    Optional<WebIdentity> authenticate(String username, String credential);

    /** 每个请求刷新启停状态和角色；外部身份提供器可按自己的缓存/令牌策略实现。 */
    default Optional<WebIdentity> refresh(WebIdentity identity) {
        return Optional.of(identity);
    }

    /** 当前提供器是否支持登录页的用户名/密码表单。 */
    default boolean passwordLogin() {
        return true;
    }

    /** 外部提供器可返回 OIDC 授权入口；本地账号不需要。 */
    default String loginUrl() {
        return null;
    }
}
