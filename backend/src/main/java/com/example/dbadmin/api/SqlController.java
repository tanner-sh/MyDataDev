package com.example.dbadmin.api;

import com.example.dbadmin.dto.ApiDtos.ExportRequest;
import com.example.dbadmin.dto.ApiDtos.FormatRequest;
import com.example.dbadmin.dto.ApiDtos.FormatResponse;
import com.example.dbadmin.dto.ApiDtos.SqlCompletionItem;
import com.example.dbadmin.dto.ApiDtos.SqlCompletionRequest;
import com.example.dbadmin.dto.ApiDtos.SqlHistoryResponse;
import com.example.dbadmin.dto.ApiDtos.SqlRequest;
import com.example.dbadmin.dto.ApiDtos.SqlResult;
import com.example.dbadmin.service.ExportService;
import com.example.dbadmin.service.SqlService;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/sql")
public class SqlController {
    private final SqlService sqlService;
    private final ExportService exportService;

    public SqlController(SqlService sqlService, ExportService exportService) {
        this.sqlService = sqlService;
        this.exportService = exportService;
    }

    @PostMapping("/execute")
    public SqlResult execute(@Valid @RequestBody SqlRequest request, @RequestHeader(value = "X-User", required = false) String actor) throws Exception {
        return sqlService.execute(request.connectionId(), request.sql(), request.maxRows(), actor);
    }

    @PostMapping("/explain")
    public SqlResult explain(@Valid @RequestBody SqlRequest request, @RequestHeader(value = "X-User", required = false) String actor) throws Exception {
        return sqlService.explain(request.connectionId(), request.sql(), actor);
    }

    @PostMapping("/format")
    public FormatResponse format(@Valid @RequestBody FormatRequest request) {
        return new FormatResponse(sqlService.format(request.sql()));
    }

    @GetMapping("/history")
    public java.util.List<SqlHistoryResponse> history(@RequestParam long connectionId, @RequestParam(required = false) Integer limit) {
        return sqlService.history(connectionId, limit);
    }

    @PostMapping("/completions")
    public java.util.List<SqlCompletionItem> completions(@Valid @RequestBody SqlCompletionRequest request) {
        return sqlService.completions(request);
    }

    @PostMapping("/export")
    public ResponseEntity<String> export(@Valid @RequestBody ExportRequest request, @RequestHeader(value = "X-User", required = false) String actor) throws Exception {
        String body = exportService.export(request.connectionId(), request.sql(), request.format(), actor);
        String format = normalizedExportFormat(request.format());
        return ResponseEntity.ok()
                .contentType(exportContentType(format))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"query-result." + format + "\"")
                .body(body);
    }

    private String normalizedExportFormat(String format) {
        String normalized = format == null ? "" : format.toLowerCase();
        if (java.util.Set.of("csv", "json", "sql", "xml").contains(normalized)) {
            return normalized;
        }
        throw new IllegalArgumentException("不支持的导出格式：" + format);
    }

    private MediaType exportContentType(String format) {
        return switch (format) {
            case "json" -> MediaType.APPLICATION_JSON;
            case "xml" -> MediaType.APPLICATION_XML;
            case "sql" -> MediaType.TEXT_PLAIN;
            default -> MediaType.parseMediaType("text/csv");
        };
    }
}
