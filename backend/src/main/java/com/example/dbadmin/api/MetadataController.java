package com.example.dbadmin.api;

import com.example.dbadmin.access.ConnectionAccessService;
import com.example.dbadmin.access.ConnectionPermission;
import com.example.dbadmin.dto.ApiDtos.CompletionCatalogResponse;
import com.example.dbadmin.dto.ApiDtos.BackupTargetPage;
import com.example.dbadmin.dto.ApiDtos.MetadataResponse;
import com.example.dbadmin.dto.ApiDtos.ObjectDetail;
import com.example.dbadmin.dto.ApiDtos.ObjectDdlResponse;
import com.example.dbadmin.dto.ApiDtos.ObjectRowCountResponse;
import com.example.dbadmin.dto.ApiDtos.ObjectSearchResponse;
import com.example.dbadmin.dto.ApiDtos.ObjectRelations;
import com.example.dbadmin.dto.ApiDtos.ObjectStructure;
import com.example.dbadmin.dto.ApiDtos.TableDesignRequest;
import com.example.dbadmin.dto.ApiDtos.TableDesignResponse;
import com.example.dbadmin.dto.ApiDtos.TableLifecycleRequest;
import com.example.dbadmin.dto.ApiDtos.RoutineInvokeRequest;
import com.example.dbadmin.dto.ApiDtos.RoutineInvokeResponse;
import com.example.dbadmin.dto.ApiDtos.SchemaObjectDetail;
import com.example.dbadmin.dto.ApiDtos.SchemaObjectLifecycleRequest;
import com.example.dbadmin.dto.ApiDtos.SchemaObjectLifecycleResponse;
import com.example.dbadmin.dto.ApiDtos.SchemaObjectPage;
import com.example.dbadmin.dto.ApiDtos.SchemaObjectTemplateResponse;
import com.example.dbadmin.dto.SchemaDiagramDtos.SchemaDiagram;
import com.example.dbadmin.service.MetadataService;
import com.example.dbadmin.service.ObjectSearchService;
import com.example.dbadmin.service.SchemaDiagramService;
import com.example.dbadmin.service.SchemaObjectService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/metadata")
public class MetadataController {
    private final MetadataService service;
    private final SchemaObjectService schemaObjects;
    private final ObjectSearchService objectSearch;
    private final SchemaDiagramService diagrams;
    private final ConnectionAccessService access;

    public MetadataController(MetadataService service, SchemaObjectService schemaObjects, ObjectSearchService objectSearch,
                              SchemaDiagramService diagrams, ConnectionAccessService access) {
        this.service = service;
        this.schemaObjects = schemaObjects;
        this.objectSearch = objectSearch;
        this.diagrams = diagrams;
        this.access = access;
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
        view(connectionId);
        return service.inspect(connectionId, schema, keyword, page, pageSize, refresh);
    }

    /** 跨对象类型的统一搜索，供命令面板式的全局搜索使用。 */
    @GetMapping("/{connectionId}/search")
    public ObjectSearchResponse search(
            @PathVariable long connectionId,
            @RequestParam(required = false) String schema,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer limit
    ) throws Exception {
        view(connectionId);
        return objectSearch.search(connectionId, schema, keyword, limit);
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
        view(connectionId);
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
        access.require(connectionId, ConnectionPermission.BACKUP_RESTORE);
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
        access.require(connectionId, ConnectionPermission.BACKUP_RESTORE);
        return service.backupTargetTables(connectionId, namespaceName, keyword, page, pageSize, refresh);
    }

    @GetMapping("/{connectionId}/objects/detail")
    public ObjectDetail detail(@PathVariable long connectionId, @RequestParam(required = false) String schemaName, @RequestParam String objectName, @RequestParam(defaultValue = "false") boolean refresh) throws Exception {
        view(connectionId);
        return service.detail(connectionId, schemaName, objectName, refresh);
    }

    @GetMapping("/{connectionId}/objects/ddl")
    public ObjectDdlResponse ddl(@PathVariable long connectionId, @RequestParam(required = false) String schemaName, @RequestParam String objectName, @RequestParam(defaultValue = "false") boolean refresh) throws Exception {
        view(connectionId);
        return service.ddl(connectionId, schemaName, objectName, refresh);
    }

    @GetMapping("/{connectionId}/objects/row-count")
    public ObjectRowCountResponse rowCount(@PathVariable long connectionId, @RequestParam(required = false) String schemaName, @RequestParam String objectName) throws Exception {
        access.require(connectionId, ConnectionPermission.QUERY);
        return service.rowCount(connectionId, schemaName, objectName);
    }

    @GetMapping("/{connectionId}/objects/structure")
    public ObjectStructure structure(@PathVariable long connectionId, @RequestParam(required = false) String schemaName, @RequestParam String objectName, @RequestParam(defaultValue = "false") boolean refresh) throws Exception {
        view(connectionId);
        return service.structure(connectionId, schemaName, objectName, refresh);
    }

    @GetMapping("/{connectionId}/objects/relations")
    public ObjectRelations relations(@PathVariable long connectionId, @RequestParam(required = false) String schemaName, @RequestParam String objectName, @RequestParam(defaultValue = "false") boolean refresh) throws Exception {
        view(connectionId);
        return service.relations(connectionId, schemaName, objectName, refresh);
    }

