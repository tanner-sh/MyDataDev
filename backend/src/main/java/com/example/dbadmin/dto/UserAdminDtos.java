package com.example.dbadmin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public final class UserAdminDtos {
    private UserAdminDtos() {}

    public record UserResponse(
            long id,
            String provider,
            String username,
            String displayName,
            String role,
            boolean enabled,
            Instant lastLoginAt,
            Instant createdAt,
            Instant updatedAt
    ) {}

    public record UserCreateRequest(
            @NotBlank @Size(max = 120) @Pattern(regexp = "[A-Za-z0-9._-]+", message = "用户名仅支持字母、数字、点、下划线和连字符") String username,
            @NotBlank @Size(max = 120) String displayName,
            @NotBlank @Size(max = 20) String role,
            @NotBlank @Size(min = 12, max = 1_000) String password,
            @NotNull Boolean enabled
    ) {}

    public record UserUpdateRequest(
            @NotBlank @Size(max = 120) @Pattern(regexp = "[A-Za-z0-9._-]+", message = "用户名仅支持字母、数字、点、下划线和连字符") String username,
            @NotBlank @Size(max = 120) String displayName,
            @NotBlank @Size(max = 20) String role,
            @Size(min = 12, max = 1_000) String password,
            @NotNull Boolean enabled
    ) {}
}
