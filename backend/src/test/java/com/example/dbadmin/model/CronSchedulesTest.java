package com.example.dbadmin.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CronSchedulesTest {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    @Test
    void savingRejectsBadCronAndZoneWithOneMessageForAllThreeSchedulers() {
        assertThatThrownBy(() -> CronSchedules.requireValidCron("invalid cron")).hasMessage("cron 表达式不合法：invalid cron");
        assertThatThrownBy(() -> CronSchedules.normalizeZone("Mars/Olympus")).hasMessage("无法识别的时区：Mars/Olympus");
        assertThat(CronSchedules.normalizeZone("  ")).isNull();
        assertThat(CronSchedules.normalizeZone(" Asia/Shanghai ")).isEqualTo("Asia/Shanghai");
    }

    @Test
    void runtimeFallsBackInsteadOfStoppingTheSweep() {
        assertThat(CronSchedules.zoneOrDefault("Mars/Olympus")).isEqualTo(ZoneId.systemDefault());
        assertThat(CronSchedules.zoneOrDefault(null)).isEqualTo(ZoneId.systemDefault());
        assertThat(CronSchedules.nextRun("not a cron", SHANGHAI, Instant.now())).isNull();
        assertThat(CronSchedules.nextRun("0 0 2 * * *", SHANGHAI, Instant.parse("2026-07-02T19:00:00Z")))
                .isEqualTo(Instant.parse("2026-07-03T18:00:00Z"));
    }

    @Test
    void dueWhenTheNextFireTimeAfterTheLastTriggerHasPassed() {
        // 上海 02:00 = UTC 18:00（前一天）。
        Instant lastRun = Instant.parse("2026-07-02T18:00:30Z");
        assertThat(CronSchedules.isDue("0 0 2 * * *", SHANGHAI, lastRun, Instant.parse("2026-07-03T18:00:30Z"))).isTrue();
        assertThat(CronSchedules.isDue("0 0 2 * * *", SHANGHAI, lastRun, Instant.parse("2026-07-03T02:00:30Z"))).isFalse();
        // 从没跑过：只看最近一分钟，不补跑历史窗口。
        assertThat(CronSchedules.isDue("0 0 2 * * *", SHANGHAI, null, Instant.parse("2026-07-03T18:00:30Z"))).isTrue();
        assertThat(CronSchedules.isDue("0 0 2 * * *", SHANGHAI, null, Instant.parse("2026-07-03T18:05:00Z"))).isFalse();
    }
}
