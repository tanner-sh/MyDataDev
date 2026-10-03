package com.example.dbadmin.api;

import com.example.dbadmin.access.ConnectionAccessService;
import com.example.dbadmin.access.ConnectionPermission;
import com.example.dbadmin.service.OracleTableStorageService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/metadata/{connectionId}/oracle-storage")
public class OracleStorageController {
    private final OracleTableStorageService service;
    private final ConnectionAccessService access;
    public OracleStorageController(OracleTableStorageService service, ConnectionAccessService access) {
        this.service = service; this.access = access;
    }
    @GetMapping
    public OracleTableStorageService.Storage inspect(@PathVariable long connectionId,
            @RequestParam(required=false) String schema, @RequestParam String table) throws Exception {
        access.require(connectionId, ConnectionPermission.QUERY);
        return service.inspect(connectionId, schema, table);
    }
}
