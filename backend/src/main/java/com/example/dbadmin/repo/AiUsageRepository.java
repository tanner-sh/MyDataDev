package com.example.dbadmin.repo;

import com.example.dbadmin.service.ai.AiUsageEntry;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

/**
 * 每天每人每个模型一行的 token 用量。
 *
 * <p>不从 {@code audit_log.detail} 里解析：那串 {@code key=value} 是给人看的，用正则在几百万行
 * 审计上做聚合，既慢又会随着 detail 的措辞一起碎掉。而这张表每天最多长出「人数 × 模型数」行，
 * 预算闸门在每次 AI 请求前都要读它一次，得是一个能走索引的小查询。</p>
 */
@Repository
public class AiUsageRepository {
    /** 用量留多久。它只用来看趋势和卡当天额度，留一年足够，再久就是白占地方。 */
    private static final int RETENTION_DAYS = 400;

    private final JdbcTemplate jdbc;

    public AiUsageRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 累加一次调用的用量。先 UPDATE 后 INSERT，撞唯一键说明并发插入，退回 UPDATE 再来一次。 */
    public void record(LocalDate day, String actor, String model, long inputTokens, long outputTokens, long cacheReadTokens) {
        String who = clamp(actor == null || actor.isBlank() ? "未知用户" : actor, 190);
        String which = clamp(model == null || model.isBlank() ? "未知模型" : model, 128);
        if (add(day, who, which, inputTokens, outputTokens, cacheReadTokens) > 0) return;
        try {
            jdbc.update("""
                    INSERT INTO ai_usage_daily(usage_date, actor, model, requests, input_tokens, output_tokens, cache_read_tokens)
                    VALUES (?, ?, ?, 1, ?, ?, ?)
                    """, day, who, which, inputTokens, outputTokens, cacheReadTokens);
        } catch (DuplicateKeyException concurrent) {
            add(day, who, which, inputTokens, outputTokens, cacheReadTokens);
        }
    }

    /** 当天全站已消耗的 token（输入 + 输出）。 */
    public long consumed(LocalDate day) {
        Long total = jdbc.queryForObject(
                "SELECT COALESCE(SUM(input_tokens + output_tokens), 0) FROM ai_usage_daily WHERE usage_date = ?",
                Long.class, day);
        return total == null ? 0 : total;
    }

    /** 当天某个人已消耗的 token。 */
    public long consumed(LocalDate day, String actor) {
        Long total = jdbc.queryForObject("""
                SELECT COALESCE(SUM(input_tokens + output_tokens), 0)
                FROM ai_usage_daily WHERE usage_date = ? AND actor = ?
                """, Long.class, day, clamp(actor == null ? "未知用户" : actor, 190));
        return total == null ? 0 : total;
    }

    /** 某一天区间内的明细，按天倒序；面板据此摆出趋势与人头。 */
    public List<AiUsageEntry> between(LocalDate from, LocalDate to) {
        return jdbc.query("""
                SELECT usage_date, actor, model, requests, input_tokens, output_tokens, cache_read_tokens
                FROM ai_usage_daily
                WHERE usage_date BETWEEN ? AND ?
                ORDER BY usage_date DESC, input_tokens + output_tokens DESC
                """, (rs, ignored) -> new AiUsageEntry(
                rs.getDate("usage_date").toLocalDate(),
                rs.getString("actor"),
                rs.getString("model"),
                rs.getInt("requests"),
                rs.getLong("input_tokens"),
                rs.getLong("output_tokens"),
                rs.getLong("cache_read_tokens")
        ), from, to);
    }

    public int deleteBefore(LocalDate day) {
        return jdbc.update("DELETE FROM ai_usage_daily WHERE usage_date < ?", day);
    }

    public int purgeExpired(LocalDate today) {
        return deleteBefore(today.minusDays(RETENTION_DAYS));
    }

    private int add(LocalDate day, String actor, String model, long inputTokens, long outputTokens, long cacheReadTokens) {
        return jdbc.update("""
                UPDATE ai_usage_daily
                SET requests = requests + 1,
                    input_tokens = input_tokens + ?,
                    output_tokens = output_tokens + ?,
                    cache_read_tokens = cache_read_tokens + ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE usage_date = ? AND actor = ? AND model = ?
                """, inputTokens, outputTokens, cacheReadTokens, day, actor, model);
    }

    private static String clamp(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
