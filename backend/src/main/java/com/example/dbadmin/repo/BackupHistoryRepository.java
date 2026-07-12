package com.example.dbadmin.repo;

import com.example.dbadmin.model.BackupHistory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class BackupHistoryRepository {
    private final JdbcTemplate jdbc;

    private final RowMapper<BackupHistory> mapper = (rs, rowNum) -> new BackupHistory(
            rs.getLong("id"),
            rs.getLong("task_id"),
            rs.getLong("connection_id"),
            rs.getString("status"),
            rs.getString("message"),
            rs.getString("file_path"),
            rs.getObject("file_size", Long.class),
            toInstant(rs.getTimestamp("started_at")),
            toInstant(rs.getTimestamp("finished_at"))
    );

    public BackupHistoryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<BackupHistory> findByTaskId(long taskId) {
        return jdbc.query("SELECT * FROM backup_history WHERE task_id = ? ORDER BY finished_at DESC, id DESC", mapper, taskId);
    }

    public List<BackupHistory> findPageByTaskId(long taskId, int limit, long offset) {
        return jdbc.query(
                "SELECT * FROM backup_history WHERE task_id = ? ORDER BY finished_at DESC, id DESC LIMIT ? OFFSET ?",
                mapper,
                taskId,
                limit,
                offset
        );
    }

    public Optional<BackupHistory> findByTaskIdAndId(long taskId, long id) {
        List<BackupHistory> rows = jdbc.query("SELECT * FROM backup_history WHERE task_id = ? AND id = ?", mapper, taskId, id);
        return rows.stream().findFirst();
    }

    public long insert(BackupHistory history) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO backup_history(task_id, connection_id, status, message, file_path, file_size, started_at, finished_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, history.taskId());
            ps.setLong(2, history.connectionId());
            ps.setString(3, history.status());
            ps.setString(4, history.message());
            ps.setString(5, history.filePath());
            ps.setObject(6, history.fileSize());
            ps.setTimestamp(7, timestamp(history.startedAt()));
            ps.setTimestamp(8, timestamp(history.finishedAt()));
            return ps;
        }, keys);
        if (keys.getKeys() != null && keys.getKeys().get("id") instanceof Number id) {
            return id.longValue();
        }
        Number key = keys.getKey();
        return key == null ? 0 : key.longValue();
    }

    public void delete(long id) {
        jdbc.update("DELETE FROM backup_history WHERE id = ?", id);
    }

    public void deleteByTaskId(long taskId) {
        jdbc.update("DELETE FROM backup_history WHERE task_id = ?", taskId);
    }

    private Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    private Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
