package com.example.dbadmin.model;

import java.time.Instant;

public record SqlFileExecution(
        long id,
        long connectionId,
        String connectionName,
        String targetDbType,
        String fileName,
        String filePath,
        long fileSize,
        String checksumSha256,
        String detectedCharset,
        String status,
        String phase,
        long processedBytes,
        Long statementTotal,
        long statementCurrent,
        long queryCount,
        long mutationCount,
        long ddlCount,
        long unknownCount,
        long successCount,
        long queryRowCount,
        Long failedStatementIndex,
        String failedSqlPreview,
        String message,
        boolean metadataChanged,
        boolean sessionChanged,
        boolean cancelRequested,
        String actor,
        Instant expiresAt,
        Instant startedAt,
        Instant finishedAt,
        Instant createdAt
) {
}
