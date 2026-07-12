package com.example.dbadmin.api;

import com.example.dbadmin.dto.ApiDtos.ConnectionRequest;
import com.example.dbadmin.dto.ApiDtos.ConnectionResponse;
import com.example.dbadmin.dto.ApiDtos.MessageResponse;
import com.example.dbadmin.dto.ApiDtos.TestConnectionRequest;
import com.example.dbadmin.service.ConnectionService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/connections")
public class ConnectionController {
    private final ConnectionService service;

    public ConnectionController(ConnectionService service) {
        this.service = service;
    }

    @GetMapping
    public List<ConnectionResponse> list() {
        return service.list();
    }

    @PostMapping
    public ConnectionResponse create(@Valid @RequestBody ConnectionRequest request, @RequestHeader(value = "X-User", required = false) String actor) {
        return service.create(request, actor);
    }

    @PutMapping("/{id}")
    public ConnectionResponse update(@PathVariable long id, @Valid @RequestBody ConnectionRequest request, @RequestHeader(value = "X-User", required = false) String actor) {
        return service.update(id, request, actor);
    }

    @DeleteMapping("/{id}")
    public MessageResponse delete(@PathVariable long id, @RequestHeader(value = "X-User", required = false) String actor) {
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
        service.testExisting(id, request);
        return new MessageResponse(true, "connection ok");
    }
}
