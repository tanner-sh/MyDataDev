package com.example.dbadmin.auth;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

/** 从服务端 SecurityContext 读取稳定用户身份，不接受客户端提供的用户 ID。 */
public final class WebIdentityContext {
    private WebIdentityContext() {
    }

    public static Optional<WebIdentity> current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getPrincipal() instanceof WebIdentity identity
                ? Optional.of(identity) : Optional.empty();
    }
}
