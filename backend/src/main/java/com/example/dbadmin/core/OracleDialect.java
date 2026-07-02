package com.example.dbadmin.core;

import com.example.dbadmin.dto.ApiDtos.SqlResult;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

public class OracleDialect extends DefaultDialect {
    @Override
    public boolean supports(String dbType, String jdbcUrl) {
        return "oracle".equalsIgnoreCase(dbType)
                || (jdbcUrl != null && jdbcUrl.toLowerCase().startsWith("jdbc:oracle:"));
    }

    @Override
    public String pageQuery(String baseSql, int limit, int offset) {
        int upperBound = offset + limit;
        return "SELECT * FROM (SELECT inner_query.*, ROWNUM dbadmin_rn FROM (" + baseSql
                + ") inner_query WHERE ROWNUM <= " + upperBound + ") WHERE dbadmin_rn > " + offset;
    }

    @Override
    public SqlResult explain(Connection connection, String sql, int maxRows, int timeoutSeconds) throws Exception {
        long started = System.nanoTime();
        try (Statement explain = connection.createStatement()) {
            explain.setQueryTimeout(timeoutSeconds);
            explain.execute("EXPLAIN PLAN FOR " + sql);
        }
        try (Statement display = connection.createStatement()) {
            display.setQueryTimeout(timeoutSeconds);
            display.setMaxRows(maxRows);
            try (ResultSet rs = display.executeQuery("SELECT PLAN_TABLE_OUTPUT FROM TABLE(DBMS_XPLAN.DISPLAY())")) {
                return readResult(rs, (System.nanoTime() - started) / 1_000_000);
            }
        }
    }
}
