package com.example.dbadmin.repo;

import com.example.dbadmin.dto.ApiDtos.SqlHistoryResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;

@Repository
public class SqlHistoryRepository {
    private final JdbcTemplate jdbc;

    public SqlHistoryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(long connectionId, String sql, String type, String status, long elapsedMs, String errorMessage, String actor) {
        jdbc.update("""
                INSERT INTO sql_history(connection_id, sql_text, sql_type, status, elapsed_ms, error_message, actor)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, connectionId, sql, type, status, elapsedMs, errorMessage, actor == null || actor.isBlank() ? "admin" : actor);
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
}
