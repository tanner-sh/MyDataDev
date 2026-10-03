package com.example.dbadmin.api;
import com.example.dbadmin.access.ConnectionAccessService;
import com.example.dbadmin.access.ConnectionPermission;
import com.example.dbadmin.dto.ApiDtos.ExportRequest;
import com.example.dbadmin.service.ExportService;
import com.example.dbadmin.service.SqlExecutionRegistry;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicReference;
@RestController
@RequestMapping("/api/sql/export-parameterized")
public class ParameterizedExportController {
    private final ExportService exports;
    private final ConnectionAccessService access;
    private final SqlExecutionRegistry executions;
    public ParameterizedExportController(ExportService exports, ConnectionAccessService access, SqlExecutionRegistry executions) {
        this.exports = exports; this.access = access; this.executions = executions;
    }
    @PostMapping public ResponseEntity<StreamingResponseBody> export(@Valid @RequestBody ExportRequest request,
            @RequestHeader(value="X-User", required=false) String actor,
            @RequestHeader(value="X-Production-Confirmation", required=false) String confirmation) throws Exception {
        access.require(request.connectionId(), ConnectionPermission.QUERY);
        access.require(request.connectionId(), ConnectionPermission.EXPORT);
        AtomicReference<Statement> statement = new AtomicReference<>();
        AtomicReference<String> executionId = new AtomicReference<>();
        ExportService.PreparedExport prepared;
        try {
            prepared = exports.prepareParameterized(request, actor, confirmation, value -> {
                statement.set(value); executionId.set(executions.register(request.executionId(), request.connectionId(), value));
            });
        } finally {
            if (executionId.get() != null) executions.unregister(executionId.get(), statement.get());
        }
        String format = ExportFormats.normalize(request.format());
        return ResponseEntity.ok().contentType(ExportFormats.contentType(format)).contentLength(prepared.size())
                .header("Content-Disposition", "attachment; filename=\"query-result." + ExportFormats.extension(format) + "\"")
                .header("X-Export-Row-Limit", request.fullResult() ? "all" : String.valueOf(ExportService.EXPORT_MAX_ROWS))
                .header("X-Export-Truncated", String.valueOf(prepared.truncated()))
                .body(prepared::writeTo);
    }
}
