package com.example.dbadmin.service;

import com.example.dbadmin.api.ApiProblemException;
import com.example.dbadmin.model.BackupTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class BackupScheduler {
    private static final Logger log = LoggerFactory.getLogger(BackupScheduler.class);
    /**
     * Enqueue failures that clear on their own: another heavy task currently
     * holds the connection permit, or the worker queue is momentarily full.
     * The trigger marker is rolled back for these so the next tick retries
     * inside the same cron window instead of skipping the run entirely.
     */
    private static final Set<String> RETRYABLE_CODES = Set.of("CONNECTION_BACKGROUND_BUSY", "BACKUP_QUEUE_FULL");
    private final BackupService backupService;
    private final Map<Long, Instant> lastTriggered = new ConcurrentHashMap<>();

    public BackupScheduler(BackupService backupService) {
        this.backupService = backupService;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void runDueBackups() {
        Instant now = Instant.now();
        List<BackupTask> tasks = backupService.list();
        forgetDeletedTasks(tasks);
        for (BackupTask task : tasks) {
            if (!task.enabled() || task.cron() == null || task.cron().isBlank()) {
                continue;
            }
            try {
                if (!isDue(task, now)) continue;
            } catch (Exception error) {
                // An unparseable cron expression is reported when the task is
                // saved; skip it here rather than stopping the whole sweep.
                log.warn("跳过定时备份任务 {}：cron 表达式无法解析（{}）", task.id(), task.cron());
                continue;
            }
            trigger(task, now);
        }
    }

    /**
     * Marks the window as handled before enqueueing, so a permanently failing
     * task cannot be retried every minute forever, then rolls the marker back
     * when the failure is one that will clear by itself.
     */
    private void trigger(BackupTask task, Instant now) {
        Instant previous = lastTriggered.put(task.id(), now);
        try {
            backupService.enqueue(task.id(), "scheduler");
        } catch (Exception error) {
            if (isRetryable(error)) {
                restoreTrigger(task.id(), previous);
                log.info("定时备份任务 {} 本次未能入队，将在下一次检查时重试：{}", task.id(), error.getMessage());
                return;
            }
            // BackupService records queue-full and execution failures on the task
            // itself; anything reaching here still deserves a server-side trace,
            // because the schedule has just skipped a run.
            log.warn("定时备份任务 {} 触发失败，本次执行窗口已跳过：{}", task.id(), error.getMessage(), error);
        }
    }

    private boolean isRetryable(Exception error) {
        return error instanceof ApiProblemException problem && RETRYABLE_CODES.contains(problem.code());
    }

    private void restoreTrigger(long taskId, Instant previous) {
        if (previous == null) lastTriggered.remove(taskId);
        else lastTriggered.put(taskId, previous);
    }

    private void forgetDeletedTasks(List<BackupTask> tasks) {
        if (lastTriggered.isEmpty()) return;
        Set<Long> known = new HashSet<>();
        for (BackupTask task : tasks) known.add(task.id());
        lastTriggered.keySet().retainAll(known);
    }

    int trackedTaskCount() {
        return lastTriggered.size();
    }

    boolean isDue(BackupTask task, Instant now) {
        CronExpression cron = CronExpression.parse(task.cron());
        Instant last = lastTriggered.getOrDefault(task.id(), task.lastRunAt());
        Instant baseline = last == null ? now.minusSeconds(60) : last;
        ZonedDateTime next = cron.next(ZonedDateTime.ofInstant(baseline, task.scheduleZoneId()));
        return next != null && !next.toInstant().isAfter(now);
    }
}
