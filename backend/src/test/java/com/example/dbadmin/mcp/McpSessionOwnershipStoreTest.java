package com.example.dbadmin.mcp;

import com.example.dbadmin.config.AppProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class McpSessionOwnershipStoreTest {
    @Test
    void bindsSessionToOneAgentAndRemovesItOnClose() {
        AppProperties properties = new AppProperties();
        properties.getMcp().setSessionTtlMinutes(5);
        McpSessionOwnershipStore sessions = new McpSessionOwnershipStore(properties);

        assertThat(sessions.bind("session-1", "agent-a")).isTrue();
        assertThat(sessions.belongsTo("session-1", "agent-a")).isTrue();
        assertThat(sessions.belongsTo("session-1", "agent-b")).isFalse();
        assertThat(sessions.bind("session-1", "agent-b")).isFalse();

        sessions.remove("session-1", "agent-b");
        assertThat(sessions.belongsTo("session-1", "agent-a")).isTrue();
        sessions.remove("session-1", "agent-a");
        assertThat(sessions.belongsTo("session-1", "agent-a")).isFalse();
    }
}
