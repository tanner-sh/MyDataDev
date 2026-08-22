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
                ORDER BY TIME DESC
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
