package com.example.dbadmin.auth;

import java.time.Instant;
import java.util.Set;

/** 持久化存储的个人访问令牌（PAT）及其连接白名单。 */
public record AccessToken(
        String id,
        long userId,
        String name,
        String secretHash,
        long issuedAuthVersion,
        String issuedProvider,
        String connectionScope,
        Set<Long> connectionIds,
        Instant expiresAt,
        Instant lastUsedAt,
        String lastUsedIp,
        Instant revokedAt,
        Instant createdAt
) {
    public static final String SCOPE_ALL = "ALL";
    public static final String SCOPE_LISTED = "LISTED";

    public boolean isListedScope() {
        return SCOPE_LISTED.equals(connectionScope);
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public boolean isExpired(Instant now) {
        return expiresAt == null || !expiresAt.isAfter(now);
    }

    public boolean isActive(Instant now) {
        return !isRevoked() && !isExpired(now);
    }
}
