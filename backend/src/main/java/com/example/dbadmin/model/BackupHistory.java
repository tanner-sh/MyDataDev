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
        Instant finishedAt
) {
}
