package com.example.dbadmin.core;

import com.example.dbadmin.dto.ApiDtos.DatabaseCapabilities;

import java.util.List;
import java.util.Locale;

public class MariaDbDialect extends MySqlDialect {
    @Override
    public boolean supports(String dbType, String jdbcUrl) {
        String type = dbType == null ? "" : dbType.toLowerCase(Locale.ROOT);
        String url = jdbcUrl == null ? "" : jdbcUrl.toLowerCase(Locale.ROOT);
        return type.equals("mariadb") || url.startsWith("jdbc:mariadb:");
    }

    @Override
    public DatabaseCapabilities capabilities() {
        return new DatabaseCapabilities(true, true, true, true, List.of("MYSQLDUMP"), List.of("MYSQL"), SchemaObjectCapabilities.mariaDb());
    }

    @Override
    public String activeSessionsSql() {
        return """
                SELECT ID AS session_id, USER AS session_user, HOST AS session_host, DB AS session_database,
                       STATE AS session_state, COMMAND AS session_command, TIME AS duration_seconds, INFO AS session_sql
                FROM information_schema.PROCESSLIST
                -- 与 MySqlDialect 同理：排除工具自己这条连接。
                WHERE ID <> CONNECTION_ID()
                ORDER BY TIME DESC
                """;
    }

    /**
     * MariaDB 的锁等待。
     *
     * <p>不能沿用 MySQL 那条：MariaDB 没有 {@code sys.innodb_lock_waits}，对应的信息在
     * {@code information_schema.INNODB_LOCK_WAITS} 里。这张表在 MariaDB 10.6 起被移除且没有
     * 版本无关的替代品，那些版本上这条语句会失败 —— 由会话服务给出一句「版本或权限」的说明，
     * 好过在这里返回 null 让界面说「不支持」，因为 10.5 及以前它是能用的。</p>
     */
    @Override
    public String blockingSessionsSql() {
        return """
                SELECT r.trx_mysql_thread_id AS blocked_session_id,
                       b.trx_mysql_thread_id AS blocking_session_id,
                       r.trx_requested_lock_id AS wait_object,
                       TIMESTAMPDIFF(SECOND, r.trx_wait_started, NOW()) AS wait_seconds,
                       r.trx_query AS blocked_sql
                FROM information_schema.INNODB_LOCK_WAITS w
                JOIN information_schema.INNODB_TRX r ON r.trx_id = w.requesting_trx_id
                JOIN information_schema.INNODB_TRX b ON b.trx_id = w.blocking_trx_id
                """;
    }

    @Override
    public boolean supportsKillSession() {
        return true;
    }

    @Override
    public String killSessionSql(String sessionId) {
        return "KILL " + Long.parseLong(sessionId);
    }
}
