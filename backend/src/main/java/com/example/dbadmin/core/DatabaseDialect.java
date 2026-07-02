package com.example.dbadmin.core;

import com.example.dbadmin.dto.ApiDtos.SqlResult;

import java.sql.Connection;

public interface DatabaseDialect {
    boolean supports(String dbType, String jdbcUrl);

    String pageQuery(String baseSql, int limit, int offset);

    SqlResult explain(Connection connection, String sql, int maxRows, int timeoutSeconds) throws Exception;

    default String quoteIdentifier(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }
}
