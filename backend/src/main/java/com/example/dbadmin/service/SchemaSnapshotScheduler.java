package com.example.dbadmin.service;

import com.example.dbadmin.model.SchemaSnapshotTarget;
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

/**
 * 结构快照的扫描器，与 {@link BackupScheduler}、{@link ScheduledQueryScheduler} 同一套判定：
 * 每分钟看一遍，谁到点了就采谁。
 *
 * <p>「上次触发时间」记在内存里、以数据库里的 {@code last_run_at} 兜底 —— 与另外两个调度器
 * 一致。重启后同一个 cron 窗口内可能少采一次，而不是补采一堆：结构快照少一份不影响追溯
 * （上一份还在，且时间线本来就只在结构变化时增行），而某个早上突然冒出二十份是实打实的噪音。</p>
 */
@Component
public class SchemaSnapshotScheduler {
    private static final Logger log = LoggerFactory.getLogger(SchemaSnapshotScheduler.class);

    private final SchemaSnapshotService service;
    private final Map<Long, Instant> lastTriggered = new ConcurrentHashMap<>();

    public SchemaSnapshotScheduler(SchemaSnapshotService service) {
        this.service = service;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 120_000)
    public void runDueSnapshots() {
        Instant now = Instant.now();
        List<SchemaSnapshotTarget> targets = service.targets();
        forgetDeletedTargets(targets);
        for (SchemaSnapshotTarget target : targets) {
            if (!target.enabled() || target.cron() == null || target.cron().isBlank()) continue;
            try {
                if (!isDue(target, now)) continue;
            } catch (Exception ignored) {
                // cron 写错在保存时就报过了，这里跳过即可，不该让整轮扫描停下来。
                log.warn("跳过结构快照目标 {}：cron 表达式无法解析（{}）", target.id(), target.cron());
                continue;
            }
            lastTriggered.put(target.id(), now);
            // runTarget 自己吞异常并把结果记在目标上，所以这里不必再包一层。
            service.runTarget(target);
        }
    }

    boolean isDue(SchemaSnapshotTarget target, Instant now) {
        CronExpression cron = CronExpression.parse(target.cron());
        Instant last = lastTriggered.getOrDefault(target.id(), target.lastRunAt());
        Instant baseline = last == null ? now.minusSeconds(60) : last;
        ZonedDateTime next = cron.next(ZonedDateTime.ofInstant(baseline, target.scheduleZoneId()));
        return next != null && !next.toInstant().isAfter(now);
    }

    private void forgetDeletedTargets(List<SchemaSnapshotTarget> targets) {
        if (lastTriggered.isEmpty()) return;
        Set<Long> known = new HashSet<>();
        for (SchemaSnapshotTarget target : targets) known.add(target.id());
        lastTriggered.keySet().retainAll(known);
    }
}
