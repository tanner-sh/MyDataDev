package com.example.dbadmin.api;

import com.example.dbadmin.access.ConnectionAccessService;
import com.example.dbadmin.access.ConnectionPermission;
import com.example.dbadmin.dto.ApiDtos.DataCommitResponse;
import com.example.dbadmin.dto.ApiDtos.DataPreviewRequest;
import com.example.dbadmin.dto.ApiDtos.DataPreviewResponse;
import com.example.dbadmin.dto.ApiDtos.TableDataResponse;
import com.example.dbadmin.dto.ApiDtos.TableDataRequest;
import com.example.dbadmin.dto.ApiDtos.TableExportRequest;
import com.example.dbadmin.service.DataEditService;
import com.example.dbadmin.service.ExportService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import com.example.dbadmin.repo.AuditRepository;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/data")
public class DataController {
    private final DataEditService service;
    private final ExportService exportService;
    private final ConnectionAccessService access;
    private final AuditRepository audit;

    public DataController(DataEditService service, ExportService exportService,
                          ConnectionAccessService access, AuditRepository audit) {
        this.service = service;
        this.exportService = exportService;
        this.access = access;
        this.audit = audit;
    }

    @GetMapping("/table")
    public TableDataResponse table(
            @RequestParam long connectionId,
            @RequestParam(required = false) String schemaName,
            @RequestParam String tableName,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "100") int pageSize,
            @RequestHeader(value = "X-User", required = false) String actor
    ) throws Exception {
        access.require(connectionId, ConnectionPermission.QUERY);
        TableDataResponse response = service.table(connectionId, schemaName, tableName, cursor, pageSize);
        audit.onConnection(actor, "TABLE_VIEW", connectionId, "table:" + tableName,
                "schema=" + schemaName + ", rows=" + response.rows().size() + ", filtered=false");
        return response;
    }

    @PostMapping("/table/query")
    public TableDataResponse queryTable(@Valid @RequestBody TableDataRequest request,
                                        @RequestHeader(value = "X-User", required = false) String actor) throws Exception {
        access.require(request.connectionId(), ConnectionPermission.QUERY);
        TableDataResponse response = service.table(request);
        audit.onConnection(actor, "TABLE_VIEW", request.connectionId(), "table:" + request.tableName(),
                "schema=" + request.schemaName() + ", rows=" + response.rows().size()
                        + ", filters=" + (request.filters() == null ? 0 : request.filters().size())
                        + ", sorts=" + (request.sorts() == null ? 0 : request.sorts().size()));
        return response;
    }

    /**
     * 导出这张表当前筛选与排序下的全部行。
     *
     * <p>用的是浏览那条查询本身（去掉分页），所以「界面上看到 12 行、导出得到 40 行」这种
     * 不一致不可能发生。生产确认照旧要 —— 浏览是把数据显示在屏幕上，导出是把它带出系统。</p>
     */
    @PostMapping("/table/export")
    public ResponseEntity<StreamingResponseBody> exportTable(
            @Valid @RequestBody TableExportRequest request,
            @RequestHeader(value = "X-User", required = false) String actor,
            @RequestHeader(value = "X-Production-Confirmation", required = false) String productionConfirmation
    ) throws Exception {
        access.require(request.query().connectionId(), ConnectionPermission.EXPORT);
        access.require(request.query().connectionId(), ConnectionPermission.QUERY);
        String format = ExportFormats.normalize(request.format());
        DataEditService.TableExportQuery query = service.exportQuery(request.query());
        ExportService.PreparedExport prepared = exportService.prepareGenerated(
                request.query().connectionId(), query.sql(), query.binder(), format, actor, productionConfirmation,
                request.query().schemaName(),
                "table:" + request.query().tableName() + " filters="
                        + (request.query().filters() == null ? 0 : request.query().filters().size()));
        return ResponseEntity.ok()
                .contentType(ExportFormats.contentType(format))
                .contentLength(prepared.size())
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + exportFileName(request, format) + "\"")
                .header("X-Export-Row-Limit", String.valueOf(ExportService.EXPORT_MAX_ROWS))
                .header("X-Export-Truncated", String.valueOf(prepared.truncated()))
                .body(prepared::writeTo);
    }

    /** 文件名带上表名：一次导出好几张表时，query-result.csv 这种名字分不出谁是谁。 */
    private static String exportFileName(TableExportRequest request, String format) {
        String table = request.query().tableName().replaceAll("[\\\\/:*?\"<>|\\x00]", "_");
        return table + "." + ExportFormats.extension(format);
    }

    @PostMapping("/preview")
    public DataPreviewResponse preview(@Valid @RequestBody DataPreviewRequest request) throws Exception {
        access.require(request.connectionId(), ConnectionPermission.DATA_WRITE);
        return service.preview(request);
    }

    @PostMapping("/commit")
    public DataCommitResponse commit(
            @Valid @RequestBody DataPreviewRequest request,
            @RequestHeader(value = "X-User", required = false) String actor,
            @RequestHeader(value = "X-Production-Confirmation", required = false) String productionConfirmation
    ) throws Exception {
        access.require(request.connectionId(), ConnectionPermission.DATA_WRITE);
        return service.commit(request, actor, productionConfirmation);
    }
}
