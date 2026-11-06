package com.example.dbadmin.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Types;
import java.util.List;
import java.util.Optional;

/**
 * 命令窗口任务的设置与逐条日志（见 V28）。任务本身仍在 sql_file_execution 里。
 */
@Repository
public class SqlCommandRepository {
    public record CommandJob(long jobId, String onError, long failedCount, Long firstFailedIndex, long logCount, boolean logTruncated) { }

    public record LogEntry(long seq, String kind, Long statementIndex, Integer startLine, Integer endLine, Long elapsedMs, String text) { }

    private static final RowMapper<CommandJob> JOB = (rs, ignored) -> new CommandJob(
            rs.getLong("job_id"), rs.getString("on_error"), rs.getLong("failed_count"),
            rs.getObject("first_failed_index", Long.class), rs.getLong("log_count"), rs.getBoolean("log_truncated"));
    private static final RowMapper<LogEntry> LOG = (rs, ignored) -> new LogEntry(
            rs.getLong("seq"), rs.getString("kind"), rs.getObject("statement_index", Long.class),
            rs.getObject("start_line", Integer.class), rs.getObject("end_line", Integer.class),
            rs.getObject("elapsed_ms", Long.class), rs.getString("text"));

    private final JdbcTemplate jdbc;

    public SqlCommandRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(long jobId, String onError) {
        jdbc.update("INSERT INTO sql_command_job(job_id, on_error) VALUES (?, ?)", jobId, onError);
    }

    public Optional<CommandJob> find(long jobId) {
        return jdbc.query("SELECT * FROM sql_command_job WHERE job_id = ?", JOB, jobId).stream().findFirst();
    }

    public boolean isCommand(long jobId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM sql_command_job WHERE job_id = ?", Integer.class, jobId);
        return count != null && count > 0;
    }

    public void recordFailure(long jobId, long statementIndex) {
        jdbc.update("""
                UPDATE sql_command_job SET failed_count = failed_count + 1,
                  first_failed_index = COALESCE(first_failed_index, ?) WHERE job_id = ?""", statementIndex, jobId);
    }

    public void append(long jobId, List<LogEntry> entries, boolean truncated) {
        if (entries.isEmpty() && !truncated) return;
        if (!entries.isEmpty()) {
            jdbc.batchUpdate("""
                    INSERT INTO sql_command_log(job_id, seq, kind, statement_index, start_line, end_line, elapsed_ms, text)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)""", entries, entries.size(), (ps, entry) -> {
                ps.setLong(1, jobId);
                ps.setLong(2, entry.seq());
                ps.setString(3, entry.kind());
                if (entry.statementIndex() == null) ps.setNull(4, Types.BIGINT); else ps.setLong(4, entry.statementIndex());
                if (entry.startLine() == null) ps.setNull(5, Types.INTEGER); else ps.setInt(5, entry.startLine());
                if (entry.endLine() == null) ps.setNull(6, Types.INTEGER); else ps.setInt(6, entry.endLine());
                if (entry.elapsedMs() == null) ps.setNull(7, Types.BIGINT); else ps.setLong(7, entry.elapsedMs());
                ps.setString(8, entry.text());
            });
        }
        jdbc.update("UPDATE sql_command_job SET log_count = log_count + ?, log_truncated = log_truncated OR ? WHERE job_id = ?",
                entries.size(), truncated, jobId);
    }

    public List<LogEntry> logs(long jobId, long afterSeq, int limit) {
        return jdbc.query("SELECT * FROM sql_command_log WHERE job_id = ? AND seq > ? ORDER BY seq LIMIT ?", LOG, jobId, afterSeq, limit);
    }
}
