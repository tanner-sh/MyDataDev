package com.example.dbadmin.service;

import com.example.dbadmin.api.ApiProblemException;
import com.example.dbadmin.model.BackupTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Set;

/**
 * 定时备份的扫描器，与 {@link ScheduledQueryScheduler}、{@link SchemaSnapshotScheduler} 共用 {@link CronSweep}；
 * 多出来的只有「入队失败但会自行恢复时，同一窗口里下一轮再试」。
 */
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
    private final CronSweep sweep = new CronSweep(log, "定时备份任务");

    public BackupScheduler(BackupService backupService) {
        this.backupService = backupService;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void runDueBackups() {
        sweep.run(backupService.list(), Instant.now(), this::trigger);
    }

    /**
     * Marks the window as handled before enqueueing, so a permanently failing
     * task cannot be retried every minute forever, then rolls the marker back
     * when the failure is one that will clear by itself.
     */
    private void trigger(BackupTask task, Instant now) {
        Instant previous = sweep.mark(task.id(), now);
        try {
            backupService.enqueue(task.id(), "scheduler");
        } catch (Exception error) {
            if (isRetryable(error)) {
                sweep.restore(task.id(), previous);
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

    int trackedTaskCount() {
        return sweep.trackedCount();
    }

    boolean isDue(BackupTask task, Instant now) {
        return sweep.isDue(task, now);
    }
}
