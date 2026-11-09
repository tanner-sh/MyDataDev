package com.example.dbadmin.mcp;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record McpRuntimeConfig(
        Settings settings,
        Set<String> allowedOrigins,
        Map<String, Agent> agents
) {
    public McpRuntimeConfig {
        allowedOrigins = allowedOrigins == null ? Set.of() : Set.copyOf(allowedOrigins);
        agents = agents == null ? Map.of() : Map.copyOf(agents);
    }

    public record Settings(
            boolean enabled,
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
            int sessionTtlMinutes,
            int maxScriptsPerConnection,
            /** db_execute 的语句超时；db_query / db_explain / db_export_query 用 queryTimeoutSeconds。 */
            int executeTimeoutSeconds,
            /**
             * db_export_query 单个文件的上限（KB）。文件整份进工具响应、进模型上下文：1 MB 的 CSV
             * 已是几十万 token，再大模型也读不完，所以默认 1 MB，管理员可按客户端的能力调。
             */
            int exportMaxKilobytes
    ) {
        public static final int DEFAULT_EXPORT_MAX_KILOBYTES = 1024;

        /** 加这一列之前的构造方式：导出上限取默认值。 */
        public Settings(boolean enabled, int defaultQueryRows, int maxQueryRows, int maxResultCells, long maxResultTextChars,
                        int maxCellTextChars, int maxSqlChars, int queryTimeoutSeconds, int metadataPageSize, int maxMetadataPageSize,
                        int tablePageSize, int maxTablePageSize, int sessionTtlMinutes, int maxScriptsPerConnection, int executeTimeoutSeconds) {
            this(enabled, defaultQueryRows, maxQueryRows, maxResultCells, maxResultTextChars, maxCellTextChars, maxSqlChars,
                    queryTimeoutSeconds, metadataPageSize, maxMetadataPageSize, tablePageSize, maxTablePageSize, sessionTtlMinutes,
                    maxScriptsPerConnection, executeTimeoutSeconds, DEFAULT_EXPORT_MAX_KILOBYTES);
        }
    }

    public record Agent(
            long id,
            String agentId,
            String keyHash,
            boolean enabled,
            boolean allowProduction,
            Map<Long, McpAccessLevel> connectionLevels,
            Instant createdAt,
            Instant updatedAt
    ) {
        public Agent {
            connectionLevels = connectionLevels == null ? Map.of() : Map.copyOf(connectionLevels);
        }

        public Set<Long> connectionIds() {
            return connectionLevels.keySet();
        }
    }

    public List<Agent> agentList() {
        return agents.values().stream()
                .sorted(java.util.Comparator.comparing(Agent::agentId, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }
}
