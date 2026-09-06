package com.example.dbadmin.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.time.ZoneId;

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
) {
    @JsonProperty("zoneId")
    public String zoneId() {
        return scheduleZoneId().getId();
    }

    /** 时区无效时退回服务器默认值，而不是让整个调度停摆 —— 与定时导出同一条规矩。 */
    public ZoneId scheduleZoneId() {
        if (scheduleZone == null || scheduleZone.isBlank()) return ZoneId.systemDefault();
        try {
            return ZoneId.of(scheduleZone.trim());
        } catch (Exception ignored) {
            return ZoneId.systemDefault();
        }
    }
}
