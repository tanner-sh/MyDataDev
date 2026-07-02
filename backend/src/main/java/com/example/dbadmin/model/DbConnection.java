package com.example.dbadmin.model;

import java.time.Instant;

public record DbConnection(
        long id,
        String name,
        String dbType,
        String jdbcUrl,
        String username,
        String encryptedPassword,
        String environment,
        boolean readonly,
        Instant createdAt,
        Instant updatedAt
) {
}
