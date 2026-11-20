package com.example.dbadmin.model;

import java.time.Instant;

/** 迁移任务中单张表或单个对象的执行与校验项。 */
public record MigrationItem(
        long id,
        long jobId,
        String itemKind,
        String sourceName,
        String targetName,
        String tableId,
        int executionOrder,
        String status,
        long sourceRows,
        long migratedRows,
        long migratedBytes,
        int totalParts,
        int completedParts,
        String checkpointJson,
        String ddlJson,
        String warningsJson,
        String verifyStatus,
        String verifyDetailJson,
        String errorMessage,
        Instant startedAt,
        Instant finishedAt
) {
}
