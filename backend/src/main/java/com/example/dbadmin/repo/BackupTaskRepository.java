package com.example.dbadmin.repo;

import com.example.dbadmin.model.BackupTask;
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
public class BackupTaskRepository {
    private final JdbcTemplate jdbc;

    private final RowMapper<BackupTask> mapper = (rs, rowNum) -> new BackupTask(
            rs.getLong("id"),
            rs.getString("name"),
            rs.getLong("connection_id"),
            rs.getString("scope"),
            rs.getString("schema_name"),
            rs.getString("table_name"),
            rs.getString("cron"),
            rs.getBoolean("enabled"),
            rs.getString("last_status"),
            rs.getString("last_message"),
            rs.getString("last_file_path"),
            rs.getObject("last_file_size", Long.class),
            toInstant(rs.getTimestamp("last_run_at"))
    );

    public BackupTaskRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<BackupTask> findAll() {
        return jdbc.query("SELECT * FROM backup_task ORDER BY id DESC", mapper);
    }

    public List<BackupTask> findByConnectionId(long connectionId) {
        return jdbc.query("SELECT * FROM backup_task WHERE connection_id = ? ORDER BY id DESC", mapper, connectionId);
    }

    public Optional<BackupTask> findById(long id) {
        List<BackupTask> rows = jdbc.query("SELECT * FROM backup_task WHERE id = ?", mapper, id);
        return rows.stream().findFirst();
    }

    public int countByConnectionId(long connectionId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM backup_task WHERE connection_id = ?", Integer.class, connectionId);
        return count == null ? 0 : count;
    }

    public long insert(BackupTask task) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO backup_task(name, connection_id, scope, schema_name, table_name, cron, enabled)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, task.name());
            ps.setLong(2, task.connectionId());
            ps.setString(3, task.scope());
            ps.setString(4, task.schemaName());
            ps.setString(5, task.tableName());
            ps.setString(6, task.cron());
            ps.setBoolean(7, task.enabled());
            return ps;
        }, keys);
        if (keys.getKeys() != null && keys.getKeys().get("id") instanceof Number id) {
            return id.longValue();
        }
        Number key = keys.getKey();
        return key == null ? 0 : key.longValue();
    }

    public void update(long id, BackupTask task) {
        jdbc.update("""
                        UPDATE backup_task
                        SET name = ?, connection_id = ?, scope = ?, schema_name = ?, table_name = ?, cron = ?, enabled = ?
                        WHERE id = ?
                        """,
                task.name(), task.connectionId(), task.scope(), task.schemaName(), task.tableName(), task.cron(), task.enabled(), id);
    }

    public void updateEnabled(long id, boolean enabled) {
        jdbc.update("UPDATE backup_task SET enabled = ? WHERE id = ?", enabled, id);
    }

    public void updateStatus(long id, String status, String message) {
        updateStatus(id, status, message, null, null);
    }

    public void updateStatus(long id, String status, String message, String filePath, Long fileSize) {
        jdbc.update("""
                        UPDATE backup_task
                        SET last_status = ?, last_message = ?, last_file_path = ?, last_file_size = ?, last_run_at = CURRENT_TIMESTAMP
                        WHERE id = ?
                        """,
                status, message, filePath, fileSize, id);
    }

    public void delete(long id) {
        jdbc.update("DELETE FROM backup_task WHERE id = ?", id);
    }

    private Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
