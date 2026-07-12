package com.example.dbadmin.api;

import com.example.dbadmin.dto.ApiDtos.DataCommitResponse;
import com.example.dbadmin.dto.ApiDtos.DataPreviewRequest;
import com.example.dbadmin.dto.ApiDtos.DataPreviewResponse;
import com.example.dbadmin.dto.ApiDtos.TableDataResponse;
import com.example.dbadmin.service.DataEditService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/data")
public class DataController {
    private final DataEditService service;

    public DataController(DataEditService service) {
        this.service = service;
    }

    @GetMapping("/table")
    public TableDataResponse table(
            @RequestParam long connectionId,
            @RequestParam(required = false) String schemaName,
            @RequestParam String tableName,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "100") int pageSize
    ) throws Exception {
        return service.table(connectionId, schemaName, tableName, cursor, pageSize);
    }

    @PostMapping("/preview")
    public DataPreviewResponse preview(@Valid @RequestBody DataPreviewRequest request) throws Exception {
        return service.preview(request);
    }

    @PostMapping("/commit")
    public DataCommitResponse commit(
            @Valid @RequestBody DataPreviewRequest request,
            @RequestHeader(value = "X-User", required = false) String actor,
            @RequestHeader(value = "X-Production-Confirmation", required = false) String productionConfirmation
    ) throws Exception {
        return service.commit(request, actor, productionConfirmation);
    }
}
