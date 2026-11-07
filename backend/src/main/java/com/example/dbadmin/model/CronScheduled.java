package com.example.dbadmin.model;

import java.time.Instant;
import java.time.ZoneId;

/**
 * 按 cron 定期运行的东西：定时备份（{@link BackupTask}）、定时导出（{@link ScheduledQuery}）、
 * 结构快照（{@link SchemaSnapshotTarget}）。
 *
 * <p>三者「到点没有」「时区怎么算」是同一套规则，原来却各写一份：三个调度器逐行相同，时区解析在三个
 * record 里各写一遍，cron 校验在三个 service 里报的错还不一样。规则现在在 {@link CronSchedules}，
 * 扫描在 {@code service.CronSweep}。</p>
 */
public interface CronScheduled {
    long id();

    boolean enabled();

    String cron();

    /** 计算 cron 用的时区；空表示服务器默认时区。 */
    String scheduleZone();

    /** 上一次运行时间（库里记的），调度器以它兜底判断这一个 cron 窗口跑过没有。 */
    Instant lastRunAt();

    /** 时区无效时退回服务器默认值，而不是让整个调度停摆。 */
    default ZoneId scheduleZoneId() {
        return CronSchedules.zoneOrDefault(scheduleZone());
    }

    /** 启用了并且填了 cron 才参与调度。 */
    default boolean scheduled() {
        return enabled() && cron() != null && !cron().isBlank();
    }
}
