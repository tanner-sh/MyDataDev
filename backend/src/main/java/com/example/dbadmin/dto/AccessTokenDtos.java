package com.example.dbadmin.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public final class AccessTokenDtos {
    private AccessTokenDtos() {
    }

    public record CreateAccessTokenRequest(
            @NotBlank(message = "令牌名称不能为空")
            @Size(max = 120, message = "令牌名称不能超过 120 个字符")
            String name,
            @Min(value = 1, message = "有效期至少为 1 天")
            @Max(value = 365, message = "有效期不能超过 365 天")
            Integer expiresInDays,
            String connectionScope,
            List<Long> connectionIds
    ) {
    }

    public record UpdateAccessTokenConnectionsRequest(
            @NotBlank(message = "连接范围不能为空")
            String connectionScope,
            List<Long> connectionIds
    ) {
    }

    public record AccessTokenResponse(
            String id,
            long userId,
            String username,
            String displayName,
            String name,
            String connectionScope,
            List<Long> connectionIds,
            Instant expiresAt,
            Instant lastUsedAt,
            String lastUsedIp,
            Instant revokedAt,
            Instant createdAt,
            boolean active
    ) {
    }

    public record CreatedAccessTokenResponse(
            AccessTokenResponse tokenInfo,
            String token
    ) {
    }

    public record TokenConnectionOption(
            long id,
            String name,
            String dbType,
            String environment,
            boolean shared
    ) {
    }

    public record SystemInfoResponse(
            String product,
            String version,
            int apiVersion,
            String mode,
            String authMode
    ) {
    }

    public record WhoAmIResponse(
            boolean authenticated,
            String authType,
            String username,
            String displayName,
            String role,
            String tokenId,
            String tokenName,
            String connectionScope,
            List<Long> allowedConnectionIds
    ) {
    }
}
