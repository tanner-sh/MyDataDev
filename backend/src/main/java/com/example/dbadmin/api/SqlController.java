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
        String format = request.format().equalsIgnoreCase("json") ? "json" : "csv";
        MediaType contentType = request.format().equalsIgnoreCase("json") ? MediaType.APPLICATION_JSON : MediaType.parseMediaType("text/csv");
        return ResponseEntity.ok()
                .contentType(contentType)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"query-result." + format + "\"")
                .body(body);
    }
}
