package com.example.dbadmin.api;

import com.example.dbadmin.access.ConnectionAccessService;
import com.example.dbadmin.access.ConnectionPermission;
import com.example.dbadmin.auth.WebIdentity;
import com.example.dbadmin.dto.AccessControlDtos.ConnectionAccessResponse;
import com.example.dbadmin.dto.AccessControlDtos.ConnectionAccessUpdateRequest;
import com.example.dbadmin.dto.AccessControlDtos.UserGroupRequest;
import com.example.dbadmin.dto.AccessControlDtos.UserGroupResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

@RestController
public class AccessControlController {
    private final ConnectionAccessService service;

    public AccessControlController(ConnectionAccessService service) {
        this.service = service;
    }

    @GetMapping("/api/access/me")
    public Map<Long, List<ConnectionPermission>> current(@RequestParam List<Long> connectionIds) {
        return service.currentPermissions(connectionIds);
    }

    @GetMapping("/api/admin/access/groups")
    public List<UserGroupResponse> groups(Authentication authentication) {
        requireAdministrator(authentication);
        return service.groups();
    }

    @PostMapping("/api/admin/access/groups")
    public UserGroupResponse createGroup(@Valid @RequestBody UserGroupRequest request, Authentication authentication) {
        return service.createGroup(request, requireAdministrator(authentication));
    }

    @PutMapping("/api/admin/access/groups/{id}")
    public UserGroupResponse updateGroup(
            @PathVariable long id,
            @Valid @RequestBody UserGroupRequest request,
            Authentication authentication
    ) {
        return service.updateGroup(id, request, requireAdministrator(authentication));
    }

    @DeleteMapping("/api/admin/access/groups/{id}")
    public Map<String, Object> deleteGroup(@PathVariable long id, Authentication authentication) {
        service.deleteGroup(id, requireAdministrator(authentication));
        return Map.of("ok", true);
    }

    @GetMapping("/api/admin/access/connections/{connectionId}")
    public ConnectionAccessResponse policy(@PathVariable long connectionId, Authentication authentication) {
        requireAdministrator(authentication);
        return service.policy(connectionId);
    }

    @PutMapping("/api/admin/access/connections/{connectionId}")
    public ConnectionAccessResponse updatePolicy(
            @PathVariable long connectionId,
            @Valid @RequestBody ConnectionAccessUpdateRequest request,
            Authentication authentication
    ) {
        return service.updatePolicy(connectionId, request, requireAdministrator(authentication));
    }

    @GetMapping("/api/admin/access/permissions")
    public List<ConnectionPermission> permissions(Authentication authentication) {
        requireAdministrator(authentication);
        return Arrays.asList(ConnectionPermission.values());
    }

    @GetMapping("/api/admin/access/templates")
    public List<com.example.dbadmin.dto.AccessControlDtos.PermissionTemplateResponse> templates(Authentication authentication) {
        requireAdministrator(authentication);
        return service.permissionTemplates();
    }

    private WebIdentity requireAdministrator(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof WebIdentity identity)
                || !"ADMIN".equals(identity.role())) {
            throw new ApiProblemException(HttpStatus.FORBIDDEN, "ADMIN_REQUIRED", "只有管理员可以管理访问权限。");
        }
        return identity;
    }
}
