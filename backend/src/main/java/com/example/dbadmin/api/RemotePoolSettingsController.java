package com.example.dbadmin.api;

import com.example.dbadmin.dto.ApiDtos.ConnectionPoolSettings;
import com.example.dbadmin.service.RemotePoolSettingsService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 全局设置沿用 /api/admin 的管理员权限及 CSRF 保护。 */
@RestController
@RequestMapping("/api/admin/connection-pool-settings")
public class RemotePoolSettingsController {
    private final RemotePoolSettingsService settings;

    public RemotePoolSettingsController(RemotePoolSettingsService settings) {
        this.settings = settings;
    }

    @GetMapping
    public ConnectionPoolSettings get() {
        return new ConnectionPoolSettings(settings.maximumPoolSize());
    }

    @PutMapping
    public ConnectionPoolSettings update(@Valid @RequestBody ConnectionPoolSettings request,
            @RequestHeader(value = "X-User", required = false) String actor) {
        return new ConnectionPoolSettings(settings.updateMaximumPoolSize(request.maximumPoolSize(), actor));
    }
}
