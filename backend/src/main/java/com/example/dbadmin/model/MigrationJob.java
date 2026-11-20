package com.example.dbadmin.model;

import java.time.Instant;

/** 异构数据库迁移任务总览（在线直迁 / 导出标准制品 / 导入标准制品）。 */
public record MigrationJob(
        long id,
        String mode,
        String sourceKind,
        Long sourceConnectionId,
        Long sourceArtifactId,
        String sourceDbType,
        String sourceSchema,
        Long targetConnectionId,
        String targetDbType,
        String targetSchema,
        Long outputArtifactId,
        String conflictMode,
        String optionsJson,
        String assessmentJson,
        String snapshotToken,
        String status,
        String phase,
        int totalItems,
        int completedItems,
        int failedItems,
        long totalRows,
        long migratedRows,
        long migratedBytes,
        String verifyStatus,
        String verifyReportJson,
        String message,
        boolean cancelRequested,
        String actor,
        Long ownerUserId,
        Instant startedAt,
        Instant finishedAt,
        Instant createdAt
) {
}
