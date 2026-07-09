package com.example.dbadmin.service;

import com.example.dbadmin.config.AppProperties;
import com.example.dbadmin.core.DialectRegistry;
import com.example.dbadmin.dto.ApiDtos.DbObject;
import com.example.dbadmin.dto.ApiDtos.MetadataResponse;
import com.example.dbadmin.dto.ApiDtos.SqlCompletionItem;
import com.example.dbadmin.dto.ApiDtos.SqlCompletionRequest;
import com.example.dbadmin.dto.ApiDtos.SqlHistoryResponse;
import com.example.dbadmin.dto.ApiDtos.SqlResult;
import com.example.dbadmin.dto.ApiDtos.SqlScriptResponse;
import com.example.dbadmin.dto.ApiDtos.SqlStatementResult;
import com.example.dbadmin.model.DbConnection;
import com.example.dbadmin.repo.AuditRepository;
import com.example.dbadmin.repo.SqlHistoryRepository;
import com.example.dbadmin.service.SqlScriptSplitter.StatementSegment;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class SqlService {
    private final ConnectionService connections;
    private final AppProperties properties;
    private final AuditRepository audit;
    private final DialectRegistry dialectRegistry;
    private final SqlHistoryRepository history;
    private final MetadataService metadata;
    private final SqlScriptSplitter scriptSplitter;

    public SqlService(ConnectionService connections, AppProperties properties, AuditRepository audit, DialectRegistry dialectRegistry, SqlHistoryRepository history, MetadataService metadata, SqlScriptSplitter scriptSplitter) {
        this.connections = connections;
        this.properties = properties;
        this.audit = audit;
        this.dialectRegistry = dialectRegistry;
        this.history = history;
        this.metadata = metadata;
        this.scriptSplitter = scriptSplitter;
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
                SqlResult result = new SqlResult(List.of(), List.of(), st.getUpdateCount(), elapsedMs, false);
                history.insert(connectionId, sql, "EXECUTE", "SUCCESS", elapsedMs, null, actor);
                return result;
            }
            try (ResultSet rs = st.getResultSet()) {
                SqlResult result = readResult(rs, elapsedMs);
                history.insert(connectionId, sql, "EXECUTE", "SUCCESS", elapsedMs, null, actor);
                return result;
            }
        } catch (Exception e) {
            long elapsedMs = (System.nanoTime() - started) / 1_000_000;
            history.insert(connectionId, sql, "EXECUTE", "FAILED", elapsedMs, abbreviate(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()), actor);
            throw e;
        }
    }

    public SqlScriptResponse executeScript(long connectionId, String sql, Integer requestedMaxRows, String actor) throws Exception {
        List<StatementSegment> statements = scriptSplitter.split(sql);
        if (statements.isEmpty()) {
            throw new IllegalArgumentException("请输入要执行的 SQL");
        }
        if (statements.size() > 50) {
            throw new IllegalArgumentException("一次最多执行 50 条 SQL");
        }

        int maxRows = Math.min(requestedMaxRows == null ? properties.getSql().getMaxRows() : requestedMaxRows, properties.getSql().getMaxRows());
        long scriptStarted = System.nanoTime();
        List<SqlStatementResult> results = new ArrayList<>();
        String status = "SUCCESS";
        String errorMessage = null;

        try (Connection c = connections.open(connectionId); Statement st = c.createStatement()) {
            st.setQueryTimeout(properties.getSql().getTimeoutSeconds());
            st.setMaxRows(maxRows);
            for (int i = 0; i < statements.size(); i++) {
                StatementSegment statement = statements.get(i);
                long statementStarted = System.nanoTime();
                try {
                    boolean hasResult = st.execute(statement.sql());
                    long elapsedMs = (System.nanoTime() - statementStarted) / 1_000_000;
                    SqlResult result;
                    if (hasResult) {
                        try (ResultSet rs = st.getResultSet()) {
                            result = readResult(rs, elapsedMs);
                        }
                    } else {
                        result = new SqlResult(List.of(), List.of(), st.getUpdateCount(), elapsedMs, false);
                    }
                    results.add(new SqlStatementResult(i + 1, statement.sql(), statement.startOffset(), statement.endOffset(), "SUCCESS", null, result));
                } catch (Exception e) {
                    long elapsedMs = (System.nanoTime() - statementStarted) / 1_000_000;
                    errorMessage = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                    SqlResult result = new SqlResult(List.of(), List.of(), -1, elapsedMs, false);
                    results.add(new SqlStatementResult(i + 1, statement.sql(), statement.startOffset(), statement.endOffset(), "FAILED", abbreviate(errorMessage), result));
                    status = "FAILED";
                    break;
                }
            }
            long elapsedMs = (System.nanoTime() - scriptStarted) / 1_000_000;
            audit.log(actor, "SQL_EXECUTE_SCRIPT", "connection:" + connectionId, abbreviate(sql));
            history.insert(connectionId, sql, "EXECUTE_SCRIPT", status, elapsedMs, errorMessage == null ? null : abbreviate(errorMessage), actor);
            return new SqlScriptResponse(status, elapsedMs, results.size(), results);
        } catch (Exception e) {
            long elapsedMs = (System.nanoTime() - scriptStarted) / 1_000_000;
            history.insert(connectionId, sql, "EXECUTE_SCRIPT", "FAILED", elapsedMs, abbreviate(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()), actor);
            throw e;
        }
    }

    public SqlResult explain(long connectionId, String sql, String actor) throws Exception {
        long started = System.nanoTime();
        DbConnection dbConnection = connections.require(connectionId);
        try (Connection connection = connections.open(connectionId)) {
            SqlResult result = dialectRegistry.dialectFor(dbConnection)
                    .explain(connection, sql, properties.getSql().getMaxRows(), properties.getSql().getTimeoutSeconds());
            audit.log(actor, "SQL_EXPLAIN", "connection:" + connectionId, abbreviate(sql));
            history.insert(connectionId, sql, "EXPLAIN", "SUCCESS", (System.nanoTime() - started) / 1_000_000, null, actor);
            return result;
        } catch (Exception e) {
            history.insert(connectionId, sql, "EXPLAIN", "FAILED", (System.nanoTime() - started) / 1_000_000, abbreviate(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()), actor);
            throw e;
        }
    }

    public List<SqlHistoryResponse> history(long connectionId, Integer limit) {
        return history.findRecent(connectionId, limit == null ? 50 : limit);
    }

    public List<SqlCompletionItem> completions(SqlCompletionRequest request) {
        List<SqlCompletionItem> items = new ArrayList<>();
        for (String keyword : sqlKeywords()) {
            items.add(new SqlCompletionItem(keyword, "KEYWORD", keyword, "SQL 关键字"));
        }
        try {
            MetadataResponse response = metadata.inspect(request.connectionId(), null, null, 0, 200);
            Set<String> schemas = new LinkedHashSet<>(response.schemas());
            for (String schema : schemas) {
                items.add(new SqlCompletionItem(schema, "SCHEMA", schema, "数据库 Schema"));
            }
            for (DbObject object : response.objects()) {
                String tableLabel = object.schemaName() == null || object.schemaName().isBlank()
                        ? object.name()
                        : object.schemaName() + "." + object.name();
                items.add(new SqlCompletionItem(tableLabel, "TABLE", tableLabel, "数据库" + objectTypeLabel(object.type())));
            }
        } catch (Exception ignored) {
            // Connection metadata may be unavailable while typing; keyword completion should still work.
        }
        return items.stream().limit(300).toList();
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
        if (sql == null) {
            return "";
        }
        return sql.length() <= 2000 ? sql : sql.substring(0, 2000);
    }

    private List<String> sqlKeywords() {
        return List.of(
                "SELECT", "FROM", "WHERE", "JOIN", "LEFT JOIN", "RIGHT JOIN", "INNER JOIN",
                "GROUP BY", "ORDER BY", "HAVING", "LIMIT", "OFFSET", "INSERT", "INTO",
                "VALUES", "UPDATE", "SET", "DELETE", "CREATE", "ALTER", "DROP", "TABLE",
                "VIEW", "INDEX", "PRIMARY KEY", "AND", "OR", "NOT", "NULL", "IS", "IN",
                "BETWEEN", "LIKE", "COUNT", "SUM", "AVG", "MIN", "MAX"
        );
    }

    private String objectTypeLabel(String type) {
        String normalized = type == null ? "" : type.toUpperCase(Locale.ROOT);
        if (normalized.contains("VIEW")) {
            return "视图";
        }
        return "表";
    }
}
