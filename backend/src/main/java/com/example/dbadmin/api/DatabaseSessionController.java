package com.example.dbadmin.api;

import com.example.dbadmin.dto.ApiDtos.DatabaseSessionPage;
import com.example.dbadmin.service.DatabaseSessionService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/sessions")
public class DatabaseSessionController {
    private final DatabaseSessionService service;

    public DatabaseSessionController(DatabaseSessionService service) {
        this.service = service;
    }

    @GetMapping
    public DatabaseSessionPage list(@RequestParam long connectionId) throws Exception {
        return service.list(connectionId);
    }

    @PostMapping("/{sessionId}/kill")
    public Map<String, Object> kill(
            @PathVariable String sessionId,
            @RequestParam long connectionId,
            @RequestHeader(value = "X-User", required = false) String actor,
            @RequestHeader(value = "X-Production-Confirmation", required = false) String productionConfirmation
    ) throws Exception {
        service.kill(connectionId, sessionId, actor, productionConfirmation);
        return Map.of("ok", true);
    }
}
