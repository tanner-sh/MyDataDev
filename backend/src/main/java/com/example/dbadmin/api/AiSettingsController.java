package com.example.dbadmin.api;

import com.example.dbadmin.dto.AiDtos.AiConnectionPolicyRequest;
import com.example.dbadmin.dto.AiDtos.AiConnectionPolicyResponse;
import com.example.dbadmin.dto.AiDtos.AiProbeResponse;
import com.example.dbadmin.dto.AiDtos.AiSettingsResponse;
import com.example.dbadmin.dto.AiDtos.AiSettingsUpdateRequest;
import com.example.dbadmin.dto.AiDtos.AiStatusResponse;
import com.example.dbadmin.dto.AiDtos.AiGlossaryEntryResponse;
import com.example.dbadmin.dto.AiDtos.AiGlossarySuggestionsResponse;
import com.example.dbadmin.dto.AiDtos.AiGlossaryUpdateRequest;
import com.example.dbadmin.service.ai.AiGlossaryService;
import com.example.dbadmin.service.ai.AiSettingsService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/ai")
public class AiSettingsController {
    private final AiSettingsService settings;
    private final AiGlossaryService glossary;

    public AiSettingsController(AiSettingsService settings, AiGlossaryService glossary) {
        this.settings = settings;
        this.glossary = glossary;
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

    @GetMapping("/connections/{id}/glossary")
    public List<AiGlossaryEntryResponse> glossary(@PathVariable long id) {
        return glossary.list(id);
    }

    /** 从表注释推候选词条。只读，不落库 —— 管理员挑完之后仍然走 PUT 那条路整体保存。 */
    @GetMapping("/connections/{id}/glossary/suggestions")
    public AiGlossarySuggestionsResponse glossarySuggestions(
            @PathVariable long id,
            @RequestParam(required = false) String schemaName,
            @RequestParam(defaultValue = "30") int limit
    ) throws Exception {
        return glossary.suggest(id, schemaName, limit);
    }

    @PutMapping("/connections/{id}/glossary")
    public List<AiGlossaryEntryResponse> updateGlossary(
            @PathVariable long id,
            @Valid @RequestBody AiGlossaryUpdateRequest request,
            @RequestHeader(value = "X-User", required = false) String actor
    ) {
        return glossary.replace(id, request, actor);
    }
}
