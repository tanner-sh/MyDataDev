package com.example.dbadmin.model;

import java.time.Instant;

public record BackupTask(
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
        Instant lastRunAt
) {
}
