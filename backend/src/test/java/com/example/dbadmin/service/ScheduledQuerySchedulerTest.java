package com.example.dbadmin.service;

import com.example.dbadmin.model.ScheduledQuery;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 到点判定与 {@link BackupSchedulerTest} 同一套语义：cron 窗口过去了就跑，重启后不补跑一堆。 */
class ScheduledQuerySchedulerTest {
    @Test
    void detectsDueTasksInTheirOwnTimezone() {
        ScheduledQuerySchedulerFixture fixture = new ScheduledQuerySchedulerFixture();
        // 上海时间 08:00 触发，对应 UTC 00:00。
        ScheduledQuery task = task("0 0 8 * * *", "Asia/Shanghai", Instant.parse("2026-07-02T23:00:00Z"));

        assertThat(fixture.scheduler.isDue(task, Instant.parse("2026-07-03T00:00:30Z"))).isTrue();
        assertThat(fixture.scheduler.isDue(task, Instant.parse("2026-07-02T23:30:00Z"))).isFalse();
    }

    /** 一条 cron 写坏的任务不该让整轮扫描停下来。 */
    @Test
    void keepsSweepingWhenOneCronIsBroken() {
        ScheduledQuerySchedulerFixture fixture = new ScheduledQuerySchedulerFixture();
        ScheduledQuery broken = task(1, "invalid cron", null, null);
        ScheduledQuery due = task(2, "0/1 * * * * *", null, null);
        when(fixture.service.list(null)).thenReturn(List.of(broken, due));

        fixture.scheduler.runDueExports();

        verify(fixture.service).run(2, "scheduler");
        verify(fixture.service, never()).run(1, "scheduler");
    }

    /** 停用的任务连判定都不该进：它现在不跑了。 */
    @Test
    void skipsDisabledTasks() {
        ScheduledQuerySchedulerFixture fixture = new ScheduledQuerySchedulerFixture();
        when(fixture.service.list(null)).thenReturn(List.of(new ScheduledQuery(3, 1, "停用", "select 1", "csv",
                "0/1 * * * * *", null, false, false, null, null, null, null, null, null)));

        fixture.scheduler.runDueExports();

        verify(fixture.service, never()).run(3, "scheduler");
    }

    private static ScheduledQuery task(String cron, String zone, Instant lastRun) {
        return task(1, cron, zone, lastRun);
    }

    private static ScheduledQuery task(long id, String cron, String zone, Instant lastRun) {
        return new ScheduledQuery(id, 1, "任务" + id, "select 1", "csv", cron, zone, true, false,
                lastRun, null, null, null, null, null);
    }

    private static final class ScheduledQuerySchedulerFixture {
        private final ScheduledQueryService service = mock(ScheduledQueryService.class);
        private final ScheduledQueryScheduler scheduler = new ScheduledQueryScheduler(service);
    }
}
