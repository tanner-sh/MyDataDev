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
        Instant createdAt,
        SqlFileTransaction transaction,
        /** 换行分隔的编译错误（Oracle 系 INVALID 对象），没有为 null。 */
        String compilationErrors
) {
    public SqlFileExecution(
        long id, long connectionId, String connectionName, String targetDbType, String fileName, String filePath,
        long fileSize, String checksumSha256, String detectedCharset, String status, String phase, long processedBytes,
        Long statementTotal, long statementCurrent, long queryCount, long mutationCount, long ddlCount, long unknownCount,
        long successCount, long queryRowCount, Long failedStatementIndex, String failedSqlPreview, String message,
        boolean metadataChanged, boolean sessionChanged, boolean cancelRequested, String actor, Instant expiresAt,
        Instant startedAt, Instant finishedAt, Instant createdAt, SqlFileTransaction transaction
    ) { this(id, connectionId, connectionName, targetDbType, fileName, filePath, fileSize, checksumSha256, detectedCharset, status, phase, processedBytes, statementTotal, statementCurrent, queryCount, mutationCount, ddlCount, unknownCount, successCount, queryRowCount, failedStatementIndex, failedSqlPreview, message, metadataChanged, sessionChanged, cancelRequested, actor, expiresAt, startedAt, finishedAt, createdAt, transaction, null); }

    public SqlFileExecution(
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
    ) { this(id, connectionId, connectionName, targetDbType, fileName, filePath, fileSize, checksumSha256, detectedCharset, status, phase, processedBytes, statementTotal, statementCurrent, queryCount, mutationCount, ddlCount, unknownCount, successCount, queryRowCount, failedStatementIndex, failedSqlPreview, message, metadataChanged, sessionChanged, cancelRequested, actor, expiresAt, startedAt, finishedAt, createdAt, SqlFileTransaction.defaults()); }
}
