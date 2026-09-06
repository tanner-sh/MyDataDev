package com.example.dbadmin.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

/** 一次导出的结果；内部路径不下发，下载必须经过连接权限检查。 */
public record ScheduledQueryRun(String id, long taskId, long connectionId, String taskName,
        String status, String message, Instant startedAt, Instant finishedAt,
        String fileName, @JsonIgnore String filePath, long fileSize) {
    @JsonProperty("downloadable")
    public boolean downloadable() { return filePath != null && "SUCCESS".equals(status); }
}
