package com.example.dbadmin.api;

import com.example.dbadmin.access.ConnectionAccessService;
import com.example.dbadmin.access.ConnectionPermission;
import com.example.dbadmin.dto.ApiDtos.TableRelationDecisionRequest;
import com.example.dbadmin.dto.ApiDtos.TableRelationsResponse;
import com.example.dbadmin.service.TableRelationService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 表关系（JOIN 补全的知识来源）。
 *
 * <p>读写都要求 QUERY：候选来自这条连接的执行历史（虽然只返回名字对），判断会影响所有人的补全。
 * 不碰目标库，所以不需要写权限；每次判断都写审计。</p>
 */
@RestController
@RequestMapping("/api/metadata/{connectionId}/table-relations")
public class TableRelationController {
    private final TableRelationService service;
    private final ConnectionAccessService access;

    public TableRelationController(TableRelationService service, ConnectionAccessService access) {
        this.service = service;
        this.access = access;
    }

    @GetMapping
    public TableRelationsResponse view(@PathVariable long connectionId, @RequestParam(defaultValue = "false") boolean refresh) {
        access.require(connectionId, ConnectionPermission.QUERY);
        return service.view(connectionId, refresh);
    }

    @PutMapping
    public TableRelationsResponse decide(@PathVariable long connectionId, @Valid @RequestBody TableRelationDecisionRequest request,
                                         @RequestHeader(value = "X-User", required = false) String actor) {
        access.require(connectionId, ConnectionPermission.QUERY);
        return service.decide(connectionId, request, actor);
    }

    @DeleteMapping(consumes = "application/json")
    public TableRelationsResponse remove(@PathVariable long connectionId, @Valid @RequestBody TableRelationDecisionRequest request,
                                         @RequestHeader(value = "X-User", required = false) String actor) {
        access.require(connectionId, ConnectionPermission.QUERY);
        return service.remove(connectionId, request, actor);
    }
}
