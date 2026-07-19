package com.example.dbadmin.model;

import java.time.Instant;

public record BackupHistory(
        long id,
        long taskId,
        long connectionId,
        String status,
        String message,
        String filePath,
        Long fileSize,
        Instant startedAt,
        Instant finishedAt,
        String fileFormat,
        String backupMethod,
        String sourceDbType,
        String checksumSha256,
        String phase,
        Long progressCurrent,
        Long progressTotal,
        boolean cancelRequested
) {
    public BackupHistory(
            long id,
            long taskId,
            long connectionId,
            String status,
            String message,
            String filePath,
            Long fileSize,
            Instant startedAt,
            Instant finishedAt
    ) {
        this(id, taskId, connectionId, status, message, filePath, fileSize, startedAt, finishedAt,
                null, null, null, null, null, null, null, false);
    }
}
