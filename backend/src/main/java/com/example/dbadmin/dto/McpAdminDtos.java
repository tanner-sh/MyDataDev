package com.example.dbadmin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public final class McpAdminDtos {
    private McpAdminDtos() {
    }

    public record McpConfigResponse(
            boolean enabled,
            String endpointPath,
            List<String> allowedOrigins,
            McpLimits limits,
            List<McpAgentResponse> agents,
            List<McpConnectionOption> connections
    ) {
    }

    public record McpLimits(
            int defaultQueryRows,
            int maxQueryRows,
            int maxResultCells,
            long maxResultTextChars,
            int maxCellTextChars,
            int maxSqlChars,
            int queryTimeoutSeconds,
            int metadataPageSize,
            int maxMetadataPageSize,
            int tablePageSize,
            int maxTablePageSize,
            int sessionTtlMinutes
    ) {
    }

    public record McpConfigUpdateRequest(
            @NotNull @Size(max = 100) List<@NotBlank @Size(max = 500) String> allowedOrigins,
            @NotNull McpLimits limits
    ) {
    }

    public record McpStatusUpdateRequest(boolean enabled) {
    }

    /** 一条连接授权：授权本身与它的访问档位是一体的，分开传会出现「授了连接没档位」的中间态。 */
    public record McpAgentGrant(
            @NotNull Long connectionId,
            @NotBlank @Size(max = 20) String accessLevel
    ) {
    }

    public record McpAgentCreateRequest(
            @NotBlank @Size(max = 64) String agentId,
            @NotNull @Size(min = 1, max = 200) List<@NotNull McpAgentGrant> grants,
            boolean allowProduction
    ) {
    }

    public record McpAgentUpdateRequest(
            boolean enabled,
            @NotNull @Size(min = 1, max = 200) List<@NotNull McpAgentGrant> grants,
            boolean allowProduction
    ) {
    }

    public record McpAgentResponse(
            long id,
            String agentId,
            boolean enabled,
            boolean allowProduction,
            List<McpAgentGrant> grants,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    public record McpCredentialResponse(McpAgentResponse agent, String credential) {
    }

    public record McpConnectionOption(
            long id,
            String name,
            String dbType,
            String environment,
            boolean readonly
    ) {
    }
}
