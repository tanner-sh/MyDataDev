package com.example.dbadmin.model;

import java.time.Instant;

public record RestoreJob(
        long id,
        String sourceKind,
        long sourceId,
        String sourceName,
        String sourceFilePath,
        String sourceChecksum,
        String fileFormat,
        String sourceDbType,
        long targetConnectionId,
        String targetDbType,
        String conflictMode,
        String namespaceMapping,
        String status,
        String phase,
        Long progressCurrent,
        Long progressTotal,
        String message,
        boolean cancelRequested,
        String actor,
        Instant startedAt,
        Instant finishedAt,
        Instant createdAt
) {
}
