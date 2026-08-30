package com.example.dbadmin.api;

import com.example.dbadmin.access.ConnectionAccessService;
import com.example.dbadmin.access.ConnectionPermission;
import com.example.dbadmin.dto.ApiDtos.DatabaseSessionPage;
import com.example.dbadmin.service.DatabaseSessionService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/sessions")
public class DatabaseSessionController {
    private final DatabaseSessionService service;
    private final ConnectionAccessService access;

    public DatabaseSessionController(DatabaseSessionService service, ConnectionAccessService access) {
        this.service = service;
        this.access = access;
    }

    @GetMapping
    public DatabaseSessionPage list(@RequestParam long connectionId) throws Exception {
        access.require(connectionId, ConnectionPermission.VIEW_METADATA);
        return service.list(connectionId);
    }

    @PostMapping("/{sessionId}/kill")
    public Map<String, Object> kill(
            @PathVariable String sessionId,
            @RequestParam long connectionId,
            @RequestHeader(value = "X-User", required = false) String actor,
            @RequestHeader(value = "X-Production-Confirmation", required = false) String productionConfirmation
    ) throws Exception {
        access.require(connectionId, ConnectionPermission.CONNECTION_ADMIN);
        service.kill(connectionId, sessionId, actor, productionConfirmation);
        return Map.of("ok", true);
    }
}
