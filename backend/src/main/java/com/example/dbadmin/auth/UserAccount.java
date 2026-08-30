package com.example.dbadmin.auth;

import java.time.Instant;

public record UserAccount(
        long id,
        String provider,
        String subject,
        String username,
        String displayName,
        String passwordHash,
        String role,
        boolean enabled,
        long authVersion,
        Instant lastLoginAt,
        Instant createdAt,
        Instant updatedAt
) {
    public WebIdentity identity() {
        return new WebIdentity(id, provider, subject, username, displayName, role, authVersion);
    }
}
