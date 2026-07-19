package com.example.dbadmin.model;

import java.time.Instant;

public record RestoreUpload(
        long id,
        String originalName,
        String filePath,
        long fileSize,
        String checksumSha256,
        String fileFormat,
        String sourceDbType,
        Instant createdAt,
        Instant expiresAt
) {
}
