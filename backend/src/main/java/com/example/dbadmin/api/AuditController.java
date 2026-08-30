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

    @GetMapping("/chain")
    public com.example.dbadmin.repo.AuditRepository.ChainVerification chain() {
        return service.verifyChain();
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
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"mydatadev-audit.csv\"")
                .body(service.exportCsv(actor, action, connectionId, keyword, from, to, exportActor));
    }

    /** 列表里的 detail 会被截断，这里返回单条记录的完整内容。 */
    @GetMapping("/{id}/detail")
    public Map<String, String> detail(@PathVariable long id) {
        return Map.of("detail", service.detail(id));
    }
}
