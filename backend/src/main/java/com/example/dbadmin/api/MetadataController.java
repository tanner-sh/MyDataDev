package com.example.dbadmin.api;

import com.example.dbadmin.dto.ApiDtos.MetadataResponse;
import com.example.dbadmin.dto.ApiDtos.ObjectDetail;
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
            @RequestParam(required = false) Integer pageSize
    ) throws Exception {
        return service.inspect(connectionId, schema, keyword, page, pageSize);
    }

    @GetMapping("/{connectionId}/objects/detail")
    public ObjectDetail detail(@PathVariable long connectionId, @RequestParam(required = false) String schemaName, @RequestParam String objectName) throws Exception {
        return service.detail(connectionId, schemaName, objectName);
    }

    @GetMapping("/{connectionId}/objects/structure")
    public ObjectStructure structure(@PathVariable long connectionId, @RequestParam(required = false) String schemaName, @RequestParam String objectName) throws Exception {
        return service.structure(connectionId, schemaName, objectName);
    }

    @GetMapping("/{connectionId}/objects/relations")
    public ObjectRelations relations(@PathVariable long connectionId, @RequestParam(required = false) String schemaName, @RequestParam String objectName) throws Exception {
        return service.relations(connectionId, schemaName, objectName);
    }

    @PostMapping("/{connectionId}/objects/design/preview")
    public TableDesignResponse previewDesign(@PathVariable long connectionId, @Valid @RequestBody TableDesignRequest request) throws Exception {
        return service.previewDesign(connectionId, request);
    }

    @PostMapping("/{connectionId}/objects/design/execute")
    public TableDesignResponse executeDesign(@PathVariable long connectionId, @Valid @RequestBody TableDesignRequest request, @RequestHeader(value = "X-User", required = false) String actor) throws Exception {
        return service.executeDesign(connectionId, request, actor);
    }
}
