package com.example.dbadmin.api;

import com.example.dbadmin.auth.UserAccountService;
import com.example.dbadmin.auth.WebIdentity;
import com.example.dbadmin.dto.UserAdminDtos.UserCreateRequest;
import com.example.dbadmin.dto.UserAdminDtos.UserResponse;
import com.example.dbadmin.dto.UserAdminDtos.UserUpdateRequest;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/users")
public class UserAdminController {
    private final UserAccountService service;

    public UserAdminController(UserAccountService service) {
        this.service = service;
    }

    @GetMapping
    public List<UserResponse> list(Authentication authentication) {
        requireAdministrator(authentication);
        return service.list();
    }

    @PostMapping
    public UserResponse create(@Valid @RequestBody UserCreateRequest request, Authentication authentication) {
        return service.create(request, requireAdministrator(authentication));
    }

    @PutMapping("/{id}")
    public UserResponse update(
            @PathVariable long id,
            @Valid @RequestBody UserUpdateRequest request,
            Authentication authentication
    ) {
        return service.update(id, request, requireAdministrator(authentication));
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable long id, Authentication authentication) {
        service.delete(id, requireAdministrator(authentication));
        return Map.of("ok", true);
    }

    private WebIdentity requireAdministrator(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof WebIdentity identity)
                || !"ADMIN".equals(identity.role())) {
            throw new ApiProblemException(HttpStatus.FORBIDDEN, "ADMIN_REQUIRED", "只有管理员可以管理用户账号。");
        }
        return identity;
    }
}
