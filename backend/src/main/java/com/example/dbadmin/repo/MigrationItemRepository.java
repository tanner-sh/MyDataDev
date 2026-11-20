package com.example.dbadmin.repo;

import com.example.dbadmin.model.MigrationItem;
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
public class MigrationItemRepository {
    private final JdbcTemplate jdbc;
    private final RowMapper<MigrationItem> mapper = (rs, ignored) -> new MigrationItem(
            rs.getLong("id"),
            rs.getLong("job_id"),
            rs.getString("item_kind"),
            rs.getString("source_name"),
            rs.getString("target_name"),
            rs.getString("table_id"),
            rs.getInt("execution_order"),
            rs.getString("status"),
            rs.getLong("source_rows"),
            rs.getLong("migrated_rows"),
            rs.getLong("migrated_bytes"),
            rs.getInt("total_parts"),
            rs.getInt("completed_parts"),
            rs.getString("checkpoint_json"),
            rs.getString("ddl_json"),
            rs.getString("warnings_json"),
            rs.getString("verify_status"),
            rs.getString("verify_detail_json"),
            rs.getString("error_message"),
            instant(rs.getTimestamp("started_at")),
            instant(rs.getTimestamp("finished_at"))
    );

    public MigrationItemRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(MigrationItem item) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO migration_item(
                        job_id, item_kind, source_name, target_name, table_id, execution_order,
                        status, source_rows, migrated_rows, migrated_bytes, total_parts, completed_parts,
                        checkpoint_json, ddl_json, warnings_json, verify_status, verify_detail_json, error_message
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, new String[]{"id"});
            ps.setLong(1, item.jobId());
            ps.setString(2, item.itemKind());
            ps.setString(3, item.sourceName());
            ps.setString(4, item.targetName());
            ps.setString(5, item.tableId());
            ps.setInt(6, item.executionOrder());
            ps.setString(7, item.status());
            ps.setLong(8, item.sourceRows());
            ps.setLong(9, item.migratedRows());
            ps.setLong(10, item.migratedBytes());
            ps.setInt(11, item.totalParts());
            ps.setInt(12, item.completedParts());
            ps.setString(13, item.checkpointJson());
            ps.setString(14, item.ddlJson());
            ps.setString(15, item.warningsJson());
            ps.setString(16, item.verifyStatus());
            ps.setString(17, item.verifyDetailJson());
            ps.setString(18, abbreviate(item.errorMessage(), 4000));
            return ps;
        }, keys);
        Number key = keys.getKey();
        return key == null ? 0 : key.longValue();
    }

    public List<MigrationItem> findByJobId(long jobId) {
        return jdbc.query("SELECT * FROM migration_item WHERE job_id = ? ORDER BY execution_order, id", mapper, jobId);
    }

    public Optional<MigrationItem> findById(long id) {
        List<MigrationItem> rows = jdbc.query("SELECT * FROM migration_item WHERE id = ?", mapper, id);
        return rows.stream().findFirst();
    }

    public void updateProgress(long id, String status, long sourceRows, long migratedRows, long migratedBytes,
                               int totalParts, int completedParts, String checkpointJson, String errorMessage,
                               Instant startedAt, Instant finishedAt) {
        jdbc.update("""
                UPDATE migration_item
                SET status = ?, source_rows = ?, migrated_rows = ?, migrated_bytes = ?,
                    total_parts = ?, completed_parts = ?, checkpoint_json = ?, error_message = ?,
                    started_at = COALESCE(started_at, ?), finished_at = ?
                WHERE id = ?
                """, status, sourceRows, migratedRows, migratedBytes, totalParts, completedParts,
                checkpointJson, abbreviate(errorMessage, 4000),
                timestamp(startedAt), timestamp(finishedAt), id);
    }

    public void updateVerification(long id, String verifyStatus, String verifyDetailJson) {
        jdbc.update("UPDATE migration_item SET verify_status = ?, verify_detail_json = ? WHERE id = ?",
                verifyStatus, verifyDetailJson, id);
    }

    public void resetFailedItemsForRetry(long jobId) {
        jdbc.update("""
                UPDATE migration_item
                SET status = 'PENDING', error_message = NULL, finished_at = NULL
                WHERE job_id = ? AND status IN ('FAILED', 'CANCELLED', 'DATA_RUNNING', 'DDL_RUNNING')
                """, jobId);
    }

    public void failStaleRunning() {
        jdbc.update("""
                UPDATE migration_item
                SET status = 'FAILED', error_message = '服务重启导致本项中断', finished_at = CURRENT_TIMESTAMP
                WHERE status IN ('DDL_RUNNING', 'DATA_RUNNING', 'INDEX_RUNNING', 'RUNNING')
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
