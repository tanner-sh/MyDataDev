package com.example.dbadmin.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/**
 * 一条定时导出：按 cron 跑一条查询，把结果写成文件。
 *
 * @param productionConfirmed 建这条任务时，用户是否为生产连接输入过连接名。定时任务没有交互
 *                            确认的机会，所以那次确认在创建时完成，运行时凭它放行
 * @param scheduleZone 计算 cron 用的时区；空表示服务器默认时区
 */
public record ScheduledQuery(
        long id,
        long connectionId,
        String name,
        String sql,
        String exportFormat,
        String cron,
        String scheduleZone,
        boolean enabled,
        boolean productionConfirmed,
        Instant lastRunAt,
        String lastStatus,
        String lastMessage,
        String lastFile,
        Instant createdAt,
        Instant updatedAt
) implements CronScheduled {
    @JsonProperty("zoneId")
    public String zoneId() {
        return scheduleZoneId().getId();
    }

}
