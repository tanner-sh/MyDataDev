package com.example.dbadmin.repo;

import com.example.dbadmin.dto.ApiDtos.SqlHistoryGroup;
import com.example.dbadmin.dto.ApiDtos.SqlHistoryResponse;
import com.example.dbadmin.dto.ApiDtos.SqlHistoryStats;
import com.example.dbadmin.dto.ApiDtos.SqlHistorySummary;
import com.example.dbadmin.auth.WebIdentityContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Locale;

@Repository
public class SqlHistoryRepository {
    private static final Logger log = LoggerFactory.getLogger(SqlHistoryRepository.class);
    public static final int MAX_HISTORY_LIMIT = 200;
    private static final int MAX_STORED_SQL_CHARS = 50_000;
    private static final int MAX_STORED_ERROR_CHARS = 10_000;
    private final JdbcTemplate jdbc;

    public SqlHistoryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(long connectionId, String sql, String type, String status, long elapsedMs, String errorMessage, String actor) {
        try {
            Long actorUserId = WebIdentityContext.current().map(com.example.dbadmin.auth.WebIdentity::userId).orElse(null);
            jdbc.update("""
                    INSERT INTO sql_history(connection_id, sql_text, sql_type, status, elapsed_ms, error_message, actor, actor_user_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    connectionId,
                    truncate(sql, MAX_STORED_SQL_CHARS),
                    truncate(type, 40),
                    truncate(status, 40),
                    elapsedMs,
                    truncate(errorMessage, MAX_STORED_ERROR_CHARS),
                    truncate(actor == null || actor.isBlank() ? "admin" : actor, 120),
                    actorUserId
            );
        } catch (RuntimeException error) {
            log.error("Unable to persist SQL history connection={} type={} status={}", connectionId, type, status, error);
        }
    }

    public List<SqlHistoryResponse> findRecent(long connectionId, int limit) {
        return findRecent(connectionId, null, limit);
    }

    /**
     * 按连接倒序返回执行历史，可按关键字过滤 SQL 正文与错误信息。
     *
     * <p>过滤下推到 SQL 而不是留给前端，是因为前端只持有最近一页：在浏览器里过滤等于
     * 「只能搜到最近 N 条」，而历史本身保留 90 天。</p>
     */
    public List<SqlHistoryResponse> findRecent(long connectionId, String keyword, int limit) {
        return findRecent(connectionId, keyword, limit, null);
    }

    public List<SqlHistoryResponse> findRecent(long connectionId, String keyword, int limit, Long actorUserId) {
        int cappedLimit = Math.max(1, Math.min(limit, MAX_HISTORY_LIMIT));
        String normalized = keyword == null || keyword.isBlank()
                ? null
                : keyword.trim().toLowerCase(Locale.ROOT);
        java.util.List<Object> parameters = new java.util.ArrayList<>();
        parameters.add(connectionId);
        StringBuilder filter = new StringBuilder();
        if (actorUserId != null) {
            filter.append(" AND actor_user_id = ?");
            parameters.add(actorUserId);
        }
        if (normalized != null) {
            filter.append(" AND (LOWER(sql_text) LIKE ? ESCAPE '!' OR LOWER(error_message) LIKE ? ESCAPE '!')");
            parameters.add(likePattern(normalized));
            parameters.add(likePattern(normalized));
        }
        parameters.add(cappedLimit);
        return jdbc.query("SELECT id, connection_id, sql_text, sql_type, status, elapsed_ms, error_message, actor, actor_user_id, created_at"
                + " FROM sql_history WHERE connection_id = ?" + filter + " ORDER BY id DESC LIMIT ?", (rs, rowNum) -> {
            Timestamp createdAt = rs.getTimestamp("created_at");
            return new SqlHistoryResponse(
                    rs.getLong("id"),
                    rs.getLong("connection_id"),
                    rs.getString("sql_text"),
                    rs.getString("sql_type"),
                    rs.getString("status"),
                    rs.getLong("elapsed_ms"),
                    rs.getString("error_message"),
                    rs.getString("actor"),
                    rs.getObject("actor_user_id", Long.class),
                    createdAt == null ? "" : createdAt.toInstant().toString()
            );
        }, parameters.toArray());
    }

    /** 关键字里的 LIKE 元字符必须转义，否则用户搜 "100%" 会匹配到所有记录。 */
    private String likePattern(String keyword) {
        return "%" + keyword.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }

    /**
     * 一段时间内的执行统计。
     *
     * <p>耗时、状态、执行人、时间本来就都记在 {@code sql_history} 里，只是从来没有被聚合过 ——
     * 「哪条查询最慢」「哪条一直在失败」这类问题，此前只能靠人一页页翻历史列表。</p>
     *
     * <p>按 SQL 原文分组而不是抹掉字面量之后再分组：应用发出的查询本来就一模一样，而人手写的
     * 查询每次都不同，抹字面量只会把一堆互不相干的临时查询归成一类。</p>
     */
    public SqlHistoryStats stats(long connectionId, int days, Long actorUserId, int topN) {
        int window = Math.max(1, Math.min(days, 365));
        int limit = Math.max(1, Math.min(topN, 50));
        java.time.Instant since = java.time.Instant.now().minus(java.time.Duration.ofDays(window));
        Timestamp from = Timestamp.from(since);
        String scope = actorUserId == null ? "" : " AND actor_user_id = ?";
        java.util.function.Function<java.util.List<Object>, Object[]> args = extra -> {
            java.util.List<Object> values = new java.util.ArrayList<>();
            values.add(connectionId);
            values.add(from);
            if (actorUserId != null) values.add(actorUserId);
            values.addAll(extra);
            return values.toArray();
        };

        SqlHistorySummary summary = jdbc.queryForObject(
                "SELECT COUNT(*) AS total, SUM(CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END) AS failed,"
                        + " COALESCE(AVG(elapsed_ms), 0) AS average, COALESCE(MAX(elapsed_ms), 0) AS slowest"
                        + " FROM sql_history WHERE connection_id = ? AND created_at >= ?" + scope,
                (rs, ignored) -> new SqlHistorySummary(rs.getInt("total"), rs.getInt("failed"),
                        Math.round(rs.getDouble("average")), rs.getLong("slowest")),
                args.apply(java.util.List.of()));

        java.util.List<SqlHistoryResponse> slowest = jdbc.query(
                "SELECT id, connection_id, sql_text, sql_type, status, elapsed_ms, error_message, actor, actor_user_id, created_at"
                        + " FROM sql_history WHERE connection_id = ? AND created_at >= ?" + scope
                        + " AND status <> 'FAILED' ORDER BY elapsed_ms DESC, id DESC LIMIT ?",
                (rs, ignored) -> row(rs), args.apply(java.util.List.of(limit)));

        java.util.List<SqlHistoryGroup> failures = jdbc.query(
                "SELECT CAST(sql_text AS VARCHAR(2000)) AS text, COUNT(*) AS hits,"
                        + " COALESCE(AVG(elapsed_ms), 0) AS average, MAX(created_at) AS last_seen"
                        + " FROM sql_history WHERE connection_id = ? AND created_at >= ?" + scope
                        + " AND status = 'FAILED' GROUP BY CAST(sql_text AS VARCHAR(2000))"
                        + " ORDER BY hits DESC LIMIT ?",
                (rs, ignored) -> group(rs), args.apply(java.util.List.of(limit)));

        java.util.List<SqlHistoryGroup> busiest = jdbc.query(
                "SELECT COALESCE(actor, '未知用户') AS text, COUNT(*) AS hits,"
                        + " COALESCE(AVG(elapsed_ms), 0) AS average, MAX(created_at) AS last_seen"
                        + " FROM sql_history WHERE connection_id = ? AND created_at >= ?" + scope
                        + " GROUP BY COALESCE(actor, '未知用户') ORDER BY hits DESC LIMIT ?",
                (rs, ignored) -> group(rs), args.apply(java.util.List.of(limit)));

        return new SqlHistoryStats(window, summary, slowest, failures, busiest);
    }

    private static SqlHistoryGroup group(java.sql.ResultSet rs) throws java.sql.SQLException {
        Timestamp lastSeen = rs.getTimestamp("last_seen");
        return new SqlHistoryGroup(rs.getString("text"), rs.getInt("hits"),
                Math.round(rs.getDouble("average")),
                lastSeen == null ? "" : lastSeen.toInstant().toString());
    }

    private static SqlHistoryResponse row(java.sql.ResultSet rs) throws java.sql.SQLException {
        Timestamp createdAt = rs.getTimestamp("created_at");
        return new SqlHistoryResponse(
                rs.getLong("id"),
                rs.getLong("connection_id"),
                rs.getString("sql_text"),
                rs.getString("sql_type"),
                rs.getString("status"),
                rs.getLong("elapsed_ms"),
                rs.getString("error_message"),
                rs.getString("actor"),
                rs.getObject("actor_user_id", Long.class),
                createdAt == null ? "" : createdAt.toInstant().toString()
        );
    }

    private String truncate(String value, int maximumLength) {
        if (value == null || value.length() <= maximumLength) return value;
        return value.substring(0, maximumLength);
    }
}
