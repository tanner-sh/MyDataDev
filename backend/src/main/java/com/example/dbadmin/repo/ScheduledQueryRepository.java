package com.example.dbadmin.repo;

import com.example.dbadmin.model.ScheduledQuery;
import org.springframework.jdbc.core.JdbcTemplate;
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
public class ScheduledQueryRepository {
    private final JdbcTemplate jdbc;

    public ScheduledQueryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<ScheduledQuery> findAll() {
        return jdbc.query("SELECT * FROM scheduled_query ORDER BY id", (rs, ignored) -> map(rs));
    }

    public List<ScheduledQuery> findByConnectionId(long connectionId) {
        return jdbc.query("SELECT * FROM scheduled_query WHERE connection_id = ? ORDER BY id",
                (rs, ignored) -> map(rs), connectionId);
    }

    public Optional<ScheduledQuery> findById(long id) {
        return jdbc.query("SELECT * FROM scheduled_query WHERE id = ?", (rs, ignored) -> map(rs), id)
                .stream().findFirst();
    }

    public long insert(ScheduledQuery task) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO scheduled_query(connection_id, name, sql_text, export_format, cron, schedule_zone,
                                                enabled, production_confirmed)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, task.connectionId());
            statement.setString(2, task.name());
            statement.setString(3, task.sql());
            statement.setString(4, task.exportFormat());
            statement.setString(5, task.cron());
            statement.setString(6, task.scheduleZone());
            statement.setBoolean(7, task.enabled());
            statement.setBoolean(8, task.productionConfirmed());
            return statement;
        }, keys);
        Number key = keys.getKey();
        if (key == null) throw new IllegalStateException("无法获取定时导出任务的自增主键");
        return key.longValue();
    }

    public void update(ScheduledQuery task) {
        jdbc.update("""
                UPDATE scheduled_query SET name = ?, sql_text = ?, export_format = ?, cron = ?, schedule_zone = ?,
                       enabled = ?, production_confirmed = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, task.name(), task.sql(), task.exportFormat(), task.cron(), task.scheduleZone(),
                task.enabled(), task.productionConfirmed(), task.id());
    }

    public void updateEnabled(long id, boolean enabled) {
        jdbc.update("UPDATE scheduled_query SET enabled = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?", enabled, id);
    }

    /** 记一次运行结果。文件名留着，界面上才说得出「上次导到哪儿了」。 */
    public void recordRun(long id, Instant runAt, String status, String message, String file) {
        jdbc.update("""
                UPDATE scheduled_query SET last_run_at = ?, last_status = ?, last_message = ?, last_file = ?,
                       updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, Timestamp.from(runAt), status, message == null ? null : abbreviate(message), file, id);
    }

    public void delete(long id) {
        jdbc.update("DELETE FROM scheduled_query WHERE id = ?", id);
    }

    private static String abbreviate(String value) {
        return value.length() <= 990 ? value : value.substring(0, 990) + "…";
    }

    private static ScheduledQuery map(java.sql.ResultSet rs) throws java.sql.SQLException {
        Timestamp lastRun = rs.getTimestamp("last_run_at");
        Timestamp created = rs.getTimestamp("created_at");
        Timestamp updated = rs.getTimestamp("updated_at");
        return new ScheduledQuery(
                rs.getLong("id"),
                rs.getLong("connection_id"),
                rs.getString("name"),
                rs.getString("sql_text"),
                rs.getString("export_format"),
                rs.getString("cron"),
                rs.getString("schedule_zone"),
                rs.getBoolean("enabled"),
                rs.getBoolean("production_confirmed"),
                lastRun == null ? null : lastRun.toInstant(),
                rs.getString("last_status"),
                rs.getString("last_message"),
                rs.getString("last_file"),
                created == null ? null : created.toInstant(),
                updated == null ? null : updated.toInstant()
        );
    }
}
