package com.example.dbadmin.api;

import com.example.dbadmin.access.ConnectionAccessService;
import com.example.dbadmin.access.ConnectionPermission;
import com.example.dbadmin.dto.ApiDtos.ConnectionRequest;
import com.example.dbadmin.dto.ApiDtos.ConnectionResponse;
import com.example.dbadmin.dto.ApiDtos.MessageResponse;
import com.example.dbadmin.dto.ApiDtos.TestConnectionRequest;
import com.example.dbadmin.service.ConnectionService;
import jakarta.validation.Valid;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/connections")
public class ConnectionController {
    private final ConnectionService service;
    private final ConnectionAccessService access;

    public ConnectionController(ConnectionService service, ConnectionAccessService access) {
        this.service = service;
        this.access = access;
    }

    @GetMapping
    public List<ConnectionResponse> list() {
        return access.visibleConnections(service.list());
    }

    @PostMapping
    @Transactional
    public ConnectionResponse create(@Valid @RequestBody ConnectionRequest request, @RequestHeader(value = "X-User", required = false) String actor) {
        ConnectionResponse created = service.create(request, actor);
        access.initializeNewConnection(created.id());
        return created;
    }

    @PutMapping("/{id}")
    public ConnectionResponse update(@PathVariable long id, @Valid @RequestBody ConnectionRequest request, @RequestHeader(value = "X-User", required = false) String actor) {
        access.require(id, ConnectionPermission.CONNECTION_ADMIN);
        return service.update(id, request, actor);
    }

    @DeleteMapping("/{id}")
    public MessageResponse delete(@PathVariable long id, @RequestHeader(value = "X-User", required = false) String actor) {
        access.require(id, ConnectionPermission.CONNECTION_ADMIN);
        service.delete(id, actor);
        return new MessageResponse(true, "deleted");
    }

    @PostMapping("/test")
    public MessageResponse test(@Valid @RequestBody TestConnectionRequest request) throws Exception {
        service.test(request);
        return new MessageResponse(true, "connection ok");
    }

    @PostMapping("/{id}/test")
    public MessageResponse testExisting(@PathVariable long id, @Valid @RequestBody(required = false) ConnectionRequest request) throws Exception {
        access.require(id, ConnectionPermission.CONNECTION_ADMIN);
        service.testExisting(id, request);
        return new MessageResponse(true, "connection ok");
    }
}
