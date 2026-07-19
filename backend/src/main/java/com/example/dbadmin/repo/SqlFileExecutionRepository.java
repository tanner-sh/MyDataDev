package com.example.dbadmin.repo;

import com.example.dbadmin.model.SqlFileExecution;
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
public class SqlFileExecutionRepository {
    private final JdbcTemplate jdbc;
    private final RowMapper<SqlFileExecution> mapper = (rs, ignored) -> new SqlFileExecution(
            rs.getLong("id"), rs.getLong("connection_id"), rs.getString("connection_name"), rs.getString("target_db_type"),
            rs.getString("file_name"), rs.getString("file_path"), rs.getLong("file_size"), rs.getString("checksum_sha256"),
            rs.getString("detected_charset"), rs.getString("status"), rs.getString("phase"), rs.getLong("processed_bytes"),
            rs.getObject("statement_total", Long.class), rs.getLong("statement_current"), rs.getLong("query_count"),
            rs.getLong("mutation_count"), rs.getLong("ddl_count"), rs.getLong("unknown_count"), rs.getLong("success_count"),
            rs.getLong("query_row_count"), rs.getObject("failed_statement_index", Long.class), rs.getString("failed_sql_preview"),
            rs.getString("message"), rs.getBoolean("metadata_changed"), rs.getBoolean("session_changed"),
            rs.getBoolean("cancel_requested"), rs.getString("actor"), instant(rs.getTimestamp("expires_at")),
            instant(rs.getTimestamp("started_at")), instant(rs.getTimestamp("finished_at")), instant(rs.getTimestamp("created_at"))
    );

    public SqlFileExecutionRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public long insert(SqlFileExecution job) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO sql_file_execution(connection_id, connection_name, target_db_type, file_name, file_path,
                      file_size, checksum_sha256, status, phase, processed_bytes, message, actor, expires_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, job.connectionId());
            ps.setString(2, job.connectionName());
            ps.setString(3, job.targetDbType());
            ps.setString(4, job.fileName());
            ps.setString(5, job.filePath());
            ps.setLong(6, job.fileSize());
            ps.setString(7, job.checksumSha256());
            ps.setString(8, job.status());
            ps.setString(9, job.phase());
            ps.setLong(10, job.processedBytes());
            ps.setString(11, job.message());
            ps.setString(12, job.actor());
            ps.setTimestamp(13, Timestamp.from(job.expiresAt()));
            return ps;
        }, keys);
        Number key = keys.getKeys() == null ? null : keys.getKeys().entrySet().stream()
                .filter(entry -> "id".equalsIgnoreCase(entry.getKey()))
                .map(entry -> entry.getValue() instanceof Number number ? number : null)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);
        return key == null ? 0 : key.longValue();
    }

    public Optional<SqlFileExecution> findById(long id) {
        return jdbc.query("SELECT * FROM sql_file_execution WHERE id = ?", mapper, id).stream().findFirst();
    }

    public List<SqlFileExecution> findPage(Long connectionId, int limit, long offset) {
        return connectionId == null
                ? jdbc.query("SELECT * FROM sql_file_execution ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?", mapper, limit, offset)
                : jdbc.query("SELECT * FROM sql_file_execution WHERE connection_id = ? ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?", mapper, connectionId, limit, offset);
    }

    public List<SqlFileExecution> findActive() {
        return jdbc.query("SELECT * FROM sql_file_execution WHERE status IN ('ANALYZING','QUEUED','RUNNING')", mapper);
    }

    public void updateAnalysisProgress(long id, long bytes) {
        jdbc.update("UPDATE sql_file_execution SET processed_bytes = ? WHERE id = ? AND status = 'ANALYZING'", bytes, id);
    }

    public void markReady(long id, String charset, long total, long query, long mutation, long ddl, long unknown, boolean metadata, boolean session) {
        jdbc.update("""
                UPDATE sql_file_execution SET status='READY', phase='READY', detected_charset=?, processed_bytes=file_size,
                  statement_total=?, query_count=?, mutation_count=?, ddl_count=?, unknown_count=?, metadata_changed=?,
                  session_changed=?, message='文件解析完成，等待确认执行。' WHERE id=? AND status='ANALYZING'
                """, charset, total, query, mutation, ddl, unknown, metadata, session, id);
    }

    public boolean queue(long id) {
        return jdbc.update("""
                UPDATE sql_file_execution job SET status='QUEUED', phase='QUEUED', message='任务已进入执行队列。'
                WHERE job.id=? AND job.status='READY' AND NOT EXISTS (
                  SELECT 1 FROM sql_file_execution active
                  WHERE active.connection_id=job.connection_id AND active.id<>job.id
                    AND active.status IN ('QUEUED','RUNNING')
                )
                """, id) == 1;
    }

    public int countRunningByConnection(long connectionId) {
        Integer value = jdbc.queryForObject("SELECT COUNT(*) FROM sql_file_execution WHERE connection_id=? AND status IN ('QUEUED','RUNNING')", Integer.class, connectionId);
        return value == null ? 0 : value;
    }

    public void markRunning(long id) {
        jdbc.update("UPDATE sql_file_execution SET status='RUNNING', phase='EXECUTING', started_at=COALESCE(started_at,CURRENT_TIMESTAMP), message='正在执行 SQL 文件。' WHERE id=? AND status='QUEUED'", id);
    }

    public void updateExecutionProgress(long id, long current, long success, long queryRows, String message) {
        jdbc.update("UPDATE sql_file_execution SET statement_current=?, success_count=?, query_row_count=?, message=? WHERE id=?", current, success, queryRows, message, id);
    }

    public void markTerminal(long id, String status, String phase, long current, long success, long queryRows,
                             Long failedIndex, String failedSql, String message) {
        jdbc.update("""
                UPDATE sql_file_execution SET status=?, phase=?, statement_current=?, success_count=?, query_row_count=?,
                  failed_statement_index=?, failed_sql_preview=?, message=?, finished_at=CURRENT_TIMESTAMP WHERE id=?
                """ + " AND status IN ('ANALYZING','READY','QUEUED','RUNNING')",
                status, phase, current, success, queryRows, failedIndex, failedSql, message, id);
    }

    public void requestCancel(long id) {
        jdbc.update("UPDATE sql_file_execution SET cancel_requested=TRUE WHERE id=? AND status IN ('ANALYZING','READY','QUEUED','RUNNING')", id);
    }

    public boolean isCancelRequested(long id) {
        Boolean value = jdbc.queryForObject("SELECT cancel_requested FROM sql_file_execution WHERE id=?", Boolean.class, id);
        return Boolean.TRUE.equals(value);
    }

    public void failStaleRunning() {
        jdbc.update("""
                UPDATE sql_file_execution SET status='FAILED', phase='INTERRUPTED',
                  message='服务重启，上一轮 SQL 文件任务已中断。', finished_at=CURRENT_TIMESTAMP
                WHERE status IN ('ANALYZING','QUEUED','RUNNING')
                """);
    }

    public List<SqlFileExecution> findExpiredReady(Instant now) {
        return jdbc.query("SELECT * FROM sql_file_execution WHERE status='READY' AND expires_at < ?", mapper, Timestamp.from(now));
    }

    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
}
