package com.example.dbadmin.service;

import com.example.dbadmin.model.BackupTask;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class BackupScheduler {
    private final BackupService backupService;
    private final Map<Long, Instant> lastTriggered = new ConcurrentHashMap<>();

    public BackupScheduler(BackupService backupService) {
        this.backupService = backupService;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void runDueBackups() {
        Instant now = Instant.now();
        for (BackupTask task : backupService.list()) {
            if (!task.enabled() || task.cron() == null || task.cron().isBlank()) {
                continue;
            }
            try {
                if (isDue(task, now)) {
                    lastTriggered.put(task.id(), now);
                    backupService.run(task.id(), "scheduler");
                }
            } catch (Exception ignored) {
                // BackupService records execution failures on the task; invalid cron expressions are skipped.
            }
        }
    }

    boolean isDue(BackupTask task, Instant now) {
        CronExpression cron = CronExpression.parse(task.cron());
        Instant last = lastTriggered.getOrDefault(task.id(), task.lastRunAt());
        Instant baseline = last == null ? now.minusSeconds(60) : last;
        ZonedDateTime next = cron.next(ZonedDateTime.ofInstant(baseline, ZoneId.systemDefault()));
        return next != null && !next.toInstant().isAfter(now);
    }
}
