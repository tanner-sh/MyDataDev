package com.example.dbadmin.mcp;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class McpSessionOwnershipStoreTest {
    @Test
    void bindsSessionToOneAgentAndRemovesItOnClose() {
        McpConfigurationService configuration = mock(McpConfigurationService.class);
        when(configuration.snapshot()).thenReturn(new McpRuntimeConfig(
                new McpRuntimeConfig.Settings(true, 100, 1_000, 50_000, 2_000_000,
                        20_000, 100_000, 30, 100, 500, 100, 500, 5),
                Set.of(), Map.of()
        ));
        McpSessionOwnershipStore sessions = new McpSessionOwnershipStore(configuration);

        assertThat(sessions.bind("session-1", "agent-a")).isTrue();
        assertThat(sessions.belongsTo("session-1", "agent-a")).isTrue();
        assertThat(sessions.belongsTo("session-1", "agent-b")).isFalse();
        assertThat(sessions.bind("session-1", "agent-b")).isFalse();

        sessions.remove("session-1", "agent-b");
        assertThat(sessions.belongsTo("session-1", "agent-a")).isTrue();
        sessions.remove("session-1", "agent-a");
        assertThat(sessions.belongsTo("session-1", "agent-a")).isFalse();

        sessions.bind("session-2", "agent-a");
        sessions.bind("session-3", "agent-b");
        sessions.removeAgent("agent-a");
        assertThat(sessions.belongsTo("session-2", "agent-a")).isFalse();
        assertThat(sessions.belongsTo("session-3", "agent-b")).isTrue();
    }
}
