package com.example.dbadmin.api;

import com.example.dbadmin.access.ConnectionAccessService;
import com.example.dbadmin.access.ConnectionPermission;
import com.example.dbadmin.dto.ApiDtos.DataCommitResponse;
import com.example.dbadmin.dto.ApiDtos.DataPreviewRequest;
import com.example.dbadmin.dto.ApiDtos.DataPreviewResponse;
import com.example.dbadmin.dto.ApiDtos.TableDataResponse;
import com.example.dbadmin.dto.ApiDtos.TableDataRequest;
import com.example.dbadmin.service.DataEditService;
import com.example.dbadmin.repo.AuditRepository;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/data")
public class DataController {
    private final DataEditService service;
    private final ConnectionAccessService access;
    private final AuditRepository audit;

    public DataController(DataEditService service, ConnectionAccessService access, AuditRepository audit) {
        this.service = service;
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
