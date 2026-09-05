package com.example.dbadmin.service;

import com.example.dbadmin.model.ScheduledQuery;
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
 * 定时导出的扫描器，与 {@link BackupScheduler} 同一套判定：每分钟看一遍，谁到点了就跑谁。
 *
 * <p>「上次触发时间」记在内存里、以数据库里的 {@code last_run_at} 作为兜底 —— 与备份那边
 * 一致。这意味着重启后同一个 cron 窗口内可能少跑一次，而不是补跑一堆；对导出来说，少一份
 * 文件比某个早上突然冒出二十份文件要好。</p>
 */
@Component
public class ScheduledQueryScheduler {
    private static final Logger log = LoggerFactory.getLogger(ScheduledQueryScheduler.class);

    private final ScheduledQueryService service;
    private final Map<Long, Instant> lastTriggered = new ConcurrentHashMap<>();

    public ScheduledQueryScheduler(ScheduledQueryService service) {
        this.service = service;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 90_000)
    public void runDueExports() {
        Instant now = Instant.now();
        List<ScheduledQuery> tasks = service.list(null);
        forgetDeletedTasks(tasks);
        for (ScheduledQuery task : tasks) {
            if (!task.enabled() || task.cron() == null || task.cron().isBlank()) continue;
            try {
                if (!isDue(task, now)) continue;
            } catch (Exception ignored) {
                // cron 写错在保存时就报过了，这里跳过即可，不该让整轮扫描停下来。
                log.warn("跳过定时导出任务 {}：cron 表达式无法解析（{}）", task.id(), task.cron());
                continue;
            }
            lastTriggered.put(task.id(), now);
            // run 自己吞掉异常并把结果记在任务上，所以这里不必再包一层 try。
            service.run(task.id(), "scheduler");
        }
    }

    boolean isDue(ScheduledQuery task, Instant now) {
        CronExpression cron = CronExpression.parse(task.cron());
        Instant last = lastTriggered.getOrDefault(task.id(), task.lastRunAt());
        Instant baseline = last == null ? now.minusSeconds(60) : last;
        ZonedDateTime next = cron.next(ZonedDateTime.ofInstant(baseline, task.scheduleZoneId()));
        return next != null && !next.toInstant().isAfter(now);
    }

    private void forgetDeletedTasks(List<ScheduledQuery> tasks) {
        if (lastTriggered.isEmpty()) return;
        Set<Long> known = new HashSet<>();
        for (ScheduledQuery task : tasks) known.add(task.id());
        lastTriggered.keySet().retainAll(known);
    }
}
