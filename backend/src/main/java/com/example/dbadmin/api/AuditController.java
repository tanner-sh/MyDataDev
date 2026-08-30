package com.example.dbadmin.api;

import com.example.dbadmin.dto.ApiDtos.AuditEventPage;
import com.example.dbadmin.dto.ApiDtos.AuditFacets;
import com.example.dbadmin.service.AuditService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.PostMapping;

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

    /**
     * 校验审计哈希链。表很大时一次验不完，响应里的 complete/nextId 说明进度，
     * 带上 fromId 就能接着往下验。
     */
    @GetMapping("/chain")
    public com.example.dbadmin.repo.AuditRepository.ChainVerification chain(
            @RequestParam(required = false) Long fromId
    ) {
        return service.verifyChain(fromId);
    }

    @GetMapping("/alerts/status")
    public com.example.dbadmin.service.AuditAlertService.Status alertStatus() {
        return service.alertStatus();
    }

    @PostMapping("/alerts/test")
    public Map<String, Object> testAlert(@RequestHeader(value = "X-User", required = false) String actor) {
        service.testAlert(actor);
        return Map.of("ok", true);
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> export(
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) Long connectionId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestHeader(value = "X-User", required = false) String exportActor
    ) {
        var export = service.exportCsv(actor, action, connectionId, keyword, from, to, exportActor);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"mydatadev-audit.csv\"")
                // 截断必须让浏览器端能看见：CSV 本身没地方说「后面还有」。
                .header("X-Audit-Export-Rows", Integer.toString(export.rows()))
                .header("X-Audit-Export-Capped", Boolean.toString(export.capped()))
                .header(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, "X-Audit-Export-Rows, X-Audit-Export-Capped")
                .body(export.content());
    }

    /** 列表里的 detail 会被截断，这里返回单条记录的完整内容。 */
    @GetMapping("/{id}/detail")
    public Map<String, String> detail(@PathVariable long id) {
        return Map.of("detail", service.detail(id));
    }
}
