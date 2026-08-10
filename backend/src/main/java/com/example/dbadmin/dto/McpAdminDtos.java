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

    public record McpAgentCreateRequest(
            @NotBlank @Size(max = 64) String agentId,
            @NotNull @Size(min = 1, max = 200) List<Long> connectionIds,
            boolean allowProduction
    ) {
    }

    public record McpAgentUpdateRequest(
            boolean enabled,
            @NotNull @Size(min = 1, max = 200) List<Long> connectionIds,
            boolean allowProduction
    ) {
    }

    public record McpAgentResponse(
            long id,
            String agentId,
            boolean enabled,
            boolean allowProduction,
            List<Long> connectionIds,
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
