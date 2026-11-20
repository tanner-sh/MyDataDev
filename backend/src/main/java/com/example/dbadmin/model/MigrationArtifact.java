package com.example.dbadmin.model;

import java.time.Instant;

/** MyDataDev 通用标准迁移制品（.mddpkg）元数据记录。 */
public record MigrationArtifact(
        long id,
        String name,
        String filePath,
        Long storageProfileId,
        String remotePath,
        long fileSize,
        String checksum,
        String sourceDbType,
        String sourceVersion,
        String sourceSchema,
        int formatVersion,
        int tableCount,
        long totalRows,
        long totalBytes,
        String manifestJson,
        Long ownerUserId,
        Instant createdAt,
        Instant expiresAt
) {
}
