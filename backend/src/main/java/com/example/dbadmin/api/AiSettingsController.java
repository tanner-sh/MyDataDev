package com.example.dbadmin.api;

import com.example.dbadmin.dto.AiDtos.AiConnectionPolicyRequest;
import com.example.dbadmin.dto.AiDtos.AiConnectionPolicyResponse;
import com.example.dbadmin.dto.AiDtos.AiProbeResponse;
import com.example.dbadmin.dto.AiDtos.AiSettingsResponse;
import com.example.dbadmin.dto.AiDtos.AiSettingsUpdateRequest;
import com.example.dbadmin.dto.AiDtos.AiStatusResponse;
import com.example.dbadmin.service.ai.AiSettingsService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/ai")
public class AiSettingsController {
    private final AiSettingsService settings;

    public AiSettingsController(AiSettingsService settings) {
        this.settings = settings;
    }

    /** 所有登录用户都能读：界面据此决定要不要显示 AI 入口。 */
    @GetMapping("/status")
    public AiStatusResponse status() {
        return settings.status();
    }

    @GetMapping("/settings")
    public AiSettingsResponse settings() {
        return settings.settingsResponse();
    }

    @PutMapping("/settings")
    public AiSettingsResponse updateSettings(
            @Valid @RequestBody AiSettingsUpdateRequest request,
            @RequestHeader(value = "X-User", required = false) String actor
    ) {
        return settings.updateSettings(request, actor);
    }

    @PostMapping("/settings/test")
    public AiProbeResponse test(@RequestHeader(value = "X-User", required = false) String actor) {
        return settings.test(actor);
    }

    @GetMapping("/connections")
    public List<AiConnectionPolicyResponse> policies() {
        return settings.policies();
    }

    @PutMapping("/connections/{id}/policy")
    public AiConnectionPolicyResponse updatePolicy(
            @PathVariable long id,
            @Valid @RequestBody AiConnectionPolicyRequest request,
            @RequestHeader(value = "X-User", required = false) String actor
    ) {
        return settings.updatePolicy(id, request, actor);
    }
}
