package com.example.dbadmin.service;

import com.example.dbadmin.model.SchemaSnapshotTarget;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 到点判定与 {@link ScheduledQuerySchedulerTest}、{@link BackupSchedulerTest} 同一套语义。 */
class SchemaSnapshotSchedulerTest {
    private final SchemaSnapshotService service = mock(SchemaSnapshotService.class);
    private final SchemaSnapshotScheduler scheduler = new SchemaSnapshotScheduler(service);

    private static SchemaSnapshotTarget target(long id, String cron, String zone, Instant lastRun, boolean enabled) {
        return new SchemaSnapshotTarget(id, 1, "PUBLIC", cron, zone, enabled, 30, lastRun, null, null, null, null);
    }

    @Test
    void detectsDueTargetsInTheirOwnTimezone() {
        // 上海时间 08:00 触发，对应 UTC 00:00。
        SchemaSnapshotTarget task = target(1, "0 0 8 * * *", "Asia/Shanghai", Instant.parse("2026-07-02T23:00:00Z"), true);

        assertThat(scheduler.isDue(task, Instant.parse("2026-07-03T00:00:30Z"))).isTrue();
        assertThat(scheduler.isDue(task, Instant.parse("2026-07-02T23:30:00Z"))).isFalse();
    }

    /** 一条 cron 写坏的目标不该让整轮扫描停下来。 */
    @Test
    void keepsSweepingWhenOneCronIsBroken() {
        SchemaSnapshotTarget broken = target(1, "invalid cron", null, null, true);
        SchemaSnapshotTarget due = target(2, "0/1 * * * * *", null, null, true);
        when(service.targets()).thenReturn(List.of(broken, due));

        scheduler.runDueSnapshots();

        verify(service).runTarget(due);
        verify(service, never()).runTarget(broken);
    }

    /** 停用的目标连判定都不该进：它现在不采了。 */
    @Test
    void skipsDisabledTargets() {
        when(service.targets()).thenReturn(List.of(target(3, "0/1 * * * * *", null, null, false)));

        scheduler.runDueSnapshots();

        verify(service, never()).runTarget(any());
    }
}
