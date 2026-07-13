package com.example.dbadmin.api;

import com.example.dbadmin.dto.ApiDtos.ExportRequest;
import com.example.dbadmin.dto.ApiDtos.FormatRequest;
import com.example.dbadmin.dto.ApiDtos.FormatResponse;
import com.example.dbadmin.dto.ApiDtos.SqlCompletionItem;
import com.example.dbadmin.dto.ApiDtos.SqlCompletionRequest;
import com.example.dbadmin.dto.ApiDtos.SqlHistoryResponse;
import com.example.dbadmin.dto.ApiDtos.SqlRequest;
import com.example.dbadmin.dto.ApiDtos.SqlPageRequest;
import com.example.dbadmin.dto.ApiDtos.SqlResult;
import com.example.dbadmin.dto.ApiDtos.SqlScriptRequest;
import com.example.dbadmin.dto.ApiDtos.SqlScriptResponse;
import com.example.dbadmin.service.ExportService;
import com.example.dbadmin.service.SqlService;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
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
    public SqlResult execute(
            @Valid @RequestBody SqlRequest request,
            @RequestHeader(value = "X-User", required = false) String actor,
            @RequestHeader(value = "X-Production-Confirmation", required = false) String productionConfirmation
    ) throws Exception {
        return sqlService.execute(request.connectionId(), request.sql(), request.maxRows(), actor, request.executionId(), productionConfirmation);
    }

    @PostMapping("/execute-script")
    public SqlScriptResponse executeScript(
            @Valid @RequestBody SqlScriptRequest request,
            @RequestHeader(value = "X-User", required = false) String actor,
            @RequestHeader(value = "X-Production-Confirmation", required = false) String productionConfirmation
    ) throws Exception {
        return sqlService.executeScript(request.connectionId(), request.sql(), request.maxRows(), request.pageSize(), actor, request.executionId(), productionConfirmation);
    }

    @PostMapping("/query-page")
    public SqlResult queryPage(
            @Valid @RequestBody SqlPageRequest request,
            @RequestHeader(value = "X-User", required = false) String actor,
            @RequestHeader(value = "X-Production-Confirmation", required = false) String productionConfirmation
    ) throws Exception {
        return sqlService.executePage(request.connectionId(), request.sql(), request.offset(), request.pageSize(), actor, request.executionId(), productionConfirmation);
    }

    @PostMapping("/explain")
    public SqlResult explain(
            @Valid @RequestBody SqlRequest request,
            @RequestHeader(value = "X-User", required = false) String actor,
            @RequestHeader(value = "X-Production-Confirmation", required = false) String productionConfirmation
    ) throws Exception {
        return sqlService.explain(request.connectionId(), request.sql(), actor, productionConfirmation);
    }

    @PostMapping("/format")
    public FormatResponse format(@Valid @RequestBody FormatRequest request) {
        return new FormatResponse(sqlService.format(request.sql()));
    }

    @PostMapping("/executions/{executionId}/cancel")
    public com.example.dbadmin.dto.ApiDtos.MessageResponse cancel(@PathVariable String executionId) throws Exception {
        boolean cancelled = sqlService.cancel(executionId);
        return new com.example.dbadmin.dto.ApiDtos.MessageResponse(cancelled, cancelled ? "已发送取消请求" : "SQL 已结束或不存在");
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
    public ResponseEntity<StreamingResponseBody> export(
            @Valid @RequestBody ExportRequest request,
            @RequestHeader(value = "X-User", required = false) String actor,
            @RequestHeader(value = "X-Production-Confirmation", required = false) String productionConfirmation
    ) throws Exception {
        String format = normalizedExportFormat(request.format());
        ExportService.PreparedExport prepared = exportService.prepare(
                request.connectionId(), request.sql(), format, actor, productionConfirmation
        );
        StreamingResponseBody body = output -> {
            try {
                prepared.writeTo(output);
            } catch (java.io.IOException e) {
                throw e;
            } catch (Exception e) {
                throw new java.io.IOException(e.getMessage(), e);
            }
        };
        return ResponseEntity.ok()
                .contentType(exportContentType(format))
                .contentLength(prepared.size())
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"query-result." + format + "\"")
                .header("X-Export-Row-Limit", String.valueOf(ExportService.EXPORT_MAX_ROWS))
                .header("X-Export-Truncated", String.valueOf(prepared.truncated()))
                .body(body);
    }

    private String normalizedExportFormat(String format) {
        String normalized = format == null ? "" : format.toLowerCase(java.util.Locale.ROOT);
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
