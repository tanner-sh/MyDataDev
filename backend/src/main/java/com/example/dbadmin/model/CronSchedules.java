package com.example.dbadmin.model;

import org.springframework.scheduling.support.CronExpression;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * cron 与时区的判定，定时备份、定时导出、结构快照共用（见 {@link CronScheduled}）。
 *
 * <p>两种口径分开：保存时严格（{@link #requireValidCron}、{@link #normalizeZone} 写错就报错 ——
 * 写错的 cron 留到某天凌晨才发现从没跑过，代价太大）；运行时宽松（{@link #zoneOrDefault}、
 * {@link #nextRun} 解析不了就退回默认值或返回 null，一条坏数据不该让整轮扫描停下来）。</p>
 */
public final class CronSchedules {
    private CronSchedules() {
    }

    /** 运行时用：空或无效的时区退回服务器默认时区。 */
    public static ZoneId zoneOrDefault(String scheduleZone) {
        if (scheduleZone == null || scheduleZone.isBlank()) return ZoneId.systemDefault();
        try {
            return ZoneId.of(scheduleZone.trim());
        } catch (DateTimeException ignored) {
            return ZoneId.systemDefault();
        }
    }

    /**
     * 保存时用：空返回 null（用服务器默认时区），否则返回规范化的时区 ID，认不出就报错。
     * 时区由前端按浏览器所在时区提交 —— 服务端跑在 UTC 的容器里时，沿用服务端时区会让 02:00 的计划
     * 在用户本地 10:00 才触发。
     */
    public static String normalizeZone(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return ZoneId.of(value.trim()).getId();
        } catch (DateTimeException error) {
            throw new IllegalArgumentException("无法识别的时区：" + value);
        }
    }

    /** 保存时用：cron 写错就报错。 */
    public static CronExpression requireValidCron(String cron) {
        try {
            return CronExpression.parse(cron == null ? "" : cron.trim());
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("cron 表达式不合法：" + cron);
        }
    }

    /** 给界面看的下一次运行时间；cron 解析不了时返回 null（保存时已经拦过一次了）。 */
    public static Instant nextRun(String cron, ZoneId zone, Instant from) {
        if (cron == null || cron.isBlank()) return null;
        try {
            ZonedDateTime next = CronExpression.parse(cron.trim()).next(ZonedDateTime.ofInstant(from, zone));
            return next == null ? null : next.toInstant();
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    /**
     * 这一刻该不该触发：从上次触发（没有就是一分钟前）往后数，下一个 cron 时刻已经到了。
     * 解析不了的 cron 抛 {@link IllegalArgumentException}，由调度器记一条日志后跳过。
     */
    public static boolean isDue(String cron, ZoneId zone, Instant lastTriggered, Instant now) {
        Instant baseline = lastTriggered == null ? now.minusSeconds(60) : lastTriggered;
        ZonedDateTime next = CronExpression.parse(cron.trim()).next(ZonedDateTime.ofInstant(baseline, zone));
        return next != null && !next.toInstant().isAfter(now);
    }
}
