package com.example.dbadmin.api;

import com.example.dbadmin.dto.ApiDtos.MessageResponse;
import com.example.dbadmin.dto.McpAdminDtos.McpAgentCreateRequest;
import com.example.dbadmin.dto.McpAdminDtos.McpAgentResponse;
import com.example.dbadmin.dto.McpAdminDtos.McpAgentUpdateRequest;
import com.example.dbadmin.dto.McpAdminDtos.McpConfigResponse;
import com.example.dbadmin.dto.McpAdminDtos.McpConfigUpdateRequest;
import com.example.dbadmin.dto.McpAdminDtos.McpCredentialResponse;
import com.example.dbadmin.dto.McpAdminDtos.McpStatusUpdateRequest;
import com.example.dbadmin.mcp.McpConfigurationService;
import com.example.dbadmin.mcp.McpSessionOwnershipStore;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/mcp")
public class McpConfigurationController {
    private final McpConfigurationService configuration;
    private final McpSessionOwnershipStore sessions;

    public McpConfigurationController(McpConfigurationService configuration, McpSessionOwnershipStore sessions) {
        this.configuration = configuration;
        this.sessions = sessions;
    }

    @GetMapping("/config")
    public McpConfigResponse config() {
        return configuration.configResponse();
    }

    @PutMapping("/config")
    public McpConfigResponse updateConfig(
            @Valid @RequestBody McpConfigUpdateRequest request,
            @RequestHeader(value = "X-User", required = false) String actor
    ) {
        return configuration.updateConfig(request, actor);
    }

    @PutMapping("/status")
    public McpConfigResponse updateStatus(
            @RequestBody McpStatusUpdateRequest request,
            @RequestHeader(value = "X-User", required = false) String actor
    ) {
        McpConfigResponse response = configuration.updateStatus(request.enabled(), actor);
        if (!request.enabled()) sessions.clear();
        return response;
    }

    @PostMapping("/agents")
    public McpCredentialResponse createAgent(
            @Valid @RequestBody McpAgentCreateRequest request,
            @RequestHeader(value = "X-User", required = false) String actor
    ) {
        return configuration.createAgent(request, actor);
    }

    @PutMapping("/agents/{id}")
    public McpAgentResponse updateAgent(
            @PathVariable long id,
            @Valid @RequestBody McpAgentUpdateRequest request,
            @RequestHeader(value = "X-User", required = false) String actor
    ) {
        McpAgentResponse response = configuration.updateAgent(id, request, actor);
        if (!request.enabled()) sessions.removeAgent(response.agentId());
        return response;
    }

    @PostMapping("/agents/{id}/rotate-key")
    public McpCredentialResponse rotateAgentKey(
            @PathVariable long id,
            @RequestHeader(value = "X-User", required = false) String actor
    ) {
        McpCredentialResponse response = configuration.rotateAgentKey(id, actor);
        sessions.removeAgent(response.agent().agentId());
        return response;
    }

    @DeleteMapping("/agents/{id}")
    public MessageResponse deleteAgent(
            @PathVariable long id,
            @RequestHeader(value = "X-User", required = false) String actor
    ) {
        String agentId = configuration.deleteAgent(id, actor);
        sessions.removeAgent(agentId);
        return new MessageResponse(true, "deleted");
    }
}
