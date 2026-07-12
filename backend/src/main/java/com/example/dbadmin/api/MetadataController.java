package com.example.dbadmin.api;

import com.example.dbadmin.dto.ApiDtos.CompletionCatalogResponse;
import com.example.dbadmin.dto.ApiDtos.BackupTargetPage;
import com.example.dbadmin.dto.ApiDtos.MetadataResponse;
import com.example.dbadmin.dto.ApiDtos.ObjectDetail;
import com.example.dbadmin.dto.ApiDtos.ObjectDdlResponse;
import com.example.dbadmin.dto.ApiDtos.ObjectRowCountResponse;
import com.example.dbadmin.dto.ApiDtos.ObjectRelations;
import com.example.dbadmin.dto.ApiDtos.ObjectStructure;
import com.example.dbadmin.dto.ApiDtos.TableDesignRequest;
import com.example.dbadmin.dto.ApiDtos.TableDesignResponse;
import com.example.dbadmin.service.MetadataService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/metadata")
public class MetadataController {
    private final MetadataService service;

    public MetadataController(MetadataService service) {
        this.service = service;
    }

    @GetMapping("/{connectionId}")
    public MetadataResponse inspect(
            @PathVariable long connectionId,
            @RequestParam(required = false) String schema,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize,
            @RequestParam(defaultValue = "false") boolean refresh
    ) throws Exception {
        return service.inspect(connectionId, schema, keyword, page, pageSize, refresh);
    }

    @GetMapping("/{connectionId}/completion-catalog")
    public CompletionCatalogResponse completionCatalog(
            @PathVariable long connectionId,
            @RequestParam(required = false) String schema,
            @RequestParam(required = false) String namespace,
            @RequestParam(required = false) String prefix,
            @RequestParam(required = false) Integer limit,
            @RequestParam(defaultValue = "false") boolean refresh
    ) throws Exception {
        String requestedNamespace = namespace == null || namespace.isBlank() ? schema : namespace;
        return service.completionCatalog(connectionId, requestedNamespace, prefix, limit, refresh);
    }

    @GetMapping("/{connectionId}/backup-targets/namespaces")
    public BackupTargetPage backupTargetNamespaces(
            @PathVariable long connectionId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize,
            @RequestParam(defaultValue = "false") boolean refresh
    ) throws Exception {
        return service.backupTargetNamespaces(connectionId, keyword, page, pageSize, refresh);
    }

    @GetMapping("/{connectionId}/backup-targets/tables")
    public BackupTargetPage backupTargetTables(
            @PathVariable long connectionId,
            @RequestParam String namespaceName,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize,
            @RequestParam(defaultValue = "false") boolean refresh
    ) throws Exception {
        return service.backupTargetTables(connectionId, namespaceName, keyword, page, pageSize, refresh);
    }

    @GetMapping("/{connectionId}/objects/detail")
    public ObjectDetail detail(@PathVariable long connectionId, @RequestParam(required = false) String schemaName, @RequestParam String objectName, @RequestParam(defaultValue = "false") boolean refresh) throws Exception {
        return service.detail(connectionId, schemaName, objectName, refresh);
    }

    @GetMapping("/{connectionId}/objects/ddl")
    public ObjectDdlResponse ddl(@PathVariable long connectionId, @RequestParam(required = false) String schemaName, @RequestParam String objectName, @RequestParam(defaultValue = "false") boolean refresh) throws Exception {
        return service.ddl(connectionId, schemaName, objectName, refresh);
    }

    @GetMapping("/{connectionId}/objects/row-count")
    public ObjectRowCountResponse rowCount(@PathVariable long connectionId, @RequestParam(required = false) String schemaName, @RequestParam String objectName) throws Exception {
        return service.rowCount(connectionId, schemaName, objectName);
    }

    @GetMapping("/{connectionId}/objects/structure")
    public ObjectStructure structure(@PathVariable long connectionId, @RequestParam(required = false) String schemaName, @RequestParam String objectName, @RequestParam(defaultValue = "false") boolean refresh) throws Exception {
        return service.structure(connectionId, schemaName, objectName, refresh);
    }

    @GetMapping("/{connectionId}/objects/relations")
    public ObjectRelations relations(@PathVariable long connectionId, @RequestParam(required = false) String schemaName, @RequestParam String objectName, @RequestParam(defaultValue = "false") boolean refresh) throws Exception {
        return service.relations(connectionId, schemaName, objectName, refresh);
    }

    @PostMapping("/{connectionId}/objects/design/preview")
    public TableDesignResponse previewDesign(@PathVariable long connectionId, @Valid @RequestBody TableDesignRequest request) throws Exception {
        return service.previewDesign(connectionId, request);
    }

    @PostMapping("/{connectionId}/objects/design/execute")
    public TableDesignResponse executeDesign(
            @PathVariable long connectionId,
            @Valid @RequestBody TableDesignRequest request,
            @RequestHeader(value = "X-User", required = false) String actor,
            @RequestHeader(value = "X-Production-Confirmation", required = false) String productionConfirmation
    ) throws Exception {
        return service.executeDesign(connectionId, request, actor, productionConfirmation);
    }
}
