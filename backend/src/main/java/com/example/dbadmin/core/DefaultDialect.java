package com.example.dbadmin.core;

import com.example.dbadmin.dto.ApiDtos.SqlResult;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class DefaultDialect implements DatabaseDialect {
    @Override
    public boolean supports(String dbType, String jdbcUrl) {
        return true;
    }

    @Override
    public String pageQuery(String baseSql, int limit, int offset) {
        return baseSql + " LIMIT " + limit + " OFFSET " + offset;
    }

    @Override
    public SqlResult explain(Connection connection, String sql, int maxRows, int timeoutSeconds) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(timeoutSeconds);
            statement.setMaxRows(maxRows);
            try (ResultSet rs = statement.executeQuery("EXPLAIN " + sql)) {
                return readResult(rs, 0);
            }
        }
    }

    protected SqlResult readResult(ResultSet rs, long elapsedMs) throws Exception {
        ResultSetMetaData md = rs.getMetaData();
        List<String> columns = new ArrayList<>();
        for (int i = 1; i <= md.getColumnCount(); i++) {
            columns.add(md.getColumnLabel(i));
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        while (rs.next()) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 1; i <= md.getColumnCount(); i++) {
                row.put(columns.get(i - 1), rs.getObject(i));
            }
            rows.add(row);
        }
        return new SqlResult(columns, rows, -1, elapsedMs, true);
    }
}
