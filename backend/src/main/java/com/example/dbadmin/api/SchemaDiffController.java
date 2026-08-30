package com.example.dbadmin.api;

import com.example.dbadmin.access.ConnectionAccessService;
import com.example.dbadmin.access.ConnectionPermission;
import com.example.dbadmin.dto.ApiDtos.SchemaDiffRequest;
import com.example.dbadmin.dto.ApiDtos.SchemaDiffResponse;
import com.example.dbadmin.service.SchemaDiffService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/schema-diff")
public class SchemaDiffController {
    private final SchemaDiffService service;
    private final ConnectionAccessService access;

    public SchemaDiffController(SchemaDiffService service, ConnectionAccessService access) {
        this.service = service;
        this.access = access;
    }

    /**
     * 用 POST 而不是 GET：请求体里可能带上百个表名，而且这是一次代价不小的读操作，
     * 不适合被浏览器或中间层当成可缓存的 GET。
     */
    @PostMapping
    public SchemaDiffResponse compare(@Valid @RequestBody SchemaDiffRequest request,
                                      @RequestHeader(value = "X-User", required = false) String actor) throws Exception {
        access.require(request.sourceConnectionId(), ConnectionPermission.VIEW_METADATA);
        access.require(request.targetConnectionId(), ConnectionPermission.VIEW_METADATA);
        return service.compare(request, actor);
    }
}
