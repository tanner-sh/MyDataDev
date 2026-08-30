package com.example.dbadmin.api;

import com.example.dbadmin.dto.ConfigArchiveDtos.ArchiveEnvelope;
import com.example.dbadmin.dto.ConfigArchiveDtos.ArchiveExportRequest;
import com.example.dbadmin.dto.ConfigArchiveDtos.ArchiveImportRequest;
import com.example.dbadmin.dto.ConfigArchiveDtos.ArchiveImportResult;
import com.example.dbadmin.service.ConnectionArchiveService;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 连接配置的加密导出与导入。
 *
 * <p>挂在 {@code /api/admin} 下以继承 ADMIN 鉴权：归档里装着全部连接的明文密码与 SSH 密钥，
 * 不该是任何普通操作员能触发的操作。桌面模式关闭了认证，这里与其他管理接口一样放行。</p>
 */
@RestController
@RequestMapping("/api/admin/connections/archive")
public class ConnectionArchiveController {
    private final ConnectionArchiveService service;

    public ConnectionArchiveController(ConnectionArchiveService service) {
        this.service = service;
    }

    @PostMapping("/export")
    public ResponseEntity<ArchiveEnvelope> export(
            @Valid @RequestBody ArchiveExportRequest request,
            @RequestHeader(value = "X-User", required = false) String actor
    ) {
        // 响应体是加密后的凭据，任何一层缓存都不该留下它。
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(service.export(request, actor));
    }

    @PostMapping("/import")
    public ArchiveImportResult importArchive(
            @Valid @RequestBody ArchiveImportRequest request,
            @RequestHeader(value = "X-User", required = false) String actor
    ) {
        return service.importArchive(request, actor);
    }
}
