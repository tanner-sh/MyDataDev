package com.example.dbadmin.api;

import com.example.dbadmin.access.ConnectionAccessService;
import com.example.dbadmin.access.ConnectionPermission;
import com.example.dbadmin.dto.ApiDtos.SchemaDriftResponse;
import com.example.dbadmin.dto.ApiDtos.SchemaSnapshotCaptureResponse;
import com.example.dbadmin.dto.ApiDtos.SchemaSnapshotSummary;
import com.example.dbadmin.dto.ApiDtos.SchemaSnapshotTargetRequest;
import com.example.dbadmin.model.SchemaSnapshotTarget;
import com.example.dbadmin.service.SchemaSnapshotService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 结构快照与漂移追踪。
 *
 * <p>全部只读：采集读的是元数据，漂移比的是两份记录，任何一条都不会改目标库。因此权限门槛是
 * VIEW_METADATA —— 与结构对比同档。</p>
 */
@RestController
@RequestMapping("/api/schema-snapshots")
public class SchemaSnapshotController {
    private final SchemaSnapshotService service;
    private final ConnectionAccessService access;

    public SchemaSnapshotController(SchemaSnapshotService service, ConnectionAccessService access) {
        this.service = service;
        this.access = access;
    }

    @PostMapping("/capture")
    public SchemaSnapshotCaptureResponse capture(
            @RequestParam long connectionId,
            @RequestParam(required = false) String schemaName,
            @RequestParam(required = false) String label,
            @RequestHeader(value = "X-User", required = false) String actor
    ) throws Exception {
        access.require(connectionId, ConnectionPermission.VIEW_METADATA);
        return service.capture(connectionId, schemaName, label, actor);
    }

    @GetMapping
    public List<SchemaSnapshotSummary> timeline(
            @RequestParam long connectionId,
            @RequestParam(required = false) String schemaName,
            @RequestParam(required = false) Integer limit
    ) throws Exception {
        access.require(connectionId, ConnectionPermission.VIEW_METADATA);
        return service.timeline(connectionId, schemaName, limit);
    }

    /**
     * 两个时间点之间的结构漂移。
     *
     * <p>{@code targetSnapshotId} 不传表示与目标库当前的结构比 —— 「上周到现在有没有人动过
     * 结构」是这个功能最常被问的一句。</p>
     */
    @GetMapping("/{baselineId}/drift")
    public SchemaDriftResponse drift(
            @PathVariable long baselineId,
            @RequestParam(required = false) Long targetSnapshotId,
            @RequestHeader(value = "X-User", required = false) String actor
    ) throws Exception {
        // 先按快照归属鉴权，再开始比较：反过来的话，没有权限的人照样触发了一次全 Schema
        // 的元数据读取和一条审计，只是拿不到结果。
        access.require(service.connectionIdOf(baselineId), ConnectionPermission.VIEW_METADATA);
        if (targetSnapshotId != null) {
            access.require(service.connectionIdOf(targetSnapshotId), ConnectionPermission.VIEW_METADATA);
        }
        return service.drift(baselineId, targetSnapshotId, actor);
    }

    @GetMapping("/targets")
    public List<SchemaSnapshotTarget> targets(@RequestParam long connectionId) {
        access.require(connectionId, ConnectionPermission.VIEW_METADATA);
        return service.targets(connectionId);
    }

    @PostMapping("/targets")
    public SchemaSnapshotTarget saveTarget(@Valid @RequestBody SchemaSnapshotTargetRequest request,
                                           @RequestHeader(value = "X-User", required = false) String actor) throws Exception {
        access.require(request.connectionId(), ConnectionPermission.VIEW_METADATA);
        return service.saveTarget(request, actor);
    }

    @DeleteMapping("/targets/{id}")
    public void deleteTarget(@PathVariable long id,
                             @RequestHeader(value = "X-User", required = false) String actor) {
        access.require(service.targetConnectionId(id), ConnectionPermission.VIEW_METADATA);
        service.deleteTarget(id, actor);
    }
}
