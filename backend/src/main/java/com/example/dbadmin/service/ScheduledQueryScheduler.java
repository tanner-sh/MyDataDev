package com.example.dbadmin.service;

import com.example.dbadmin.model.ScheduledQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 定时导出的扫描器，与 {@link BackupScheduler}、{@link SchemaSnapshotScheduler} 共用 {@link CronSweep}。
 * 对导出来说，重启后少一份文件比某个早上突然冒出二十份文件要好（见 CronSweep）。
 */
@Component
public class ScheduledQueryScheduler {
    private static final Logger log = LoggerFactory.getLogger(ScheduledQueryScheduler.class);

    private final ScheduledQueryService service;
    private final CronSweep sweep = new CronSweep(log, "定时导出任务");

    public ScheduledQueryScheduler(ScheduledQueryService service) {
        this.service = service;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 90_000)
    public void runDueExports() {
        sweep.run(service.list(null), Instant.now(), (task, now) -> {
            sweep.mark(task.id(), now);
            // submit 自己吞掉执行异常并把结果记在任务上；这里只会碰到「没能启动」。
            try { service.submit(task.id(), "scheduler"); }
            catch (RuntimeException busy) { log.info("定时导出未启动 task={} reason={}", task.id(), busy.getMessage()); }
        });
    }

    boolean isDue(ScheduledQuery task, Instant now) {
        return sweep.isDue(task, now);
    }
}
