package com.example.dbadmin.auth;

import java.io.Serial;
import java.io.Serializable;
import java.security.Principal;

/** Session、审计和授权共用的稳定身份；具体身份来源由 WebIdentityProvider 决定。 */
public record WebIdentity(
        long userId,
        String provider,
        String subject,
        String username,
        String displayName,
        String role,
        long authVersion
) implements Principal, Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    @Override
    public String getName() {
        return username;
    }
}
