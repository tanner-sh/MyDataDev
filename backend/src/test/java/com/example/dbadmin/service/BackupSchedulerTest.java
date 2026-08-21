package com.example.dbadmin.service;

import com.example.dbadmin.api.ApiProblemException;
import com.example.dbadmin.model.BackupTask;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BackupSchedulerTest {
    @Test
    void detectsDueCronTasks() {
        BackupService service = mock(BackupService.class);
        BackupScheduler scheduler = new BackupScheduler(service);
        Instant now = Instant.parse("2026-07-03T00:10:00Z");
        BackupTask task = task("0 0/5 * * * *", Instant.parse("2026-07-03T00:04:00Z"));

        assertThat(scheduler.isDue(task, now)).isTrue();
    }

    @Test
    void skipsCronTasksThatAreNotDue() {
        BackupService service = mock(BackupService.class);
        BackupScheduler scheduler = new BackupScheduler(service);
        Instant now = Instant.parse("2026-07-03T00:03:00Z");
        BackupTask task = task("0 0/5 * * * *", Instant.parse("2026-07-03T00:00:00Z"));

        assertThat(scheduler.isDue(task, now)).isFalse();
    }

    @Test
    void runDueBackupsContinuesWhenOneTaskFails() throws Exception {
        BackupService service = mock(BackupService.class);
        BackupTask due = task("0/1 * * * * *", null);
        BackupTask invalid = task("invalid cron", null);
        when(service.list()).thenReturn(List.of(invalid, due));
        BackupScheduler scheduler = new BackupScheduler(service);

        scheduler.runDueBackups();

        org.mockito.Mockito.verify(service).enqueue(due.id(), "scheduler");
    }

    @Test
    void retriesTransientEnqueueFailureOnTheNextSweep() {
        BackupService service = mock(BackupService.class);
        BackupTask due = task("0/1 * * * * *", null);
        when(service.list()).thenReturn(List.of(due));
        when(service.enqueue(due.id(), "scheduler")).thenThrow(new ApiProblemException(
                HttpStatus.CONFLICT, "CONNECTION_BACKGROUND_BUSY", "该连接已有后台重任务正在执行，请等待完成后重试。"));
        BackupScheduler scheduler = new BackupScheduler(service);

        scheduler.runDueBackups();

        // The marker was rolled back, so the very same cron window is still due.
        assertThat(scheduler.isDue(due, Instant.now())).isTrue();
    }

    @Test
    void doesNotRetryPermanentEnqueueFailureWithinTheSameWindow() {
        BackupService service = mock(BackupService.class);
        BackupTask due = task("0 0 2 * * *", null);
        when(service.list()).thenReturn(List.of(due));
        when(service.enqueue(due.id(), "scheduler")).thenThrow(new IllegalArgumentException("Backup task not found: 1"));
        BackupScheduler scheduler = new BackupScheduler(service);

        scheduler.runDueBackups();

        assertThat(scheduler.isDue(due, Instant.now())).isFalse();
    }

    @Test
    void forgetsTriggerMarkersOfDeletedTasks() {
        BackupService service = mock(BackupService.class);
        BackupTask due = task("0/1 * * * * *", null);
        when(service.list()).thenReturn(List.of(due), List.of());
        BackupScheduler scheduler = new BackupScheduler(service);

        scheduler.runDueBackups();
        scheduler.runDueBackups();

        assertThat(scheduler.trackedTaskCount()).isZero();
    }

    @Test
    void firesDailyCronAtTheTaskTimeZoneNotTheServerZone() {
        BackupService service = mock(BackupService.class);
        BackupScheduler scheduler = new BackupScheduler(service);
        // 每天 02:00（Asia/Shanghai）= 前一天 18:00 UTC。
        BackupTask task = zonedTask("0 0 2 * * *", Instant.parse("2026-07-02T18:00:30Z"), "Asia/Shanghai");

        assertThat(scheduler.isDue(task, Instant.parse("2026-07-03T18:00:30Z"))).isTrue();
        assertThat(scheduler.isDue(task, Instant.parse("2026-07-03T02:00:30Z"))).isFalse();
    }

    @Test
    void fallsBackToTheServerZoneForTasksWithoutOne() {
        BackupService service = mock(BackupService.class);
        BackupScheduler scheduler = new BackupScheduler(service);
        BackupTask task = task("0 0 2 * * *", Instant.parse("2026-07-02T02:00:30Z"));
        Instant due = java.time.ZonedDateTime.of(2026, 7, 3, 2, 0, 30, 0, java.time.ZoneId.systemDefault()).toInstant();

        assertThat(scheduler.isDue(task, due)).isTrue();
    }

    private BackupTask zonedTask(String cron, Instant lastRunAt, String zone) {
        BackupTask base = task(cron, lastRunAt);
        return new BackupTask(base.id(), base.name(), base.connectionId(), base.scope(), base.schemaName(),
                base.tableName(), base.tableNames(), base.backupMethod(), base.toolPath(), base.extraArgs(),
                base.nativeConnectName(), base.cron(), base.enabled(), base.lastStatus(), base.lastMessage(),
                base.lastFilePath(), base.lastFileSize(), base.lastRunAt(), null, null, null, null, null,
                null, null, null, zone);
    }

    private BackupTask task(String cron, Instant lastRunAt) {
        return new BackupTask(1, "backup", 1, "DATABASE", null, null, cron, true, null, null, null, null, lastRunAt);
    }
}
