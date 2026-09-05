package com.example.dbadmin.api;

import com.example.dbadmin.access.ConnectionAccessService;
import com.example.dbadmin.access.ConnectionPermission;
import com.example.dbadmin.dto.ApiDtos.ScheduledQueryRequest;
import com.example.dbadmin.dto.ApiDtos.ScheduledQueryResponse;
import com.example.dbadmin.model.ScheduledQuery;
import com.example.dbadmin.service.ScheduledQueryService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 定时导出任务。
 *
 * <p>权限要 EXPORT 加 QUERY，与手动导出同档 —— 定时只是把同一件事挪到没人盯着的时候做，
 * 不该因此更容易。</p>
 */
@RestController
@RequestMapping("/api/scheduled-queries")
public class ScheduledQueryController {
    private final ScheduledQueryService service;
    private final ConnectionAccessService access;

    public ScheduledQueryController(ScheduledQueryService service, ConnectionAccessService access) {
        this.service = service;
        this.access = access;
    }

    @GetMapping
    public List<ScheduledQueryResponse> list(@RequestParam(required = false) Long connectionId) {
        if (connectionId != null) access.require(connectionId, ConnectionPermission.QUERY);
        return service.list(connectionId).stream()
                // 不带连接 id 时逐条过一遍权限：列表不该泄露用户没权限看到的连接上有哪些任务。
                .filter(task -> connectionId != null || accessible(task.connectionId()))
                .map(this::response)
                .toList();
    }

    @PostMapping
    public ScheduledQueryResponse create(@Valid @RequestBody ScheduledQueryRequest request,
                                         @RequestHeader(value = "X-User", required = false) String actor) {
        requireExport(request.connectionId());
        return response(service.create(request, actor));
    }

    @PutMapping("/{id}")
    public ScheduledQueryResponse update(@PathVariable long id,
                                         @Valid @RequestBody ScheduledQueryRequest request,
                                         @RequestHeader(value = "X-User", required = false) String actor) {
        requireExport(service.require(id).connectionId());
        requireExport(request.connectionId());
        return response(service.update(id, request, actor));
    }

    @PatchMapping("/{id}/enabled")
    public ScheduledQueryResponse setEnabled(@PathVariable long id,
                                             @RequestParam boolean enabled,
                                             @RequestHeader(value = "X-User", required = false) String actor) {
        requireExport(service.require(id).connectionId());
        return response(service.setEnabled(id, enabled, actor));
    }

    /** 手动跑一次：与到点自动跑走同一条路，结果也记在同一处。 */
    @PostMapping("/{id}/run")
    public ScheduledQueryResponse run(@PathVariable long id,
                                      @RequestHeader(value = "X-User", required = false) String actor) {
        requireExport(service.require(id).connectionId());
        return response(service.run(id, actor));
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable long id, @RequestHeader(value = "X-User", required = false) String actor) {
        requireExport(service.require(id).connectionId());
        service.delete(id, actor);
    }

    private boolean accessible(long connectionId) {
        try {
            access.require(connectionId, ConnectionPermission.QUERY);
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private void requireExport(long connectionId) {
        access.require(connectionId, ConnectionPermission.EXPORT);
        access.require(connectionId, ConnectionPermission.QUERY);
    }

    private ScheduledQueryResponse response(ScheduledQuery task) {
        return new ScheduledQueryResponse(task, service.nextRunAt(task));
    }
}
