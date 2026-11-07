package com.example.dbadmin.service;

import com.example.dbadmin.model.SchemaSnapshotTarget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 结构快照的扫描器，与 {@link BackupScheduler}、{@link ScheduledQueryScheduler} 共用 {@link CronSweep}。
 * 重启后同一窗口内可能少采一次：快照少一份不影响追溯（上一份还在，且时间线本来就只在结构变化时增行），
 * 而某个早上突然冒出二十份是实打实的噪音。
 */
@Component
public class SchemaSnapshotScheduler {
    private static final Logger log = LoggerFactory.getLogger(SchemaSnapshotScheduler.class);

    private final SchemaSnapshotService service;
    private final CronSweep sweep = new CronSweep(log, "结构快照目标");

    public SchemaSnapshotScheduler(SchemaSnapshotService service) {
        this.service = service;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 120_000)
    public void runDueSnapshots() {
        sweep.run(service.targets(), Instant.now(), (target, now) -> {
            sweep.mark(target.id(), now);
            // runTarget 自己吞异常并把结果记在目标上，所以这里不必再包一层。
            service.runTarget(target);
        });
    }

    boolean isDue(SchemaSnapshotTarget target, Instant now) {
        return sweep.isDue(target, now);
    }
}
