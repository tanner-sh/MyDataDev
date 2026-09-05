package com.example.dbadmin.api;

import com.example.dbadmin.access.ConnectionAccessService;
import com.example.dbadmin.access.ConnectionPermission;
import com.example.dbadmin.dto.ApiDtos.ConnectionPoolOverview;
import com.example.dbadmin.dto.ApiDtos.ConnectionPoolStatus;
import com.example.dbadmin.dto.ApiDtos.ConnectionRequest;
import com.example.dbadmin.dto.ApiDtos.ConnectionResponse;
import com.example.dbadmin.dto.ApiDtos.MessageResponse;
import com.example.dbadmin.dto.ApiDtos.TestConnectionRequest;
import com.example.dbadmin.service.ConnectionService;
import jakarta.validation.Valid;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

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

    /**
     * 远程连接池的现状。
     *
     * <p>池此前只在报出 REMOTE_POOL_EXHAUSTED 的那一刻才「被看见」，而那条报错既说不出名额
     * 被谁占着，也说不出哪个早就闲了。只返回调用方本来就能看到的连接 —— 池的存在本身也是
     * 「这台机器上有哪些库」的信息。</p>
     */
    @GetMapping("/pools")
    public ConnectionPoolOverview pools() {
        Map<Long, String> visible = access.visibleConnections(service.list()).stream()
                .collect(java.util.stream.Collectors.toMap(ConnectionResponse::id, ConnectionResponse::name));
        List<ConnectionPoolStatus> pools = service.poolSnapshot().stream()
                .filter(pool -> visible.containsKey(pool.connectionId()))
                .map(pool -> new ConnectionPoolStatus(pool.connectionId(), visible.get(pool.connectionId()),
                        pool.total(), pool.active(), pool.idle(), pool.waiting(), pool.maxPoolSize(),
                        pool.pendingBorrows(), pool.idleMillis(), pool.tunnelAlive()))
                .toList();
        return new ConnectionPoolOverview(pools, service.poolCapacity());
    }

    /** 手动关掉一条连接的池；下一次请求会重新建。与改连接配置走同一条淘汰路径。 */
    @DeleteMapping("/{id}/pool")
    public MessageResponse closePool(@PathVariable long id, @RequestHeader(value = "X-User", required = false) String actor) {
        access.require(id, ConnectionPermission.CONNECTION_ADMIN);
        service.closePool(id, actor);
        return new MessageResponse(true, "pool closed");
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
