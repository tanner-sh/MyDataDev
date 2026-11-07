package com.example.dbadmin.api;

import com.example.dbadmin.access.ConnectionAccessService;
import com.example.dbadmin.access.ConnectionPermission;
import com.example.dbadmin.dto.ApiDtos.CompletionCatalogResponse;
import com.example.dbadmin.dto.ApiDtos.BackupTargetPage;
import com.example.dbadmin.dto.ApiDtos.MetadataResponse;
import com.example.dbadmin.dto.ApiDtos.ObjectDetail;
import com.example.dbadmin.dto.ApiDtos.ObjectDdlResponse;
import com.example.dbadmin.dto.ApiDtos.SchemaExportCandidates;
import com.example.dbadmin.dto.ApiDtos.SchemaExportRequest;
import com.example.dbadmin.dto.ApiDtos.SchemaExportResponse;
import com.example.dbadmin.dto.ApiDtos.ObjectRowCountResponse;
import com.example.dbadmin.dto.ApiDtos.ObjectSearchResponse;
import com.example.dbadmin.dto.ApiDtos.ObjectRelations;
import com.example.dbadmin.dto.ApiDtos.ObjectStructure;
import com.example.dbadmin.dto.ApiDtos.ObjectColumns;
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
import com.example.dbadmin.service.SchemaExportService;
import com.example.dbadmin.service.TableDesignService;
import com.example.dbadmin.service.RoutineInvocationService;
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
    private final SchemaExportService schemaExport;
    private final TableDesignService tableDesign;
    private final RoutineInvocationService routines;

    public MetadataController(MetadataService service, SchemaObjectService schemaObjects, ObjectSearchService objectSearch,
                              SchemaDiagramService diagrams, ConnectionAccessService access, SchemaExportService schemaExport,
                              TableDesignService tableDesign, RoutineInvocationService routines) {
        this.routines = routines;
        this.schemaExport = schemaExport;
        this.tableDesign = tableDesign;
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

    @GetMapping("/{connectionId}/objects/columns")
    public ObjectColumns columns(@PathVariable long connectionId, @RequestParam(required = false) String schemaName, @RequestParam String objectName, @RequestParam(defaultValue = "false") boolean refresh) throws Exception {
        view(connectionId);
        return service.objectColumns(connectionId, schemaName, objectName, refresh);
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
            @RequestParam(required = false) Integer limit,
            @RequestParam(defaultValue = "false") boolean refresh
    ) throws Exception {
        view(connectionId);
        return diagrams.build(connectionId, schemaName, limit, refresh);
    }

    @PostMapping("/{connectionId}/objects/design/preview")
    public TableDesignResponse previewDesign(@PathVariable long connectionId, @Valid @RequestBody TableDesignRequest request) throws Exception {
        ddlAccess(connectionId);
        return tableDesign.previewDesign(connectionId, request);
    }

    @PostMapping("/{connectionId}/objects/design/execute")
    public TableDesignResponse executeDesign(
            @PathVariable long connectionId,
            @Valid @RequestBody TableDesignRequest request,
            @RequestHeader(value = "X-User", required = false) String actor,
            @RequestHeader(value = "X-Production-Confirmation", required = false) String productionConfirmation
    ) throws Exception {
        ddlAccess(connectionId);
        return tableDesign.executeDesign(connectionId, request, actor, productionConfirmation);
    }

    @PostMapping("/{connectionId}/tables/lifecycle/preview")
    public TableDesignResponse previewTableLifecycle(
            @PathVariable long connectionId,
            @Valid @RequestBody TableLifecycleRequest request
    ) throws Exception {
        ddlAccess(connectionId);
        return tableDesign.previewTableLifecycle(connectionId, request);
    }

    @PostMapping("/{connectionId}/tables/lifecycle/execute")
    public TableDesignResponse executeTableLifecycle(
            @PathVariable long connectionId,
            @Valid @RequestBody TableLifecycleRequest request,
            @RequestHeader(value = "X-User", required = false) String actor,
            @RequestHeader(value = "X-Production-Confirmation", required = false) String productionConfirmation
    ) throws Exception {
        ddlAccess(connectionId);
        return tableDesign.executeTableLifecycle(connectionId, request, actor, productionConfirmation);
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
        return routines.invoke(connectionId, request, actor, productionConfirmation);
    }

    /**
     * 后台调用：参数校验与前台相同，执行交给命令窗口那条后台任务（语句超时按后台任务的配置，可取消、可看进度）。
     * 返回任务，日志用 {@code GET /api/sql-file-executions/{id}/command-log} 轮询。
     */
    @PostMapping("/{connectionId}/schema-objects/invoke-background")
    public org.springframework.http.ResponseEntity<com.example.dbadmin.dto.ApiDtos.SqlFileExecutionResponse> invokeSchemaObjectInBackground(
            @PathVariable long connectionId,
            @Valid @RequestBody RoutineInvokeRequest request,
            @RequestHeader(value = "X-User", required = false) String actor,
            @RequestHeader(value = "X-Production-Confirmation", required = false) String productionConfirmation
    ) throws Exception {
        access.require(connectionId, ConnectionPermission.DATA_WRITE);
        return org.springframework.http.ResponseEntity.status(org.springframework.http.HttpStatus.ACCEPTED)
                .body(routines.invokeInBackground(connectionId, request, actor, productionConfirmation));
    }

    /** 对象定义批量导出的候选清单：表 + 方言支持的各类对象。 */
    @GetMapping("/{connectionId}/schema-export/candidates")
    public SchemaExportCandidates schemaExportCandidates(@PathVariable long connectionId, @RequestParam(required = false) String schemaName,
                                                         @RequestParam(required = false) String keyword) throws Exception {
        view(connectionId);
        return schemaExport.candidates(connectionId, schemaName, keyword);
    }

    /**
     * 把选中对象的定义按依赖顺序合成一份脚本。只读、只生成文本，与查看单个对象的 DDL 同档权限；
     * 用 POST 是因为一次要带几百个对象。
     */
    @PostMapping("/{connectionId}/schema-export")
    public SchemaExportResponse schemaExport(
            @PathVariable long connectionId,
            @Valid @RequestBody SchemaExportRequest request,
            @RequestHeader(value = "X-User", required = false) String actor
    ) throws Exception {
        view(connectionId);
        return schemaExport.export(connectionId, request, actor);
    }

    private void view(long connectionId) {
        access.require(connectionId, ConnectionPermission.VIEW_METADATA);
    }

    private void ddlAccess(long connectionId) {
        access.require(connectionId, ConnectionPermission.DDL);
    }
}
