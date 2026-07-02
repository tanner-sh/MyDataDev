package com.example.dbadmin.service;

import com.example.dbadmin.dto.ApiDtos.SqlResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class ExportService {
    private static final int EXPORT_MAX_ROWS = 10_000;

    private final SqlService sqlService;
    private final ObjectMapper mapper;

    public ExportService(SqlService sqlService, ObjectMapper mapper) {
        this.sqlService = sqlService;
        this.mapper = mapper;
    }

    public String export(long connectionId, String sql, String format, String actor) throws Exception {
        SqlResult result = sqlService.execute(connectionId, sql, EXPORT_MAX_ROWS, actor);
        if (!result.resultSet()) {
            throw new IllegalArgumentException("只有查询结果可以导出。");
        }
        if ("json".equalsIgnoreCase(format)) {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result.rows());
        }
        if ("csv".equalsIgnoreCase(format)) {
            return csv(result);
        }
        if ("sql".equalsIgnoreCase(format)) {
            return sql(result);
        }
        if ("xml".equalsIgnoreCase(format)) {
            return xml(result);
        }
        throw new IllegalArgumentException("不支持的导出格式：" + format);
    }

    private String csv(SqlResult result) {
        String header = result.columns().stream().map(this::csvValue).collect(Collectors.joining(","));
        String rows = result.rows().stream()
                .map(row -> result.columns().stream().map(c -> csvValue(row.get(c))).collect(Collectors.joining(",")))
                .collect(Collectors.joining("\n"));
        return rows.isBlank() ? header : header + "\n" + rows;
    }

    private String csvValue(Object value) {
        String s = value == null ? "" : value.toString();
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    private String sql(SqlResult result) {
        if (result.rows().isEmpty()) {
            return "-- 查询结果为空，未生成 INSERT 语句。";
        }
        String columns = result.columns().stream().map(this::sqlIdentifier).collect(Collectors.joining(", "));
        return result.rows().stream()
                .map(row -> "INSERT INTO query_result (" + columns + ") VALUES (" + sqlValues(result.columns(), row) + ");")
                .collect(Collectors.joining("\n"));
    }

    private String sqlValues(List<String> columns, Map<String, Object> row) {
        return columns.stream().map(column -> sqlLiteral(row.get(column))).collect(Collectors.joining(", "));
    }

    private String sqlIdentifier(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private String sqlLiteral(Object value) {
        if (value == null) {
            return "NULL";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        return "'" + value.toString().replace("'", "''") + "'";
    }

    private String xml(SqlResult result) {
        StringBuilder body = new StringBuilder();
        body.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<rows>");
        for (Map<String, Object> row : result.rows()) {
            body.append("\n  <row>");
            for (String column : result.columns()) {
                String tag = xmlName(column);
                body.append("\n    <").append(tag).append(">")
                        .append(xmlValue(row.get(column)))
                        .append("</").append(tag).append(">");
            }
            body.append("\n  </row>");
        }
        body.append("\n</rows>");
        return body.toString();
    }

    private String xmlName(String value) {
        String normalized = value == null || value.isBlank() ? "field" : value.replaceAll("[^A-Za-z0-9_.-]", "_");
        if (!normalized.substring(0, 1).matches("[A-Za-z_]")) {
            return "_" + normalized;
        }
        return normalized;
    }

    private String xmlValue(Object value) {
        if (value == null) {
            return "";
        }
        return value.toString()
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
