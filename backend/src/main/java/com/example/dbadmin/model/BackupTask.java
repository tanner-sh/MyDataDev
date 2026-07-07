package com.example.dbadmin.model;

import java.time.Instant;

public record BackupTask(
        long id,
        String name,
        long connectionId,
        String scope,
        String schemaName,
        String tableName,
        String backupMethod,
        String toolPath,
        String extraArgs,
        String nativeConnectName,
        String cron,
        boolean enabled,
        String lastStatus,
        String lastMessage,
        String lastFilePath,
        Long lastFileSize,
        Instant lastRunAt
) {
    public BackupTask(
            long id,
            String name,
            long connectionId,
            String scope,
            String schemaName,
            String tableName,
            String cron,
            boolean enabled,
            String lastStatus,
            String lastMessage,
            String lastFilePath,
            Long lastFileSize,
            Instant lastRunAt
    ) {
        this(id, name, connectionId, scope, schemaName, tableName, "SQL", null, null, null, cron, enabled, lastStatus, lastMessage, lastFilePath, lastFileSize, lastRunAt);
    }
}
