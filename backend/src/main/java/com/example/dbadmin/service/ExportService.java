package com.example.dbadmin.service;

import com.example.dbadmin.dto.ApiDtos.SqlResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.stream.Collectors;

@Service
public class ExportService {
    private final SqlService sqlService;
    private final ObjectMapper mapper;

    public ExportService(SqlService sqlService, ObjectMapper mapper) {
        this.sqlService = sqlService;
        this.mapper = mapper;
    }

    public String export(long connectionId, String sql, String format, String actor) throws Exception {
        SqlResult result = sqlService.execute(connectionId, sql, 1000, actor);
        if ("json".equalsIgnoreCase(format)) {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result.rows());
        }
        if ("csv".equalsIgnoreCase(format)) {
            String header = result.columns().stream().map(this::csv).collect(Collectors.joining(","));
            String rows = result.rows().stream()
                    .map(row -> result.columns().stream().map(c -> csv(row.get(c))).collect(Collectors.joining(",")))
                    .collect(Collectors.joining("\n"));
            return rows.isBlank() ? header : header + "\n" + rows;
        }
        throw new IllegalArgumentException("Unsupported export format: " + format);
    }

    private String csv(Object value) {
        String s = value == null ? "" : value.toString();
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }
}
