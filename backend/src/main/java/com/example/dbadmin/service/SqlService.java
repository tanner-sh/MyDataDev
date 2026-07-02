package com.example.dbadmin.service;

import com.example.dbadmin.config.AppProperties;
import com.example.dbadmin.core.DialectRegistry;
import com.example.dbadmin.dto.ApiDtos.SqlResult;
import com.example.dbadmin.model.DbConnection;
import com.example.dbadmin.repo.AuditRepository;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class SqlService {
    private final ConnectionService connections;
    private final AppProperties properties;
    private final AuditRepository audit;
    private final DialectRegistry dialectRegistry;

    public SqlService(ConnectionService connections, AppProperties properties, AuditRepository audit, DialectRegistry dialectRegistry) {
        this.connections = connections;
        this.properties = properties;
        this.audit = audit;
        this.dialectRegistry = dialectRegistry;
    }

    public SqlResult execute(long connectionId, String sql, Integer requestedMaxRows, String actor) throws Exception {
        int maxRows = Math.min(requestedMaxRows == null ? properties.getSql().getMaxRows() : requestedMaxRows, properties.getSql().getMaxRows());
        long started = System.nanoTime();
        try (Connection c = connections.open(connectionId); Statement st = c.createStatement()) {
            st.setQueryTimeout(properties.getSql().getTimeoutSeconds());
            st.setMaxRows(maxRows);
            boolean hasResult = st.execute(sql);
            long elapsedMs = (System.nanoTime() - started) / 1_000_000;
            audit.log(actor, "SQL_EXECUTE", "connection:" + connectionId, abbreviate(sql));
            if (!hasResult) {
                return new SqlResult(List.of(), List.of(), st.getUpdateCount(), elapsedMs, false);
            }
            try (ResultSet rs = st.getResultSet()) {
                return readResult(rs, elapsedMs);
            }
        }
    }

    public SqlResult explain(long connectionId, String sql, String actor) throws Exception {
        DbConnection dbConnection = connections.require(connectionId);
        try (Connection connection = connections.open(connectionId)) {
            SqlResult result = dialectRegistry.dialectFor(dbConnection)
                    .explain(connection, sql, properties.getSql().getMaxRows(), properties.getSql().getTimeoutSeconds());
            audit.log(actor, "SQL_EXPLAIN", "connection:" + connectionId, abbreviate(sql));
            return result;
        }
    }

    public String format(String sql) {
        String formatted = sql.trim().replaceAll("\\s+", " ");
        String[] keywords = {"select", "from", "where", "join", "left join", "right join", "inner join", "group by", "order by", "having", "limit", "insert", "update", "delete"};
        for (String keyword : keywords) {
            formatted = formatted.replaceAll("(?i)\\b" + keyword.replace(" ", "\\s+") + "\\b", "\n" + keyword.toUpperCase(Locale.ROOT));
        }
        return formatted.trim();
    }

    private SqlResult readResult(ResultSet rs, long elapsedMs) throws Exception {
        ResultSetMetaData md = rs.getMetaData();
        List<String> columns = new ArrayList<>();
        for (int i = 1; i <= md.getColumnCount(); i++) {
            columns.add(md.getColumnLabel(i));
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        while (rs.next()) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 1; i <= md.getColumnCount(); i++) {
                row.put(columns.get(i - 1), serializableValue(rs.getObject(i)));
            }
            rows.add(row);
        }
        return new SqlResult(columns, rows, -1, elapsedMs, true);
    }

    private Object serializableValue(Object value) throws Exception {
        if (value instanceof Clob clob) {
            long length = clob.length();
            return clob.getSubString(1, (int) Math.min(length, 10_000));
        }
        if (value instanceof Blob blob) {
            return "<BLOB " + blob.length() + " bytes>";
        }
        return value;
    }

    private String abbreviate(String sql) {
        return sql.length() <= 2000 ? sql : sql.substring(0, 2000);
    }
}
