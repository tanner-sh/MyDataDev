package com.example.dbadmin.service;

import com.example.dbadmin.model.CronScheduled;
import com.example.dbadmin.model.CronSchedules;
import org.slf4j.Logger;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * 三个 cron 调度器（备份、定时导出、结构快照）共用的那一轮扫描：每分钟看一遍，谁到点了就交给 trigger。
 *
 * <p>「上次触发时间」记在内存里、以库里的 {@code last_run_at} 兜底。这意味着重启后同一个 cron 窗口内
 * 可能少跑一次，而不是补跑一堆 —— 少一份备份 / 导出 / 快照，比某个早上突然冒出二十份要好。</p>
 */
final class CronSweep {
    private final Logger log;
    private final String label;
    private final Map<Long, Instant> lastTriggered = new ConcurrentHashMap<>();

    /** @param label 日志里怎么称呼一条任务，例如「定时备份任务」。 */
    CronSweep(Logger log, String label) {
        this.log = log;
        this.label = label;
    }

    <T extends CronScheduled> void run(List<T> items, Instant now, BiConsumer<T, Instant> trigger) {
        forgetDeleted(items);
        for (T item : items) {
            if (!item.scheduled()) continue;
            try {
                if (!isDue(item, now)) continue;
            } catch (Exception error) {
                // cron 写错在保存时就报过了，这里跳过即可，不该让整轮扫描停下来。
                log.warn("跳过{} {}：cron 表达式无法解析（{}）", label, item.id(), item.cron());
                continue;
            }
            trigger.accept(item, now);
        }
    }

    boolean isDue(CronScheduled item, Instant now) {
        return CronSchedules.isDue(item.cron(), item.scheduleZoneId(), lastTriggered.getOrDefault(item.id(), item.lastRunAt()), now);
    }

    /** 记下这一窗口已经处理过，返回之前的记录（失败可以还原时用）。 */
    Instant mark(long id, Instant now) {
        return lastTriggered.put(id, now);
    }

    /** 还原 {@link #mark} 之前的记录：这次失败会自己消失，下一轮还该在同一窗口里再试。 */
    void restore(long id, Instant previous) {
        if (previous == null) lastTriggered.remove(id);
        else lastTriggered.put(id, previous);
    }

    int trackedCount() {
        return lastTriggered.size();
    }

    private void forgetDeleted(List<? extends CronScheduled> items) {
        if (lastTriggered.isEmpty()) return;
        Set<Long> known = new HashSet<>();
        for (CronScheduled item : items) known.add(item.id());
        lastTriggered.keySet().retainAll(known);
    }
}
