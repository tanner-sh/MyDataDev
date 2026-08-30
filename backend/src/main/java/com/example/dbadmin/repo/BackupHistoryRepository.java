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
            toInstant(rs.getTimestamp("finished_at")),
            rs.getString("file_format"),
            rs.getString("backup_method"),
            rs.getString("source_db_type"),
            rs.getString("checksum_sha256"),
            rs.getString("phase"),
            rs.getObject("progress_current", Long.class),
            rs.getObject("progress_total", Long.class),
            rs.getBoolean("cancel_requested"),
            rs.getString("storage_type"),
            rs.getObject("storage_profile_id", Long.class),
            null,
            rs.getString("storage_object_key"),
            toInstant(rs.getTimestamp("staging_expires_at"))
    );

    public BackupHistoryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
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
                    INSERT INTO backup_history(task_id, connection_id, status, message, file_path, file_size, started_at, finished_at, file_format, backup_method, source_db_type, checksum_sha256, phase, progress_current, progress_total, cancel_requested, storage_type, storage_profile_id, storage_object_key, staging_expires_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, history.taskId());
            ps.setLong(2, history.connectionId());
            ps.setString(3, history.status());
            ps.setString(4, history.message());
            ps.setString(5, history.filePath());
            ps.setObject(6, history.fileSize());
            ps.setTimestamp(7, timestamp(history.startedAt()));
            ps.setTimestamp(8, timestamp(history.finishedAt()));
            ps.setString(9, history.fileFormat());
            ps.setString(10, history.backupMethod());
            ps.setString(11, history.sourceDbType());
            ps.setString(12, history.checksumSha256());
            ps.setString(13, history.phase());
            ps.setObject(14, history.progressCurrent());
            ps.setObject(15, history.progressTotal());
            ps.setBoolean(16, history.cancelRequested());
            ps.setString(17, history.storageType());
            ps.setObject(18, history.storageProfileId());
            ps.setString(19, history.storageObjectKey());
            ps.setTimestamp(20, timestamp(history.stagingExpiresAt()));
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

    public Optional<BackupHistory> findById(long id) {
        List<BackupHistory> rows = jdbc.query("SELECT * FROM backup_history WHERE id = ?", mapper, id);
        return rows.stream().findFirst();
    }

    public List<BackupHistory> findPageByConnectionId(long connectionId, int limit, long offset) {
        return jdbc.query("SELECT * FROM backup_history WHERE connection_id = ? ORDER BY finished_at DESC, id DESC LIMIT ? OFFSET ?", mapper, connectionId, limit, offset);
    }

    public boolean updateExecution(long id, String status, String phase, long current, Long total, String message,
                                   String filePath, Long fileSize, String checksum, Instant finishedAt) {
        return updateExecution(id, status, phase, current, total, message, filePath, fileSize, checksum, finishedAt,
                filePath == null ? null : "LOCAL", null, null, null);
    }

    public boolean updateExecution(long id, String status, String phase, long current, Long total, String message,
                                   String filePath, Long fileSize, String checksum, Instant finishedAt,
                                   String storageType, Long storageProfileId, String storageObjectKey, Instant stagingExpiresAt) {
        int updated = jdbc.update("""
                UPDATE backup_history
                SET status = ?, phase = ?, progress_current = ?, progress_total = ?, message = ?, file_path = ?,
                    file_size = ?, checksum_sha256 = ?, finished_at = ?, storage_type = ?, storage_profile_id = ?,
                    storage_object_key = ?, staging_expires_at = ?
                WHERE id = ? AND status IN ('QUEUED','RUNNING')
                  AND (? <> 'SUCCESS' OR cancel_requested = FALSE)
                """, status, phase, current, total, message, filePath, fileSize, checksum, timestamp(finishedAt),
                storageType, storageProfileId, storageObjectKey, timestamp(stagingExpiresAt), id, status);
        return updated > 0;
    }

    public boolean updateProgress(long id, String status, String phase, long current, Long total, String message) {
        return jdbc.update("""
                UPDATE backup_history SET status = ?, phase = ?, progress_current = ?, progress_total = ?, message = ?
                WHERE id = ? AND status IN ('QUEUED','RUNNING') AND cancel_requested = FALSE
                """, status, phase, current, total, message, id) > 0;
    }

    /** 上传失败记录只有通过这个入口才能重新进入活动态；同时清掉上一轮的取消标记。 */
    public boolean queueUploadRetry(long id, long total, String message) {
        return jdbc.update("""
                UPDATE backup_history
                SET status = 'QUEUED', phase = 'UPLOAD_RETRY_QUEUED', progress_current = 0,
                    progress_total = ?, message = ?, cancel_requested = FALSE
                WHERE id = ? AND status = 'FAILED' AND phase = 'UPLOAD_FAILED'
                """, total, message, id) > 0;
    }

    public List<BackupHistory> findExpiredStaging(Instant cutoff, int limit) {
        return jdbc.query("""
                SELECT * FROM backup_history WHERE phase = 'UPLOAD_FAILED' AND staging_expires_at IS NOT NULL
                  AND staging_expires_at < ? AND file_path IS NOT NULL ORDER BY staging_expires_at, id LIMIT ?
                """, mapper, timestamp(cutoff), limit);
    }

    public void markStagingExpired(long id, String message) {
        jdbc.update("UPDATE backup_history SET phase = 'UPLOAD_EXPIRED', file_path = NULL, staging_expires_at = NULL, message = ? WHERE id = ?",
                message, id);
    }

    public void requestCancel(long id) {
        jdbc.update("UPDATE backup_history SET cancel_requested = TRUE WHERE id = ? AND status IN ('QUEUED','RUNNING')", id);
    }

    public List<BackupHistory> findSuccessfulByTaskId(long taskId) {
        return jdbc.query("SELECT * FROM backup_history WHERE task_id = ? AND status = 'SUCCESS' ORDER BY finished_at DESC, id DESC", mapper, taskId);
    }

    public List<BackupHistory> findRetentionCandidates(long taskId, Integer keepCount, Instant cutoff, int limit) {
        int normalizedKeepCount = keepCount == null ? Integer.MAX_VALUE : Math.max(keepCount, 0);
        Timestamp normalizedCutoff = cutoff == null ? null : Timestamp.from(cutoff);
        return jdbc.query("""
                SELECT candidate.* FROM (
                  SELECT history.*, ROW_NUMBER() OVER (ORDER BY finished_at DESC, id DESC) AS retention_rank
                  FROM backup_history history
                  WHERE history.task_id = ? AND history.status = 'SUCCESS'
                ) candidate
                WHERE (candidate.retention_rank > ? OR (? IS NOT NULL AND candidate.finished_at < ?))
                  AND NOT EXISTS (
                    SELECT 1 FROM restore_job job
                    WHERE job.source_kind = 'HISTORY' AND job.source_id = candidate.id
                      AND job.status IN ('QUEUED','RUNNING')
                  )
                ORDER BY candidate.finished_at, candidate.id
                LIMIT ?
                """, mapper, taskId, normalizedKeepCount, normalizedCutoff, normalizedCutoff, limit);
    }

    public List<BackupHistory> findActive(Long connectionId) {
        return connectionId == null
                ? jdbc.query("SELECT * FROM backup_history WHERE status IN ('QUEUED','RUNNING') ORDER BY id", mapper)
                : jdbc.query("SELECT * FROM backup_history WHERE connection_id = ? AND status IN ('QUEUED','RUNNING') ORDER BY id", mapper, connectionId);
    }

    public Optional<BackupHistory> findActiveByTaskId(long taskId) {
        List<BackupHistory> rows = jdbc.query("SELECT * FROM backup_history WHERE task_id = ? AND status IN ('QUEUED','RUNNING') ORDER BY id DESC LIMIT 1", mapper, taskId);
        return rows.stream().findFirst();
    }

    private Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    private Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
