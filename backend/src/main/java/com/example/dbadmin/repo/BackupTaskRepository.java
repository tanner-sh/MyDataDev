package com.example.dbadmin.repo;

import com.example.dbadmin.model.BackupTask;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
            null,
            stringOrDefault(rs.getString("backup_method"), "SQL"),
            rs.getString("tool_path"),
            rs.getString("extra_args"),
            rs.getString("native_connect_name"),
            rs.getString("cron"),
            rs.getBoolean("enabled"),
            rs.getString("last_status"),
            rs.getString("last_message"),
            rs.getString("last_file_path"),
            rs.getObject("last_file_size", Long.class),
            toInstant(rs.getTimestamp("last_run_at")),
            rs.getObject("retention_days", Integer.class),
            rs.getObject("retention_count", Integer.class)
    );

    public BackupTaskRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<BackupTask> findAll() {
        return attachTargets(jdbc.query("SELECT * FROM backup_task ORDER BY id DESC", mapper));
    }

    public List<BackupTask> findByConnectionId(long connectionId) {
        return attachTargets(jdbc.query("SELECT * FROM backup_task WHERE connection_id = ? ORDER BY id DESC", mapper, connectionId));
    }

    public List<BackupTask> findPage(long connectionId, String keyword, String status, int limit, long offset) {
        String safeKeyword = keyword == null ? "" : keyword.trim().toLowerCase(java.util.Locale.ROOT);
        String safeStatus = status == null ? "" : status.trim().toUpperCase(java.util.Locale.ROOT);
        return attachTargets(jdbc.query("""
                SELECT * FROM backup_task
                WHERE connection_id = ?
                  AND (? = '' OR LOWER(name) LIKE ?)
                  AND (? = '' OR last_status = ?)
                ORDER BY id DESC LIMIT ? OFFSET ?
                """, mapper, connectionId, safeKeyword, "%" + safeKeyword + "%", safeStatus, safeStatus, limit, offset));
    }

    public Optional<BackupTask> findById(long id) {
        List<BackupTask> rows = jdbc.query("SELECT * FROM backup_task WHERE id = ?", mapper, id);
        return attachTargets(rows).stream().findFirst();
    }

    public int countByConnectionId(long connectionId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM backup_task WHERE connection_id = ?", Integer.class, connectionId);
        return count == null ? 0 : count;
    }

    public int countRunningByConnectionId(long connectionId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM backup_task WHERE connection_id = ? AND last_status = 'RUNNING'",
                Integer.class,
                connectionId
        );
        return count == null ? 0 : count;
    }

    @Transactional
    public long insert(BackupTask task) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO backup_task(name, connection_id, scope, schema_name, table_name, backup_method, tool_path, extra_args, native_connect_name, cron, enabled, retention_days, retention_count)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, task.name());
            ps.setLong(2, task.connectionId());
            ps.setString(3, task.scope());
            ps.setString(4, task.schemaName());
            ps.setString(5, task.tableName());
            ps.setString(6, task.backupMethod());
            ps.setString(7, task.toolPath());
            ps.setString(8, task.extraArgs());
            ps.setString(9, task.nativeConnectName());
            ps.setString(10, task.cron());
            ps.setBoolean(11, task.enabled());
            ps.setObject(12, task.retentionDays());
            ps.setObject(13, task.retentionCount());
            return ps;
        }, keys);
        if (keys.getKeys() != null && keys.getKeys().get("id") instanceof Number id) {
            writeTargets(id.longValue(), task.tableNames());
            return id.longValue();
        }
        Number key = keys.getKey();
        long id = key == null ? 0 : key.longValue();
        writeTargets(id, task.tableNames());
        return id;
    }

    @Transactional
    public void update(long id, BackupTask task) {
        jdbc.update("""
                        UPDATE backup_task
                        SET name = ?, connection_id = ?, scope = ?, schema_name = ?, table_name = ?, backup_method = ?, tool_path = ?, extra_args = ?, native_connect_name = ?, cron = ?, enabled = ?, retention_days = ?, retention_count = ?
                        WHERE id = ?
                        """,
                task.name(), task.connectionId(), task.scope(), task.schemaName(), task.tableName(), task.backupMethod(), task.toolPath(), task.extraArgs(), task.nativeConnectName(), task.cron(), task.enabled(), task.retentionDays(), task.retentionCount(), id);
        writeTargets(id, task.tableNames());
    }

    public void updateEnabled(long id, boolean enabled) {
        jdbc.update("UPDATE backup_task SET enabled = ? WHERE id = ?", enabled, id);
    }

    public void failStaleRunningTasks() {
        jdbc.update("""
                UPDATE backup_task
                SET last_status = 'FAILED', last_message = '服务重启，上一轮备份执行状态未知。'
                WHERE last_status = 'RUNNING'
                """);
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

    public void updateSummary(long id, String status, String message, String filePath, Long fileSize, Instant runAt) {
        jdbc.update("""
                        UPDATE backup_task
                        SET last_status = ?, last_message = ?, last_file_path = ?, last_file_size = ?, last_run_at = ?
                        WHERE id = ?
                        """,
                status, message, filePath, fileSize, runAt == null ? null : Timestamp.from(runAt), id);
    }

    @Transactional
    public void delete(long id) {
        try {
            jdbc.update("DELETE FROM backup_task_table WHERE task_id = ?", id);
        } catch (DataAccessException ignored) {
            // Older installations can still delete tasks while schema initialization is completing.
        }
        jdbc.update("DELETE FROM backup_task WHERE id = ?", id);
    }

    private List<BackupTask> attachTargets(List<BackupTask> tasks) {
        if (tasks.isEmpty()) {
            return tasks;
        }
        String placeholders = String.join(",", Collections.nCopies(tasks.size(), "?"));
        Map<Long, List<String>> targetsByTask = new HashMap<>();
        try {
            List<TargetRow> targets = jdbc.query(
                    "SELECT task_id, table_name FROM backup_task_table WHERE task_id IN (" + placeholders + ") ORDER BY task_id, target_order",
                    (rs, rowNum) -> new TargetRow(rs.getLong("task_id"), rs.getString("table_name")),
                    tasks.stream().map(BackupTask::id).toArray()
            );
            for (TargetRow target : targets) {
                targetsByTask.computeIfAbsent(target.taskId(), ignored -> new ArrayList<>()).add(target.tableName());
            }
        } catch (DataAccessException ignored) {
            // Legacy fallback: table_name remains populated until all environments have run schema.sql.
        }
        return tasks.stream()
                .map(task -> withTargets(task, targetsByTask.getOrDefault(task.id(), task.tableNames())))
                .toList();
    }

    private BackupTask withTargets(BackupTask task, List<String> tableNames) {
        return new BackupTask(
                task.id(), task.name(), task.connectionId(), task.scope(), task.schemaName(), task.tableName(), tableNames,
                task.backupMethod(), task.toolPath(), task.extraArgs(), task.nativeConnectName(), task.cron(), task.enabled(),
                task.lastStatus(), task.lastMessage(), task.lastFilePath(), task.lastFileSize(), task.lastRunAt(),
                task.retentionDays(), task.retentionCount()
        );
    }

    private void writeTargets(long taskId, List<String> tableNames) {
        jdbc.update("DELETE FROM backup_task_table WHERE task_id = ?", taskId);
        if (tableNames == null || tableNames.isEmpty()) {
            return;
        }
        List<Object[]> batch = new ArrayList<>();
        for (int index = 0; index < tableNames.size(); index++) {
            batch.add(new Object[]{taskId, index, tableNames.get(index)});
        }
        jdbc.batchUpdate("INSERT INTO backup_task_table(task_id, target_order, table_name) VALUES (?, ?, ?)", batch);
    }

    private Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    private String stringOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private record TargetRow(long taskId, String tableName) {
    }
}
