package com.example.dbadmin.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/**
 * 一个定期采集结构快照的目标。
 *
 * @param keepSnapshots 每个目标保留多少份快照。快照存的是整份结构，不封顶会让元数据库一直长
 * @param scheduleZone  计算 cron 用的时区；空表示服务器默认时区
 */
public record SchemaSnapshotTarget(
        long id,
        long connectionId,
        String schemaName,
        String cron,
        String scheduleZone,
        boolean enabled,
        int keepSnapshots,
        Instant lastRunAt,
        String lastStatus,
        String lastMessage,
        Instant createdAt,
        Instant updatedAt
) implements CronScheduled {
    @JsonProperty("zoneId")
    public String zoneId() {
        return scheduleZoneId().getId();
    }

}
