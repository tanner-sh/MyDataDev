package com.example.dbadmin.api;
import com.example.dbadmin.access.ConnectionAccessService;
import com.example.dbadmin.access.ConnectionPermission;
import com.example.dbadmin.service.DatabaseCapabilityService;
import org.springframework.web.bind.annotation.*;
import java.util.List;
@RestController
@RequestMapping("/api/connections/{id}/capability-report")
public class DatabaseCapabilityController {
    private final DatabaseCapabilityService service;
    private final ConnectionAccessService access;
    public DatabaseCapabilityController(DatabaseCapabilityService service, ConnectionAccessService access) { this.service = service; this.access = access; }
    @GetMapping public DatabaseCapabilityService.Report inspect(@PathVariable long id) throws Exception {
        access.require(id, ConnectionPermission.QUERY);
        return service.inspect(id, access.currentPermissions(List.of(id)).getOrDefault(id, List.of()));
    }
}