    /** Schema 级 ER 图。只读，只返回参与关系的列；表数超过上限时按 truncated 告知调用方。 */
    @GetMapping("/{connectionId}/diagram")
    public SchemaDiagram diagram(
            @PathVariable long connectionId,
            @RequestParam(required = false) String schemaName,
            @RequestParam(required = false) Integer limit
    ) throws Exception {
        view(connectionId);
        return diagrams.build(connectionId, schemaName, limit);
    }

    @PostMapping("/{connectionId}/objects/design/preview")
    public TableDesignResponse previewDesign(@PathVariable long connectionId, @Valid @RequestBody TableDesignRequest request) throws Exception {
        ddlAccess(connectionId);
        return service.previewDesign(connectionId, request);
    }

    @PostMapping("/{connectionId}/objects/design/execute")
    public TableDesignResponse executeDesign(
            @PathVariable long connectionId,
            @Valid @RequestBody TableDesignRequest request,
            @RequestHeader(value = "X-User", required = false) String actor,
            @RequestHeader(value = "X-Production-Confirmation", required = false) String productionConfirmation
    ) throws Exception {
        ddlAccess(connectionId);
        return service.executeDesign(connectionId, request, actor, productionConfirmation);
    }

    @PostMapping("/{connectionId}/tables/lifecycle/preview")
    public TableDesignResponse previewTableLifecycle(
            @PathVariable long connectionId,
            @Valid @RequestBody TableLifecycleRequest request
    ) throws Exception {
        ddlAccess(connectionId);
        return service.previewTableLifecycle(connectionId, request);
    }

    @PostMapping("/{connectionId}/tables/lifecycle/execute")
    public TableDesignResponse executeTableLifecycle(
            @PathVariable long connectionId,
            @Valid @RequestBody TableLifecycleRequest request,
            @RequestHeader(value = "X-User", required = false) String actor,
            @RequestHeader(value = "X-Production-Confirmation", required = false) String productionConfirmation
    ) throws Exception {
        ddlAccess(connectionId);
        return service.executeTableLifecycle(connectionId, request, actor, productionConfirmation);
    }

    @GetMapping("/{connectionId}/schema-objects")
    public SchemaObjectPage schemaObjects(
            @PathVariable long connectionId,
            @RequestParam(required = false) String schemaName,
            @RequestParam String kind,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize,
            @RequestParam(defaultValue = "false") boolean refresh
    ) throws Exception {
        view(connectionId);
        return schemaObjects.list(connectionId, schemaName, kind, keyword, page, pageSize, refresh);
    }

    @GetMapping("/{connectionId}/schema-objects/detail")
    public SchemaObjectDetail schemaObjectDetail(
            @PathVariable long connectionId,
            @RequestParam String objectKey,
            @RequestParam(defaultValue = "false") boolean refresh
    ) throws Exception {
        view(connectionId);
        return schemaObjects.detail(connectionId, objectKey, refresh);
    }

    @GetMapping("/{connectionId}/schema-objects/template")
    public SchemaObjectTemplateResponse schemaObjectTemplate(
            @PathVariable long connectionId,
            @RequestParam String kind,
            @RequestParam(required = false) String schemaName,
            @RequestParam String objectName
    ) {
        view(connectionId);
        return schemaObjects.template(connectionId, kind, schemaName, objectName);
    }

    @PostMapping("/{connectionId}/schema-objects/lifecycle/preview")
    public SchemaObjectLifecycleResponse previewSchemaObjectLifecycle(
            @PathVariable long connectionId,
            @Valid @RequestBody SchemaObjectLifecycleRequest request
    ) throws Exception {
        ddlAccess(connectionId);
        return schemaObjects.preview(connectionId, request);
    }

    @PostMapping("/{connectionId}/schema-objects/lifecycle/execute")
    public SchemaObjectLifecycleResponse executeSchemaObjectLifecycle(
            @PathVariable long connectionId,
            @Valid @RequestBody SchemaObjectLifecycleRequest request,
            @RequestHeader(value = "X-User", required = false) String actor,
            @RequestHeader(value = "X-Production-Confirmation", required = false) String productionConfirmation
    ) throws Exception {
        ddlAccess(connectionId);
        return schemaObjects.execute(connectionId, request, actor, productionConfirmation);
    }

    @PostMapping("/{connectionId}/schema-objects/invoke")
    public RoutineInvokeResponse invokeSchemaObject(
            @PathVariable long connectionId,
            @Valid @RequestBody RoutineInvokeRequest request,
            @RequestHeader(value = "X-User", required = false) String actor,
            @RequestHeader(value = "X-Production-Confirmation", required = false) String productionConfirmation
    ) throws Exception {
        access.require(connectionId, ConnectionPermission.DATA_WRITE);
        return schemaObjects.invoke(connectionId, request, actor, productionConfirmation);
    }

    private void view(long connectionId) {
        access.require(connectionId, ConnectionPermission.VIEW_METADATA);
    }

    private void ddlAccess(long connectionId) {
        access.require(connectionId, ConnectionPermission.DDL);
    }
}
