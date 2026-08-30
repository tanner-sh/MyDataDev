package com.example.dbadmin.api;

import com.example.dbadmin.access.ConnectionAccessService;
import com.example.dbadmin.access.ConnectionPermission;
import com.example.dbadmin.dto.ApiDtos.ExportRequest;
import com.example.dbadmin.dto.ApiDtos.FormatRequest;
import com.example.dbadmin.dto.ApiDtos.FormatResponse;
import com.example.dbadmin.dto.ApiDtos.SqlCompletionItem;
import com.example.dbadmin.dto.ApiDtos.SqlCompletionRequest;
import com.example.dbadmin.dto.ApiDtos.SqlHistoryResponse;
import com.example.dbadmin.dto.ApiDtos.SqlTransactionResponse;
import com.example.dbadmin.dto.ApiDtos.SqlRequest;
import com.example.dbadmin.dto.ApiDtos.SqlPageRequest;
import com.example.dbadmin.dto.ApiDtos.SqlResult;
import com.example.dbadmin.dto.ApiDtos.SqlScriptRequest;
import com.example.dbadmin.dto.ApiDtos.SqlScriptResponse;
import com.example.dbadmin.service.ExportService;
import com.example.dbadmin.service.SqlService;
import com.example.dbadmin.auth.WebIdentity;
import org.springframework.security.core.Authentication;
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
    private final com.example.dbadmin.service.SqlTransactionService transactions;
    private final ConnectionAccessService access;

    public SqlController(
            SqlService sqlService,
            ExportService exportService,
            com.example.dbadmin.service.SqlTransactionService transactions,
            ConnectionAccessService access
    ) {
        this.sqlService = sqlService;
        this.exportService = exportService;
        this.transactions = transactions;
        this.access = access;
    }

    @PostMapping("/execute")
    public SqlResult execute(
            @Valid @RequestBody SqlRequest request,
            @RequestHeader(value = "X-User", required = false) String actor,
            @RequestHeader(value = "X-Production-Confirmation", required = false) String productionConfirmation
    ) throws Exception {
        access.requireSql(request.connectionId(), request.sql());
        return sqlService.execute(request.connectionId(), request.sql(), request.maxRows(), actor, request.executionId(), productionConfirmation, request.schemaName(), request.unscopedMutationConfirmed());
    }

    @PostMapping("/execute-script")
    public SqlScriptResponse executeScript(
            @Valid @RequestBody SqlScriptRequest request,
            @RequestHeader(value = "X-User", required = false) String actor,
            @RequestHeader(value = "X-Production-Confirmation", required = false) String productionConfirmation
    ) throws Exception {
        access.requireSql(request.connectionId(), request.sql());
        return sqlService.executeScript(request.connectionId(), request.sql(), request.maxRows(), request.pageSize(), actor, request.executionId(), productionConfirmation, request.schemaName(), request.unscopedMutationConfirmed());
    }

    @PostMapping("/query-page")
    public SqlResult queryPage(
            @Valid @RequestBody SqlPageRequest request,
            @RequestHeader(value = "X-User", required = false) String actor,
            @RequestHeader(value = "X-Production-Confirmation", required = false) String productionConfirmation
    ) throws Exception {
        access.require(request.connectionId(), ConnectionPermission.QUERY);
        return sqlService.executePage(request.connectionId(), request.sql(), request.offset(), request.pageSize(), actor, request.executionId(), productionConfirmation, request.schemaName());
    }

    @PostMapping("/explain")
    public SqlResult explain(
            @Valid @RequestBody SqlRequest request,
            @RequestHeader(value = "X-User", required = false) String actor,
            @RequestHeader(value = "X-Production-Confirmation", required = false) String productionConfirmation
    ) throws Exception {
        access.require(request.connectionId(), ConnectionPermission.QUERY);
        return sqlService.explain(request.connectionId(), request.sql(), actor, productionConfirmation, request.schemaName());
    }

    @PostMapping("/format")
    public FormatResponse format(@Valid @RequestBody FormatRequest request) {
        return new FormatResponse(sqlService.format(request.sql()));
    }

    @PostMapping("/executions/{executionId}/cancel")
    public com.example.dbadmin.dto.ApiDtos.MessageResponse cancel(@PathVariable String executionId) throws Exception {
        access.requireSqlExecution(executionId);
        boolean cancelled = sqlService.cancel(executionId);
        return new com.example.dbadmin.dto.ApiDtos.MessageResponse(cancelled, cancelled ? "已发送取消请求" : "SQL 已结束或不存在");
    }

    @PostMapping("/transactions")
    public SqlTransactionResponse beginTransaction(
            @Valid @RequestBody com.example.dbadmin.dto.ApiDtos.SqlTransactionBeginRequest request,
            @RequestHeader(value = "X-User", required = false) String actor,
            @RequestHeader(value = "X-Production-Confirmation", required = false) String productionConfirmation
    ) throws Exception {
        access.require(request.connectionId(), ConnectionPermission.DATA_WRITE);
        return transactions.begin(request.connectionId(), request.schemaName(), actor, productionConfirmation);
    }

    /** 页面刷新后要能认回还开着的事务，否则那条连接会一直被占到超时。 */
    @GetMapping("/transactions/active")
    public java.util.Map<String, Object> activeTransaction(@RequestParam long connectionId) {
        access.require(connectionId, ConnectionPermission.QUERY);
        SqlTransactionResponse active = transactions.active(connectionId);
        return active == null ? java.util.Map.of() : java.util.Map.of("transaction", active);
    }

    @PostMapping("/transactions/{id}/execute")
    public com.example.dbadmin.dto.ApiDtos.SqlTransactionScriptResponse executeInTransaction(
            @PathVariable String id,
            @Valid @RequestBody com.example.dbadmin.dto.ApiDtos.SqlTransactionExecuteRequest request,
            @RequestHeader(value = "X-User", required = false) String actor
    ) throws Exception {
        access.requireTransaction(id, request.sql());
        return transactions.execute(id, request.sql(), request.maxRows(), actor, request.unscopedMutationConfirmed());
    }

    @PostMapping("/transactions/{id}/commit")
    public SqlTransactionResponse commitTransaction(
            @PathVariable String id,
            @RequestHeader(value = "X-User", required = false) String actor
    ) throws Exception {
        access.requireTransaction(id, null);
        return transactions.finish(id, true, actor);
    }

    @PostMapping("/transactions/{id}/rollback")
    public SqlTransactionResponse rollbackTransaction(
            @PathVariable String id,
            @RequestHeader(value = "X-User", required = false) String actor
    ) throws Exception {
        access.requireTransaction(id, null);
        return transactions.finish(id, false, actor);
    }

    @GetMapping("/history")
    public java.util.List<SqlHistoryResponse> history(
            @RequestParam long connectionId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false, defaultValue = "mine") String scope,
            Authentication authentication
    ) {
        access.require(connectionId, ConnectionPermission.QUERY);
        WebIdentity identity = authentication != null && authentication.getPrincipal() instanceof WebIdentity webIdentity
                ? webIdentity : null;
        boolean allUsers = "all".equalsIgnoreCase(scope);
        if (!allUsers && !"mine".equalsIgnoreCase(scope)) {
            throw new IllegalArgumentException("不支持的 SQL 历史范围：" + scope);
        }
        if (allUsers && identity != null && !"ADMIN".equals(identity.role())) {
            throw new ApiProblemException(
                    org.springframework.http.HttpStatus.FORBIDDEN,
                    "SQL_HISTORY_ALL_ADMIN_REQUIRED",
                    "只有管理员可以查看该连接中全部用户的 SQL 历史。"
            );
        }
        Long actorUserId = identity != null && !allUsers ? identity.userId() : null;
        return sqlService.history(connectionId, keyword, limit, actorUserId);
    }

    @PostMapping("/completions")
    public java.util.List<SqlCompletionItem> completions(@Valid @RequestBody SqlCompletionRequest request) {
        access.require(request.connectionId(), ConnectionPermission.VIEW_METADATA);
        return sqlService.completions(request);
    }

    @PostMapping("/export")
    public ResponseEntity<StreamingResponseBody> export(
            @Valid @RequestBody ExportRequest request,
            @RequestHeader(value = "X-User", required = false) String actor,
            @RequestHeader(value = "X-Production-Confirmation", required = false) String productionConfirmation
    ) throws Exception {
        access.require(request.connectionId(), ConnectionPermission.EXPORT);
        access.require(request.connectionId(), ConnectionPermission.QUERY);
        String format = normalizedExportFormat(request.format());
        ExportService.PreparedExport prepared = exportService.prepare(
                request.connectionId(), request.sql(), format, actor, productionConfirmation,
                request.schemaName(), request.targetTableParts()
        );
        StreamingResponseBody body = output -> prepared.writeTo(output);
        return ResponseEntity.ok()
                .contentType(exportContentType(format))
                .contentLength(prepared.size())
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"query-result." + exportFileExtension(format) + "\"")
                .header("X-Export-Row-Limit", String.valueOf(ExportService.EXPORT_MAX_ROWS))
                .header("X-Export-Truncated", String.valueOf(prepared.truncated()))
                .body(body);
    }

    private String normalizedExportFormat(String format) {
        String normalized = format == null ? "" : format.toLowerCase(java.util.Locale.ROOT);
        if (java.util.Set.of("csv", "json", "sql", "xml", "markdown", "xlsx").contains(normalized)) {
            return normalized;
        }
        throw new IllegalArgumentException("不支持的导出格式：" + format);
    }

    /** Markdown 的惯例扩展名是 .md；写成 .markdown 会让不少工具认不出来。 */
    private String exportFileExtension(String format) {
        return "markdown".equals(format) ? "md" : format;
    }

    private MediaType exportContentType(String format) {
        return switch (format) {
            case "json" -> MediaType.APPLICATION_JSON;
            case "xml" -> MediaType.APPLICATION_XML;
            case "sql" -> MediaType.TEXT_PLAIN;
            case "markdown" -> MediaType.parseMediaType("text/markdown");
            case "xlsx" -> MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            default -> MediaType.parseMediaType("text/csv");
        };
    }
}
