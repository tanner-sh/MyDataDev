package com.example.dbadmin.service;

import com.example.dbadmin.api.ApiProblemException;
import com.example.dbadmin.core.DatabaseDialect;
import com.example.dbadmin.core.DialectRegistry;
import com.example.dbadmin.dto.ApiDtos.DatabaseSession;
import com.example.dbadmin.dto.ApiDtos.DatabaseSessionPage;
import com.example.dbadmin.model.DbConnection;
import com.example.dbadmin.repo.AuditRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 目标库上的活动会话。
 *
 * <p>此前只能看到本工具自己发起的后台任务与前台 SQL，看不到目标库上到底在跑什么 —— 排查
 * 「谁把表锁住了」只能切到数据库自带的客户端。各方言的来源不同（MySQL 的 PROCESSLIST、
 * PostgreSQL 的 pg_stat_activity、Oracle 的 V$SESSION），因此语句收在 DatabaseDialect 里，
 * 这里只负责统一读取、限额与终止时的审计。</p>
 */
@Service
public class DatabaseSessionService {
    private static final int MAX_SESSIONS = 500;
    private static final int MAX_SQL_CHARS = 4_000;
    private static final int QUERY_TIMEOUT_SECONDS = 15;

    private final ConnectionService connections;
    private final DialectRegistry dialectRegistry;
    private final ExecutionGuard executionGuard;
    private final AuditRepository audit;

    public DatabaseSessionService(
            ConnectionService connections,
            DialectRegistry dialectRegistry,
            ExecutionGuard executionGuard,
            AuditRepository audit
    ) {
        this.connections = connections;
        this.dialectRegistry = dialectRegistry;
        this.executionGuard = executionGuard;
        this.audit = audit;
    }

    public DatabaseSessionPage list(long connectionId) throws Exception {
        DbConnection dbConnection = connections.require(connectionId);
        DatabaseDialect dialect = dialectRegistry.dialectFor(dbConnection);
        String sql = dialect.activeSessionsSql();
        if (sql == null) {
            return new DatabaseSessionPage(false, false, List.of(), "当前数据库类型暂不支持查看活动会话。");
        }
        boolean canKill = dialect.supportsKillSession();
        List<DatabaseSession> sessions = new ArrayList<>();
        try (Connection connection = connections.open(connectionId);
             ReadOnlyQueryScope ignored = ReadOnlyQueryScope.begin(connection, true);
             Statement statement = connection.createStatement()) {
            dialect.configureReadStatement(connection, statement, 200, QUERY_TIMEOUT_SECONDS);
            statement.setMaxRows(MAX_SESSIONS);
            try (ResultSet rs = statement.executeQuery(sql)) {
                Set<String> columns = columnLabels(rs.getMetaData());
                while (rs.next() && sessions.size() < MAX_SESSIONS) {
                    sessions.add(new DatabaseSession(
                            text(rs, columns, "session_id"),
                            text(rs, columns, "session_user"),
                            text(rs, columns, "session_host"),
                            text(rs, columns, "session_database"),
                            text(rs, columns, "session_state"),
                            text(rs, columns, "session_command"),
                            number(rs, columns, "duration_seconds"),
                            abbreviate(text(rs, columns, "session_sql"))
                    ));
                }
            }
        } catch (Exception error) {
            // 权限不足读不到系统视图是很常见的，说明原因比抛一个 SQL 错误有用。
            return new DatabaseSessionPage(true, canKill, List.of(),
                    "读取活动会话失败（通常是账号缺少查看系统视图的权限）：" + error.getMessage());
        }
        return new DatabaseSessionPage(true, canKill, List.copyOf(sessions), null);
    }

    public void kill(long connectionId, String sessionId, String actor, String productionConfirmation) throws Exception {
        DbConnection dbConnection = connections.require(connectionId);
        // 终止别人的会话是破坏性操作，按写操作要求确认。
        executionGuard.requireMutationAllowed(dbConnection, productionConfirmation);
        DatabaseDialect dialect = dialectRegistry.dialectFor(dbConnection);
        if (!dialect.supportsKillSession()) {
            throw new ApiProblemException(
                    HttpStatus.BAD_REQUEST, "SESSION_KILL_UNSUPPORTED", "当前数据库类型暂不支持终止会话。"
            );
        }
        String sql;
        try {
            sql = dialect.killSessionSql(sessionId);
        } catch (RuntimeException error) {
            // 方言按自己的格式校验会话标识（Oracle 要 SID,SERIAL#，其余要纯数字）。
            throw new IllegalArgumentException("会话标识无效：" + sessionId);
        }
        try (Connection connection = connections.open(connectionId);
             Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            boolean hasResult = statement.execute(sql);
            if (hasResult) requireTerminated(statement, sessionId);
        }
        audit.onConnection(actor, "SESSION_KILL", connectionId, "session:" + sessionId);
    }

    /**
     * 有的数据库用返回值而不是异常表示「没杀成」。
     *
     * <p>PostgreSQL 的 {@code pg_terminate_backend()} 在会话号不存在时只发一条 WARNING 并返回
     * false —— 不看这个布尔值的话，一次什么都没做的调用会被当成成功记进审计日志，用户也以为
     * 那个会话已经被终止了。MySQL 的 KILL 与 Oracle 的 ALTER SYSTEM 走的是抛异常的路子，不进
     * 这个分支。</p>
     */
    private void requireTerminated(Statement statement, String sessionId) throws Exception {
        try (ResultSet rs = statement.getResultSet()) {
            if (rs == null || !rs.next()) return;
            Object value = rs.getObject(1);
            boolean terminated;
            if (value == null) terminated = false;
            else if (value instanceof Boolean bool) terminated = bool;
            else if (value instanceof Number number) terminated = number.intValue() != 0;
            else terminated = !Set.of("f", "false", "0", "n", "no").contains(String.valueOf(value).toLowerCase(Locale.ROOT));
            if (!terminated) {
                throw new ApiProblemException(
                        HttpStatus.CONFLICT, "SESSION_KILL_FAILED",
                        "数据库未能终止会话 " + sessionId + "，它可能已经结束或不属于当前实例。"
                );
            }
        }
    }

    private static Set<String> columnLabels(ResultSetMetaData metadata) throws Exception {
        Set<String> labels = new LinkedHashSet<>();
        for (int index = 1; index <= metadata.getColumnCount(); index++) {
            labels.add(metadata.getColumnLabel(index).toLowerCase(Locale.ROOT));
        }
        return labels;
    }

    private static String text(ResultSet rs, Set<String> columns, String label) throws Exception {
        if (!columns.contains(label)) return null;
        Object value = rs.getObject(label);
        return value == null ? null : String.valueOf(value);
    }

    private static Long number(ResultSet rs, Set<String> columns, String label) throws Exception {
        if (!columns.contains(label)) return null;
        Object value = rs.getObject(label);
        if (value == null) return null;
        if (value instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String abbreviate(String value) {
        if (value == null) return null;
        return value.length() <= MAX_SQL_CHARS ? value : value.substring(0, MAX_SQL_CHARS);
    }
}
