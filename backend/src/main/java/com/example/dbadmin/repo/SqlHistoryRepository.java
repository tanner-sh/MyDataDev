package com.example.dbadmin.repo;

import com.example.dbadmin.dto.ApiDtos.SqlHistoryResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;

@Repository
public class SqlHistoryRepository {
    private static final Logger log = LoggerFactory.getLogger(SqlHistoryRepository.class);
    private static final int MAX_STORED_SQL_CHARS = 50_000;
    private static final int MAX_STORED_ERROR_CHARS = 10_000;
    private final JdbcTemplate jdbc;

    public SqlHistoryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(long connectionId, String sql, String type, String status, long elapsedMs, String errorMessage, String actor) {
        try {
            jdbc.update("""
                    INSERT INTO sql_history(connection_id, sql_text, sql_type, status, elapsed_ms, error_message, actor)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """,
                    connectionId,
                    truncate(sql, MAX_STORED_SQL_CHARS),
                    truncate(type, 40),
                    truncate(status, 40),
                    elapsedMs,
                    truncate(errorMessage, MAX_STORED_ERROR_CHARS),
                    truncate(actor == null || actor.isBlank() ? "admin" : actor, 120)
            );
        } catch (RuntimeException error) {
            log.error("Unable to persist SQL history connection={} type={} status={}", connectionId, type, status, error);
        }
    }

    public List<SqlHistoryResponse> findRecent(long connectionId, int limit) {
        int cappedLimit = Math.max(1, Math.min(limit, 200));
        return jdbc.query("""
                SELECT id, connection_id, sql_text, sql_type, status, elapsed_ms, error_message, actor, created_at
                FROM sql_history
                WHERE connection_id = ?
                ORDER BY id DESC
                LIMIT ?
                """, (rs, rowNum) -> {
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
                    createdAt == null ? "" : createdAt.toInstant().toString()
            );
        }, connectionId, cappedLimit);
    }

    private String truncate(String value, int maximumLength) {
        if (value == null || value.length() <= maximumLength) return value;
        return value.substring(0, maximumLength);
    }
}
