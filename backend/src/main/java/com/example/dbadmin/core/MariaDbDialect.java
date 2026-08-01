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
}
