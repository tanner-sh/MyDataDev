package com.example.dbadmin.api;

import com.example.dbadmin.access.ConnectionAccessService;
import com.example.dbadmin.access.ConnectionPermission;
import com.example.dbadmin.dto.ApiDtos.TableDesignResponse;
import com.example.dbadmin.service.MySqlTablePropertiesService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/metadata/{connectionId}/table-properties")
public class TablePropertiesController {
    private final MySqlTablePropertiesService service;
    private final ConnectionAccessService access;
    public TablePropertiesController(MySqlTablePropertiesService service, ConnectionAccessService access) {
        this.service = service; this.access = access;
    }
    @GetMapping
    public MySqlTablePropertiesService.Properties inspect(@PathVariable long connectionId, @RequestParam(required=false) String schema, @RequestParam String table) throws Exception {
        access.require(connectionId, ConnectionPermission.QUERY);
        return service.inspect(connectionId, schema, table);
    }
    @PostMapping("/preview")
    public TableDesignResponse preview(@PathVariable long connectionId, @RequestBody MySqlTablePropertiesService.Change change) throws Exception {
        access.require(connectionId, ConnectionPermission.DDL);
        return service.preview(connectionId, change);
    }
    @PostMapping("/execute")
    public TableDesignResponse execute(@PathVariable long connectionId, @RequestBody MySqlTablePropertiesService.Change change,
            @RequestHeader(value="X-User", required=false) String actor,
            @RequestHeader(value="X-Production-Confirmation", required=false) String confirmation) throws Exception {
        access.require(connectionId, ConnectionPermission.DDL);
        return service.execute(connectionId, change, actor, confirmation);
    }
}
