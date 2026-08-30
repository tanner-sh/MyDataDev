package com.example.dbadmin.repo;

import com.example.dbadmin.mcp.McpAccessLevel;
import com.example.dbadmin.mcp.McpRuntimeConfig;
import com.example.dbadmin.mcp.McpRuntimeConfig.Agent;
import com.example.dbadmin.mcp.McpRuntimeConfig.Settings;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Repository
public class McpConfigurationRepository {
    private final JdbcTemplate jdbc;

    public McpConfigurationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Settings> findSettings() {
        List<Settings> rows = jdbc.query("SELECT * FROM mcp_settings WHERE id = 1", (rs, ignored) -> new Settings(
                rs.getBoolean("enabled"),
                rs.getInt("default_query_rows"),
                rs.getInt("max_query_rows"),
                rs.getInt("max_result_cells"),
                rs.getLong("max_result_text_chars"),
                rs.getInt("max_cell_text_chars"),
                rs.getInt("max_sql_chars"),
                rs.getInt("query_timeout_seconds"),
                rs.getInt("metadata_page_size"),
                rs.getInt("max_metadata_page_size"),
                rs.getInt("table_page_size"),
                rs.getInt("max_table_page_size"),
                rs.getInt("session_ttl_minutes")
        ));
        return rows.stream().findFirst();
    }

