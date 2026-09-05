package com.example.dbadmin.service.ai;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把每人每模型的明细汇总成面板要的两个视角：按天和按人。
 *
 * <p>纯逻辑单独放，是因为「今天用了多少」这件事有两处会读：预算闸门和用量面板，两处的口径
 * 必须一致 —— 面板显示还剩额度、请求却被拒了，比不显示更糟。</p>
 */
public final class AiUsageSummary {
    /** 面板上最多列几个人。用量分布是长尾，前十之后基本都是个位数请求。 */
    public static final int MAX_ACTORS = 10;

    private AiUsageSummary() {
    }

    public static Result summarize(List<AiUsageEntry> entries, LocalDate today, String caller) {
        Map<LocalDate, Totals> byDay = new LinkedHashMap<>();
        Map<String, Totals> byActor = new LinkedHashMap<>();
        long usedToday = 0;
        long usedTodayByCaller = 0;
        for (AiUsageEntry entry : entries) {
            byDay.computeIfAbsent(entry.day(), ignored -> new Totals()).add(entry);
            byActor.computeIfAbsent(entry.actor(), ignored -> new Totals()).add(entry);
            if (entry.day().equals(today)) {
                usedToday += entry.billable();
                if (entry.actor().equals(caller)) usedTodayByCaller += entry.billable();
            }
        }

        List<DayUsage> days = new ArrayList<>();
        byDay.forEach((day, totals) -> days.add(new DayUsage(day, totals.requests, totals.inputTokens,
                totals.outputTokens, totals.cacheReadTokens)));
        days.sort(Comparator.comparing(DayUsage::day).reversed());

        List<ActorUsage> actors = new ArrayList<>();
        byActor.forEach((actor, totals) -> actors.add(new ActorUsage(actor, totals.requests,
                totals.inputTokens + totals.outputTokens)));
        actors.sort(Comparator.comparingLong(ActorUsage::tokens).reversed()
                .thenComparing(ActorUsage::actor));

        return new Result(usedToday, usedTodayByCaller, List.copyOf(days),
                List.copyOf(actors.subList(0, Math.min(actors.size(), MAX_ACTORS))));
    }

    public record Result(long usedToday, long usedTodayByCaller, List<DayUsage> days, List<ActorUsage> actors) {
    }

    public record DayUsage(LocalDate day, int requests, long inputTokens, long outputTokens, long cacheReadTokens) {
    }

    public record ActorUsage(String actor, int requests, long tokens) {
    }

    private static final class Totals {
        private int requests;
        private long inputTokens;
        private long outputTokens;
        private long cacheReadTokens;

        private void add(AiUsageEntry entry) {
            requests += entry.requests();
            inputTokens += entry.inputTokens();
            outputTokens += entry.outputTokens();
            cacheReadTokens += entry.cacheReadTokens();
        }
    }
}
