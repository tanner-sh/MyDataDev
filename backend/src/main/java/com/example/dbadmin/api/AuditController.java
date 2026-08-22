package com.example.dbadmin.api;

import com.example.dbadmin.dto.ApiDtos.AuditEventPage;
import com.example.dbadmin.dto.ApiDtos.AuditFacets;
import com.example.dbadmin.service.AuditService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/audit")
public class AuditController {
    private final AuditService service;

    public AuditController(AuditService service) {
        this.service = service;
    }

    @GetMapping
    public AuditEventPage list(
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) Long connectionId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize
    ) {
        return service.list(actor, action, connectionId, keyword, from, to, page, pageSize);
    }

    @GetMapping("/facets")
    public AuditFacets facets() {
        return service.facets();
    }

    /** 列表里的 detail 会被截断，这里返回单条记录的完整内容。 */
    @GetMapping("/{id}/detail")
    public Map<String, String> detail(@PathVariable long id) {
        return Map.of("detail", service.detail(id));
    }
}
