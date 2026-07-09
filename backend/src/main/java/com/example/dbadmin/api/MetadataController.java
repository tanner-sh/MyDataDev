package com.example.dbadmin.api;

import com.example.dbadmin.dto.ApiDtos.MetadataResponse;
import com.example.dbadmin.dto.ApiDtos.ObjectDetail;
import com.example.dbadmin.dto.ApiDtos.ObjectStructure;
import com.example.dbadmin.service.MetadataService;
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
}
