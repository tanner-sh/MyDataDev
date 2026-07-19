package com.example.dbadmin.repo;

import com.example.dbadmin.model.RestoreJob;
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
public class RestoreJobRepository {
    private final JdbcTemplate jdbc;
    private final RowMapper<RestoreJob> mapper = (rs, ignored) -> new RestoreJob(
            rs.getLong("id"), rs.getString("source_kind"), rs.getLong("source_id"), rs.getString("source_name"),
            rs.getString("source_file_path"), rs.getString("source_checksum"), rs.getString("file_format"),
            rs.getString("source_db_type"), rs.getLong("target_connection_id"), rs.getString("target_db_type"),
            rs.getString("conflict_mode"), rs.getString("namespace_mapping"), rs.getString("status"),
            rs.getString("phase"), rs.getObject("progress_current", Long.class), rs.getObject("progress_total", Long.class),
            rs.getString("message"), rs.getBoolean("cancel_requested"), rs.getString("actor"),
            instant(rs.getTimestamp("started_at")), instant(rs.getTimestamp("finished_at")), instant(rs.getTimestamp("created_at"))
    );

    public RestoreJobRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(RestoreJob job) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO restore_job(source_kind, source_id, source_name, source_file_path, source_checksum,
                      file_format, source_db_type, target_connection_id, target_db_type, conflict_mode,
                      namespace_mapping, status, phase, progress_current, progress_total, message, actor)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, job.sourceKind());
            ps.setLong(2, job.sourceId());
            ps.setString(3, job.sourceName());
            ps.setString(4, job.sourceFilePath());
            ps.setString(5, job.sourceChecksum());
            ps.setString(6, job.fileFormat());
            ps.setString(7, job.sourceDbType());
            ps.setLong(8, job.targetConnectionId());
            ps.setString(9, job.targetDbType());
            ps.setString(10, job.conflictMode());
            ps.setString(11, job.namespaceMapping());
            ps.setString(12, job.status());
            ps.setString(13, job.phase());
            ps.setObject(14, job.progressCurrent());
            ps.setObject(15, job.progressTotal());
            ps.setString(16, job.message());
            ps.setString(17, job.actor());
            return ps;
        }, keys);
        Number key = keys.getKey();
        return key == null ? 0 : key.longValue();
    }

    public Optional<RestoreJob> findById(long id) {
        List<RestoreJob> rows = jdbc.query("SELECT * FROM restore_job WHERE id = ?", mapper, id);
        return rows.stream().findFirst();
    }

    public List<RestoreJob> findPage(Long connectionId, int limit, long offset) {
        return connectionId == null
                ? jdbc.query("SELECT * FROM restore_job ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?", mapper, limit, offset)
                : jdbc.query("SELECT * FROM restore_job WHERE target_connection_id = ? ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?", mapper, connectionId, limit, offset);
    }

    public List<RestoreJob> findActive(Long connectionId) {
        return connectionId == null
                ? jdbc.query("SELECT * FROM restore_job WHERE status IN ('QUEUED','RUNNING') ORDER BY id", mapper)
                : jdbc.query("SELECT * FROM restore_job WHERE target_connection_id = ? AND status IN ('QUEUED','RUNNING') ORDER BY id", mapper, connectionId);
    }

    public int countActiveByConnectionId(long connectionId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM restore_job WHERE target_connection_id = ? AND status IN ('QUEUED','RUNNING')", Integer.class, connectionId);
        return count == null ? 0 : count;
    }

    public int countActiveBySource(String sourceKind, long sourceId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM restore_job WHERE source_kind = ? AND source_id = ? AND status IN ('QUEUED','RUNNING')",
                Integer.class, sourceKind, sourceId);
        return count == null ? 0 : count;
    }

    public int countActiveByBackupTaskId(long taskId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM restore_job job
                WHERE job.source_kind = 'HISTORY' AND job.status IN ('QUEUED','RUNNING')
                  AND job.source_id IN (SELECT history.id FROM backup_history history WHERE history.task_id = ?)
                """, Integer.class, taskId);
        return count == null ? 0 : count;
    }

    public void updateProgress(long id, String status, String phase, long current, Long total, String message, Instant startedAt, Instant finishedAt) {
        jdbc.update("""
                UPDATE restore_job SET status = ?, phase = ?, progress_current = ?, progress_total = ?, message = ?,
                    started_at = COALESCE(started_at, ?), finished_at = ? WHERE id = ?
                """, status, phase, current, total, message, timestamp(startedAt), timestamp(finishedAt), id);
    }

    public void requestCancel(long id) {
        jdbc.update("UPDATE restore_job SET cancel_requested = TRUE WHERE id = ?", id);
    }

    public void failStaleRunning() {
        jdbc.update("UPDATE restore_job SET status = 'FAILED', phase = 'INTERRUPTED', message = '服务重启，上一轮恢复执行状态未知。', finished_at = CURRENT_TIMESTAMP WHERE status IN ('QUEUED','RUNNING')");
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }
}
