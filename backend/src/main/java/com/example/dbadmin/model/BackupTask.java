package com.example.dbadmin.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.scheduling.support.CronExpression;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Locale;

public record BackupTask(
        long id,
        String name,
        long connectionId,
        String scope,
        String schemaName,
        String tableName,
        List<String> tableNames,
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
    public BackupTask {
        String normalizedScope = scope == null ? "" : scope.toUpperCase(Locale.ROOT);
        if ("TABLE".equals(normalizedScope)) {
            normalizedScope = "TABLES";
        }
        scope = normalizedScope;
        tableNames = tableNames == null ? List.of() : List.copyOf(tableNames);
        if (tableNames.isEmpty() && tableName != null && !tableName.isBlank() && "TABLES".equals(scope)) {
            tableNames = List.of(tableName);
        }
        if (!tableNames.isEmpty()) {
            tableName = tableNames.get(0);
        }
    }

    public BackupTask(
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
        this(id, name, connectionId, scope, schemaName, tableName, null, backupMethod, toolPath, extraArgs, nativeConnectName, cron, enabled, lastStatus, lastMessage, lastFilePath, lastFileSize, lastRunAt);
    }

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
        this(id, name, connectionId, scope, schemaName, tableName, null, "SQL", null, null, null, cron, enabled, lastStatus, lastMessage, lastFilePath, lastFileSize, lastRunAt);
    }

    @JsonProperty("zoneId")
    public String zoneId() {
        return ZoneId.systemDefault().getId();
    }

    @JsonProperty("nextRunAt")
    public Instant nextRunAt() {
        if (!enabled || cron == null || cron.isBlank()) {
            return null;
        }
        try {
            ZoneId zone = ZoneId.systemDefault();
            ZonedDateTime next = CronExpression.parse(cron).next(ZonedDateTime.now(zone));
            return next == null ? null : next.toInstant();
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
