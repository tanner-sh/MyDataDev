package com.example.dbadmin.repo;

import com.example.dbadmin.model.MigrationJob;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class MigrationJobRepository {
    private final JdbcTemplate jdbc;
    private final RowMapper<MigrationJob> mapper = (rs, ignored) -> new MigrationJob(
            rs.getLong("id"),
            rs.getString("mode"),
            rs.getString("source_kind"),
            rs.getObject("source_connection_id", Long.class),
            rs.getObject("source_artifact_id", Long.class),
            rs.getString("source_db_type"),
            rs.getString("source_schema"),
            rs.getObject("target_connection_id", Long.class),
            rs.getString("target_db_type"),
            rs.getString("target_schema"),
            rs.getObject("output_artifact_id", Long.class),
            rs.getString("conflict_mode"),
            rs.getString("options_json"),
            rs.getString("assessment_json"),
            rs.getString("snapshot_token"),
            rs.getString("status"),
            rs.getString("phase"),
            rs.getInt("total_items"),
            rs.getInt("completed_items"),
            rs.getInt("failed_items"),
            rs.getLong("total_rows"),
            rs.getLong("migrated_rows"),
            rs.getLong("migrated_bytes"),
            rs.getString("verify_status"),
            rs.getString("verify_report_json"),
            rs.getString("message"),
            rs.getBoolean("cancel_requested"),
            rs.getString("actor"),
            rs.getObject("owner_user_id", Long.class),
            instant(rs.getTimestamp("started_at")),
            instant(rs.getTimestamp("finished_at")),
            instant(rs.getTimestamp("created_at"))
    );

    public MigrationJobRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(MigrationJob job) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO migration_job(
                        mode, source_kind, source_connection_id, source_artifact_id, source_db_type, source_schema,
                        target_connection_id, target_db_type, target_schema, output_artifact_id,
                        conflict_mode, options_json, assessment_json, snapshot_token,
                        status, phase, total_items, completed_items, failed_items,
                        total_rows, migrated_rows, migrated_bytes, verify_status, verify_report_json,
                        message, actor, owner_user_id
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, new String[]{"id"});
            ps.setString(1, job.mode());
            ps.setString(2, job.sourceKind());
            ps.setObject(3, job.sourceConnectionId());
            ps.setObject(4, job.sourceArtifactId());
            ps.setString(5, job.sourceDbType());
            ps.setString(6, job.sourceSchema());
            ps.setObject(7, job.targetConnectionId());
            ps.setString(8, job.targetDbType());
            ps.setString(9, job.targetSchema());
            ps.setObject(10, job.outputArtifactId());
            ps.setString(11, job.conflictMode());
            ps.setString(12, job.optionsJson());
            ps.setString(13, job.assessmentJson());
            ps.setString(14, job.snapshotToken());
            ps.setString(15, job.status());
            ps.setString(16, job.phase());
            ps.setInt(17, job.totalItems());
            ps.setInt(18, job.completedItems());
            ps.setInt(19, job.failedItems());
            ps.setLong(20, job.totalRows());
            ps.setLong(21, job.migratedRows());
            ps.setLong(22, job.migratedBytes());
            ps.setString(23, job.verifyStatus());
            ps.setString(24, job.verifyReportJson());
            ps.setString(25, job.message());
            ps.setString(26, job.actor());
            ps.setObject(27, job.ownerUserId());
            return ps;
        }, keys);
        Number key = keys.getKey();
        return key == null ? 0 : key.longValue();
    }

    public Optional<MigrationJob> findById(long id) {
        List<MigrationJob> rows = jdbc.query("SELECT * FROM migration_job WHERE id = ?", mapper, id);
        return rows.stream().findFirst();
    }

    public List<MigrationJob> findPage(Long connectionId, int limit, long offset) {
        if (connectionId == null) {
            return jdbc.query("SELECT * FROM migration_job ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?",
                    mapper, limit, offset);
        }
        return jdbc.query("""
                SELECT * FROM migration_job
                WHERE source_connection_id = ? OR target_connection_id = ?
                ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?
                """, mapper, connectionId, connectionId, limit, offset);
    }

    public List<MigrationJob> findActive(Long connectionId) {
        if (connectionId == null) {
            return jdbc.query("SELECT * FROM migration_job WHERE status IN ('QUEUED','RUNNING') ORDER BY id", mapper);
        }
        return jdbc.query("""
                SELECT * FROM migration_job
                WHERE (source_connection_id = ? OR target_connection_id = ?) AND status IN ('QUEUED','RUNNING')
                ORDER BY id
                """, mapper, connectionId, connectionId);
    }

    public int countActiveByArtifactId(long artifactId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM migration_job
                WHERE (source_artifact_id = ? OR output_artifact_id = ?) AND status IN ('QUEUED','RUNNING')
                """, Integer.class, artifactId, artifactId);
        return count == null ? 0 : count;
    }

    public void updateProgress(long id, String status, String phase, int totalItems, int completedItems, int failedItems,
                               long totalRows, long migratedRows, long migratedBytes, String message,
                               Instant startedAt, Instant finishedAt) {
        jdbc.update("""
                UPDATE migration_job
                SET status = ?, phase = ?, total_items = ?, completed_items = ?, failed_items = ?,
                    total_rows = ?, migrated_rows = ?, migrated_bytes = ?, message = ?,
                    started_at = COALESCE(started_at, ?), finished_at = ?
                WHERE id = ? AND status IN ('QUEUED','RUNNING')
                """, status, phase, totalItems, completedItems, failedItems,
                totalRows, migratedRows, migratedBytes, abbreviate(message, 2000),
                timestamp(startedAt), timestamp(finishedAt), id);
    }

    public void reopenForRetry(long id, String message) {
        jdbc.update("""
                UPDATE migration_job
                SET status = 'QUEUED', phase = 'RETRY_QUEUED', cancel_requested = FALSE,
                    finished_at = NULL, message = ?
                WHERE id = ?
                """, abbreviate(message, 2000), id);
    }

    public void updateSnapshotToken(long id, String snapshotToken) {
        jdbc.update("UPDATE migration_job SET snapshot_token = ? WHERE id = ?", snapshotToken, id);
    }

    public void updateOutputArtifactId(long id, long artifactId) {
        jdbc.update("UPDATE migration_job SET output_artifact_id = ? WHERE id = ?", artifactId, id);
    }

    public void updateVerification(long id, String verifyStatus, String verifyReportJson) {
        jdbc.update("UPDATE migration_job SET verify_status = ?, verify_report_json = ? WHERE id = ?",
                verifyStatus, verifyReportJson, id);
    }

    public void requestCancel(long id) {
        jdbc.update("UPDATE migration_job SET cancel_requested = TRUE WHERE id = ?", id);
    }

    public boolean isCancelRequested(long id) {
        Boolean flag = jdbc.queryForObject("SELECT cancel_requested FROM migration_job WHERE id = ?", Boolean.class, id);
        return Boolean.TRUE.equals(flag);
    }

    public void failStaleRunning() {
        jdbc.update("""
                UPDATE migration_job
                SET status = 'FAILED', phase = 'INTERRUPTED',
                    message = '服务重启，上一轮迁移任务被中断，可使用「失败项重试」继续。',
                    finished_at = CURRENT_TIMESTAMP
                WHERE status IN ('QUEUED','RUNNING')
                """);
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static String abbreviate(String text, int max) {
        if (text == null || text.length() <= max) return text;
        return text.substring(0, max - 1) + "…";
    }
}
