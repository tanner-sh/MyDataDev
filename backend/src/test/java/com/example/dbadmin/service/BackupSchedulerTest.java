package com.example.dbadmin.service;

import com.example.dbadmin.model.BackupTask;
import org.junit.jupiter.api.Test;

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

    private BackupTask task(String cron, Instant lastRunAt) {
        return new BackupTask(1, "backup", 1, "DATABASE", null, null, cron, true, null, null, null, null, lastRunAt);
    }
}
