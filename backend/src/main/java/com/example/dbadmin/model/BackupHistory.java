package com.example.dbadmin.model;

import com.fasterxml.jackson.annotation.JsonProperty;

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
        boolean cancelRequested,
        String storageType,
        Long storageProfileId,
        String storageProfileName,
        String storageObjectKey,
        Instant stagingExpiresAt
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
                null, null, null, null, null, null, null, false,
                "LOCAL", null, null, null, null);
    }

    public BackupHistory(
            long id, long taskId, long connectionId, String status, String message, String filePath, Long fileSize,
            Instant startedAt, Instant finishedAt, String fileFormat, String backupMethod, String sourceDbType,
            String checksumSha256, String phase, Long progressCurrent, Long progressTotal, boolean cancelRequested
    ) {
        this(id, taskId, connectionId, status, message, filePath, fileSize, startedAt, finishedAt, fileFormat,
                backupMethod, sourceDbType, checksumSha256, phase, progressCurrent, progressTotal, cancelRequested,
                "LOCAL", null, null, null, null);
    }

    @JsonProperty("fileAvailable")
    public boolean fileAvailable() {
        return "SUCCESS".equals(status) && (filePath != null && !filePath.isBlank()
                || storageObjectKey != null && !storageObjectKey.isBlank());
    }

    @JsonProperty("stagingAvailable")
    public boolean stagingAvailable() {
        return "UPLOAD_FAILED".equals(phase) && filePath != null && !filePath.isBlank();
    }

    public BackupHistory withStorageProfileName(String profileName) {
        return new BackupHistory(id, taskId, connectionId, status, message, filePath, fileSize, startedAt, finishedAt,
                fileFormat, backupMethod, sourceDbType, checksumSha256, phase, progressCurrent, progressTotal,
                cancelRequested, storageType, storageProfileId, profileName, storageObjectKey, stagingExpiresAt);
    }
}
