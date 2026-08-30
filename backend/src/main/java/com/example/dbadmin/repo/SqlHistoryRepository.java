package com.example.dbadmin.repo;

import com.example.dbadmin.dto.ApiDtos.SqlHistoryResponse;
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

    private String truncate(String value, int maximumLength) {
        if (value == null || value.length() <= maximumLength) return value;
        return value.substring(0, maximumLength);
    }
}
