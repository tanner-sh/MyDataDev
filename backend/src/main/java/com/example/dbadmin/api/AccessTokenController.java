package com.example.dbadmin.api;

import com.example.dbadmin.auth.AccessTokenService;
import com.example.dbadmin.auth.WebIdentity;
import com.example.dbadmin.auth.WebIdentityContext;
import com.example.dbadmin.dto.AccessTokenDtos.AccessTokenResponse;
import com.example.dbadmin.dto.AccessTokenDtos.CreateAccessTokenRequest;
import com.example.dbadmin.dto.AccessTokenDtos.CreatedAccessTokenResponse;
import com.example.dbadmin.dto.AccessTokenDtos.TokenConnectionOption;
import com.example.dbadmin.dto.AccessTokenDtos.UpdateAccessTokenConnectionsRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
public class AccessTokenController {
    private final AccessTokenService service;

    public AccessTokenController(AccessTokenService service) {
        this.service = service;
    }

    @GetMapping("/api/auth/tokens")
    public List<AccessTokenResponse> listOwn(Authentication authentication) {
        return service.listForUser(requireSessionIdentity(authentication));
    }

    @GetMapping("/api/auth/tokens/connections")
    public List<TokenConnectionOption> availableConnections(Authentication authentication) {
        return service.availableConnections(requireSessionIdentity(authentication));
    }

    @PostMapping("/api/auth/tokens")
    public CreatedAccessTokenResponse create(
            @Valid @RequestBody CreateAccessTokenRequest request,
            Authentication authentication
    ) {
        return service.create(request, requireSessionIdentity(authentication));
    }

    @PutMapping("/api/auth/tokens/{id}/connections")
    public AccessTokenResponse updateConnections(
            @PathVariable String id,
            @Valid @RequestBody UpdateAccessTokenConnectionsRequest request,
            Authentication authentication
    ) {
        return service.updateConnections(id, request, requireSessionIdentity(authentication));
    }

    @DeleteMapping("/api/auth/tokens/{id}")
    public Map<String, Object> revokeOwn(@PathVariable String id, Authentication authentication) {
        service.revokeOwn(id, requireSessionIdentity(authentication));
        return Map.of("ok", true);
    }

    @GetMapping("/api/admin/tokens")
    public List<AccessTokenResponse> listAll(Authentication authentication) {
        requireAdminSession(authentication);
        return service.listAll();
    }

    @DeleteMapping("/api/admin/tokens/{id}")
    public Map<String, Object> revokeByAdmin(@PathVariable String id, Authentication authentication) {
        service.revokeByAdmin(id, requireAdminSession(authentication));
        return Map.of("ok", true);
    }

    private WebIdentity requireSessionIdentity(Authentication authentication) {
        if (WebIdentityContext.isAccessTokenRequest()) {
            throw new ApiProblemException(HttpStatus.FORBIDDEN, "SESSION_REQUIRED", "访问令牌管理仅支持通过 Web 会话操作。");
        }
        if (authentication == null || !(authentication.getPrincipal() instanceof WebIdentity identity)) {
            throw new ApiProblemException(HttpStatus.CONFLICT, "AUTH_DISABLED", "服务端未启用用户认证，无法管理访问令牌。");
        }
        return identity;
    }

    private WebIdentity requireAdminSession(Authentication authentication) {
        WebIdentity identity = requireSessionIdentity(authentication);
        if (!"ADMIN".equals(identity.role())) {
            throw new ApiProblemException(HttpStatus.FORBIDDEN, "ADMIN_REQUIRED", "只有管理员可以管理全部访问令牌。");
        }
        return identity;
    }
}
