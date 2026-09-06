package com.example.dbadmin.repo;

import com.example.dbadmin.model.SchemaSnapshot;
import com.example.dbadmin.model.SchemaSnapshotTarget;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class SchemaSnapshotRepository {
    /**
     * 列快照时不要带上 content。
     *
     * <p>一份快照可能有几 MB，而时间线一次要列几十条 —— 把内容一起查出来只为了显示表数量和
     * 时间，是几百 MB 的无谓传输。需要内容的只有对比那一条路径，它按 id 单独取。</p>
     */
    private static final String SUMMARY_COLUMNS =
            "id, connection_id, schema_name, label, table_count, checksum_sha256, captured_by, captured_at, last_seen_at";

    private final JdbcTemplate jdbc;

    public SchemaSnapshotRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<SchemaSnapshot> findSummaries(long connectionId, String schemaName, int limit) {
        return jdbc.query(
                "SELECT " + SUMMARY_COLUMNS + " FROM schema_snapshot WHERE connection_id = ? AND schema_name = ?"
                        + " ORDER BY captured_at DESC, id DESC LIMIT ?",
                (rs, ignored) -> map(rs, false), connectionId, schemaName, limit);
    }

    public Optional<SchemaSnapshot> findLatest(long connectionId, String schemaName) {
        return jdbc.query(
                "SELECT " + SUMMARY_COLUMNS + " FROM schema_snapshot WHERE connection_id = ? AND schema_name = ?"
                        + " ORDER BY captured_at DESC, id DESC LIMIT 1",
                (rs, ignored) -> map(rs, false), connectionId, schemaName).stream().findFirst();
    }

    public Optional<SchemaSnapshot> findById(long id) {
        return jdbc.query("SELECT * FROM schema_snapshot WHERE id = ?", (rs, ignored) -> map(rs, true), id)
                .stream().findFirst();
    }

    public long insert(SchemaSnapshot snapshot) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO schema_snapshot(connection_id, schema_name, label, table_count, checksum_sha256,
                                                content, captured_by, captured_at, last_seen_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, new String[]{"id"});
            statement.setLong(1, snapshot.connectionId());
            statement.setString(2, snapshot.schemaName());
            statement.setString(3, snapshot.label());
            statement.setInt(4, snapshot.tableCount());
            statement.setString(5, snapshot.checksumSha256());
            statement.setString(6, snapshot.content());
            statement.setString(7, snapshot.capturedBy());
            Timestamp now = Timestamp.from(snapshot.capturedAt() == null ? Instant.now() : snapshot.capturedAt());
            statement.setTimestamp(8, now);
            statement.setTimestamp(9, now);
            return statement;
        }, keys);
        Number key = keys.getKey();
        if (key == null) throw new IllegalStateException("无法获取结构快照的自增主键");
        return key.longValue();
    }

    /** 结构没变时只推进「最后一次见到」，不新增一行。 */
    public void touch(long id, Instant seenAt) {
        jdbc.update("UPDATE schema_snapshot SET last_seen_at = ? WHERE id = ?", Timestamp.from(seenAt), id);
    }

    public int delete(long id) {
        return jdbc.update("DELETE FROM schema_snapshot WHERE id = ?", id);
    }

    /**
     * 只保留最近 {@code keep} 份。
     *
     * <p>用「取第 keep+1 新的那条的 captured_at 当分界线」而不是 OFFSET 删除：H2 的 DELETE
     * 不支持 LIMIT/OFFSET，而按时间分界一条语句就能删干净。</p>
     */
    public int prune(long connectionId, String schemaName, int keep) {
        List<Timestamp> boundary = jdbc.query(
                "SELECT captured_at FROM schema_snapshot WHERE connection_id = ? AND schema_name = ?"
                        + " ORDER BY captured_at DESC, id DESC LIMIT 1 OFFSET ?",
                (rs, ignored) -> rs.getTimestamp(1), connectionId, schemaName, keep);
        if (boundary.isEmpty()) return 0;
        return jdbc.update("DELETE FROM schema_snapshot WHERE connection_id = ? AND schema_name = ? AND captured_at <= ?",
                connectionId, schemaName, boundary.get(0));
    }

    public List<SchemaSnapshotTarget> findAllTargets() {
        return jdbc.query("SELECT * FROM schema_snapshot_target ORDER BY id", (rs, ignored) -> mapTarget(rs));
    }

    public Optional<SchemaSnapshotTarget> findTarget(long connectionId, String schemaName) {
        return jdbc.query("SELECT * FROM schema_snapshot_target WHERE connection_id = ? AND schema_name = ?",
                (rs, ignored) -> mapTarget(rs), connectionId, schemaName).stream().findFirst();
    }

    public Optional<SchemaSnapshotTarget> findTargetById(long id) {
        return jdbc.query("SELECT * FROM schema_snapshot_target WHERE id = ?", (rs, ignored) -> mapTarget(rs), id)
                .stream().findFirst();
    }

    /** 同一个（连接, Schema）只允许一个采集目标，重复保存就是改它。 */
    public void upsertTarget(SchemaSnapshotTarget target) {
        int updated = jdbc.update("""
                UPDATE schema_snapshot_target
                SET cron = ?, schedule_zone = ?, enabled = ?, keep_snapshots = ?, updated_at = CURRENT_TIMESTAMP
                WHERE connection_id = ? AND schema_name = ?
                """, target.cron(), target.scheduleZone(), target.enabled(), target.keepSnapshots(),
                target.connectionId(), target.schemaName());
        if (updated > 0) return;
        jdbc.update("""
                INSERT INTO schema_snapshot_target(connection_id, schema_name, cron, schedule_zone, enabled, keep_snapshots)
                VALUES (?, ?, ?, ?, ?, ?)
                """, target.connectionId(), target.schemaName(), target.cron(), target.scheduleZone(),
                target.enabled(), target.keepSnapshots());
    }

    public void markTargetRun(long id, Instant runAt, String status, String message) {
        jdbc.update("UPDATE schema_snapshot_target SET last_run_at = ?, last_status = ?, last_message = ? WHERE id = ?",
                Timestamp.from(runAt), status, message, id);
    }

    public int deleteTarget(long id) {
        return jdbc.update("DELETE FROM schema_snapshot_target WHERE id = ?", id);
    }

    private static SchemaSnapshot map(ResultSet rs, boolean withContent) throws SQLException {
        return new SchemaSnapshot(
                rs.getLong("id"),
                rs.getLong("connection_id"),
                rs.getString("schema_name"),
                rs.getString("label"),
                rs.getInt("table_count"),
                rs.getString("checksum_sha256"),
                withContent ? rs.getString("content") : null,
                rs.getString("captured_by"),
                instant(rs.getTimestamp("captured_at")),
                instant(rs.getTimestamp("last_seen_at"))
        );
    }

    private static SchemaSnapshotTarget mapTarget(ResultSet rs) throws SQLException {
        return new SchemaSnapshotTarget(
                rs.getLong("id"),
                rs.getLong("connection_id"),
                rs.getString("schema_name"),
                rs.getString("cron"),
                rs.getString("schedule_zone"),
                rs.getBoolean("enabled"),
                rs.getInt("keep_snapshots"),
                instant(rs.getTimestamp("last_run_at")),
                rs.getString("last_status"),
                rs.getString("last_message"),
                instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("updated_at"))
        );
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