    public void insertSettings(Settings settings) {
        jdbc.update("""
                INSERT INTO mcp_settings(
                    id, enabled, default_query_rows, max_query_rows, max_result_cells,
                    max_result_text_chars, max_cell_text_chars, max_sql_chars, query_timeout_seconds,
                    metadata_page_size, max_metadata_page_size, table_page_size,
                    max_table_page_size, session_ttl_minutes
                ) VALUES (1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                settings.enabled(), settings.defaultQueryRows(), settings.maxQueryRows(), settings.maxResultCells(),
                settings.maxResultTextChars(), settings.maxCellTextChars(), settings.maxSqlChars(), settings.queryTimeoutSeconds(),
                settings.metadataPageSize(), settings.maxMetadataPageSize(), settings.tablePageSize(),
                settings.maxTablePageSize(), settings.sessionTtlMinutes());
    }

    public void updateSettings(Settings settings) {
        jdbc.update("""
                UPDATE mcp_settings SET
                    enabled = ?, default_query_rows = ?, max_query_rows = ?, max_result_cells = ?,
                    max_result_text_chars = ?, max_cell_text_chars = ?, max_sql_chars = ?,
                    query_timeout_seconds = ?, metadata_page_size = ?, max_metadata_page_size = ?,
                    table_page_size = ?, max_table_page_size = ?, session_ttl_minutes = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = 1
                """,
                settings.enabled(), settings.defaultQueryRows(), settings.maxQueryRows(), settings.maxResultCells(),
                settings.maxResultTextChars(), settings.maxCellTextChars(), settings.maxSqlChars(), settings.queryTimeoutSeconds(),
                settings.metadataPageSize(), settings.maxMetadataPageSize(), settings.tablePageSize(),
                settings.maxTablePageSize(), settings.sessionTtlMinutes());
    }

    public void replaceOrigins(Set<String> origins) {
        jdbc.update("DELETE FROM mcp_allowed_origin");
        for (String origin : origins) {
            jdbc.update("INSERT INTO mcp_allowed_origin(origin) VALUES (?)", origin);
        }
    }

    public Set<String> findOrigins() {
        return new LinkedHashSet<>(jdbc.query("SELECT origin FROM mcp_allowed_origin ORDER BY origin", (rs, ignored) -> rs.getString(1)));
    }

    public List<Agent> findAgents() {
        Map<Long, MutableAgent> agents = new LinkedHashMap<>();
        jdbc.query("SELECT * FROM mcp_agent ORDER BY agent_id", rs -> {
            long id = rs.getLong("id");
            agents.put(id, new MutableAgent(
                    id,
                    rs.getString("agent_id"),
                    rs.getString("key_hash"),
                    rs.getBoolean("enabled"),
                    rs.getBoolean("allow_production"),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("updated_at").toInstant()
            ));
        });
        jdbc.query("SELECT agent_id, connection_id, access_level FROM mcp_agent_connection ORDER BY agent_id, connection_id", rs -> {
            MutableAgent agent = agents.get(rs.getLong("agent_id"));
            // 未知档位按只读处理：读到一个不认识的值时降级永远比升级安全。
            if (agent != null) agent.connectionLevels.put(rs.getLong("connection_id"), level(rs.getString("access_level")));
        });
        return agents.values().stream().map(MutableAgent::toRecord).toList();
    }

    private static McpAccessLevel level(String value) {
        try {
            return McpAccessLevel.parse(value);
        } catch (IllegalArgumentException unknown) {
            return McpAccessLevel.READ_ONLY;
        }
    }

    public long insertAgent(String agentId, String keyHash, boolean enabled, boolean allowProduction,
                            Map<Long, McpAccessLevel> connectionLevels) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO mcp_agent(agent_id, key_hash, enabled, allow_production)
                    VALUES (?, ?, ?, ?)
                    """, new String[]{"id"});
            statement.setString(1, agentId);
            statement.setString(2, keyHash);
            statement.setBoolean(3, enabled);
            statement.setBoolean(4, allowProduction);
            return statement;
        }, keys);
        Number id = keys.getKeys() == null ? null : keys.getKeys().entrySet().stream()
                .filter(entry -> "id".equalsIgnoreCase(entry.getKey()))
                .map(Map.Entry::getValue)
                .filter(Number.class::isInstance)
                .map(Number.class::cast)
                .findFirst()
                .orElse(null);
        if (id == null) throw new IllegalStateException("无法获取新建 MCP Agent ID");
        replaceAgentConnections(id.longValue(), connectionLevels);
        return id.longValue();
    }

    public void updateAgent(long id, boolean enabled, boolean allowProduction, Map<Long, McpAccessLevel> connectionLevels) {
        int updated = jdbc.update("""
                UPDATE mcp_agent SET enabled = ?, allow_production = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?
                """, enabled, allowProduction, id);
        if (updated == 0) throw new IllegalArgumentException("MCP Agent 不存在");
        replaceAgentConnections(id, connectionLevels);
    }

    public void updateAgentKey(long id, String keyHash) {
        int updated = jdbc.update("UPDATE mcp_agent SET key_hash = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?", keyHash, id);
        if (updated == 0) throw new IllegalArgumentException("MCP Agent 不存在");
    }

    public void deleteAgent(long id) {
        if (jdbc.update("DELETE FROM mcp_agent WHERE id = ?", id) == 0) {
            throw new IllegalArgumentException("MCP Agent 不存在");
        }
    }

    private void replaceAgentConnections(long agentId, Map<Long, McpAccessLevel> connectionLevels) {
        jdbc.update("DELETE FROM mcp_agent_connection WHERE agent_id = ?", agentId);
        connectionLevels.forEach((connectionId, level) -> jdbc.update(
                "INSERT INTO mcp_agent_connection(agent_id, connection_id, access_level) VALUES (?, ?, ?)",
                agentId, connectionId, level.name()));
    }

    private static final class MutableAgent {
        private final long id;
        private final String agentId;
        private final String keyHash;
        private final boolean enabled;
        private final boolean allowProduction;
        private final java.time.Instant createdAt;
        private final java.time.Instant updatedAt;
        private final Map<Long, McpAccessLevel> connectionLevels = new LinkedHashMap<>();

        private MutableAgent(long id, String agentId, String keyHash, boolean enabled, boolean allowProduction,
                             java.time.Instant createdAt, java.time.Instant updatedAt) {
            this.id = id;
            this.agentId = agentId;
            this.keyHash = keyHash;
            this.enabled = enabled;
            this.allowProduction = allowProduction;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
        }

        private Agent toRecord() {
            return new Agent(id, agentId, keyHash, enabled, allowProduction, connectionLevels, createdAt, updatedAt);
        }
    }
}
