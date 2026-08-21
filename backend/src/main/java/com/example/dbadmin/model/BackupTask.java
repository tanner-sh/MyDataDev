package com.example.dbadmin.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.scheduling.support.CronExpression;

import java.time.DateTimeException;
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
        Instant lastRunAt,
        Integer retentionDays,
        Integer retentionCount,
        Long storageProfileId,
        String storageProfileName,
        String storageType,
        String lastStorageType,
        Long lastStorageProfileId,
        String lastStorageObjectKey,
        String scheduleZone
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
        this(id, name, connectionId, scope, schemaName, tableName, tableNames, backupMethod, toolPath, extraArgs,
                nativeConnectName, cron, enabled, lastStatus, lastMessage, lastFilePath, lastFileSize, lastRunAt,
                null, null, null, null, null, null, null, null, null);
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
        this(id, name, connectionId, scope, schemaName, tableName, null, backupMethod, toolPath, extraArgs, nativeConnectName,
                cron, enabled, lastStatus, lastMessage, lastFilePath, lastFileSize, lastRunAt,
                null, null, null, null, null, null, null, null, null);
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
        this(id, name, connectionId, scope, schemaName, tableName, null, "SQL", null, null, null, cron, enabled,
                lastStatus, lastMessage, lastFilePath, lastFileSize, lastRunAt,
                null, null, null, null, null, null, null, null, null);
    }

    public BackupTask withStorageProfile(String profileName, String profileType) {
        return new BackupTask(id, name, connectionId, scope, schemaName, tableName, tableNames, backupMethod, toolPath,
                extraArgs, nativeConnectName, cron, enabled, lastStatus, lastMessage, lastFilePath, lastFileSize,
                lastRunAt, retentionDays, retentionCount, storageProfileId, profileName, profileType,
                lastStorageType, lastStorageProfileId, lastStorageObjectKey, scheduleZone);
    }

    @JsonProperty("lastFileAvailable")
    public boolean lastFileAvailable() {
        return "SUCCESS".equals(lastStatus) && (lastFilePath != null && !lastFilePath.isBlank()
                || lastStorageObjectKey != null && !lastStorageObjectKey.isBlank());
    }

    /**
     * 执行计划所用的时区。任务上没有记录时区（旧数据）才回落到服务端默认时区，
     * 否则 02:00 这样的计划会被按服务器时区解释，用户看到的却是本地时间。
     */
    public ZoneId scheduleZoneId() {
        if (scheduleZone == null || scheduleZone.isBlank()) {
            return ZoneId.systemDefault();
        }
        try {
            return ZoneId.of(scheduleZone.trim());
        } catch (DateTimeException ignored) {
            return ZoneId.systemDefault();
        }
    }

    @JsonProperty("zoneId")
    public String zoneId() {
        return scheduleZoneId().getId();
    }

    @JsonProperty("nextRunAt")
    public Instant nextRunAt() {
        if (!enabled || cron == null || cron.isBlank()) {
            return null;
        }
        try {
            ZoneId zone = scheduleZoneId();
            ZonedDateTime next = CronExpression.parse(cron).next(ZonedDateTime.now(zone));
            return next == null ? null : next.toInstant();
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
