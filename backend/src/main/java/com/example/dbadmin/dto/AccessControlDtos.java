package com.example.dbadmin.dto;

import com.example.dbadmin.access.ConnectionPermission;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Set;

public final class AccessControlDtos {
    private AccessControlDtos() {}

    public record UserGroupResponse(
            long id,
            String name,
            String description,
            List<Long> memberUserIds,
            List<Long> externalMemberUserIds,
            Instant createdAt,
            Instant updatedAt
    ) {}

    public record UserGroupRequest(
            @NotBlank @Size(max = 120) String name,
            @Size(max = 500) String description,
            @NotNull List<@NotNull Long> memberUserIds
    ) {}

    public record ConnectionGrantRequest(
            @NotBlank @Size(max = 20) String granteeType,
            @NotNull Long granteeId,
            @NotNull Set<@NotNull ConnectionPermission> permissions
    ) {}

    public record ConnectionAccessUpdateRequest(
            @NotBlank @Size(max = 20) String accessMode,
            Long ownerUserId,
            @NotNull List<@Valid ConnectionGrantRequest> grants
    ) {}

    public record ConnectionGrantResponse(
            String granteeType,
            long granteeId,
            List<ConnectionPermission> permissions
    ) {}

    public record ConnectionAccessResponse(
            long connectionId,
            String accessMode,
            Long ownerUserId,
            List<ConnectionGrantResponse> grants,
            List<ConnectionPermission> availablePermissions
    ) {}

    public record PermissionTemplateResponse(
            String key,
            String name,
            String description,
            List<ConnectionPermission> permissions
    ) {}
}
