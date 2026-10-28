package com.example.dbadmin.mcp;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static com.example.dbadmin.mcp.McpSessionOwnershipStore.Access.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class McpSessionOwnershipStoreTest {
    @Test
    void bindsSessionToOneAgentAndRemovesItOnClose() {
        McpSessionOwnershipStore sessions = new McpSessionOwnershipStore(configuration());

        assertThat(sessions.bind("session-1", "agent-a")).isTrue();
        assertThat(sessions.checkAccess("session-1", "agent-a")).isEqualTo(ALLOWED);
        assertThat(sessions.checkAccess("session-1", "agent-b")).isEqualTo(FORBIDDEN);
        assertThat(sessions.bind("session-1", "agent-b")).isFalse();

        sessions.remove("session-1", "agent-b");
        assertThat(sessions.checkAccess("session-1", "agent-a")).isEqualTo(ALLOWED);
        sessions.remove("session-1", "agent-a");
        assertThat(sessions.checkAccess("session-1", "agent-a")).isEqualTo(NOT_FOUND);

        sessions.bind("session-2", "agent-a");
        sessions.bind("session-3", "agent-b");
        sessions.removeAgent("agent-a");
        assertThat(sessions.checkAccess("session-2", "agent-a")).isEqualTo(NOT_FOUND);
        assertThat(sessions.checkAccess("session-3", "agent-b")).isEqualTo(ALLOWED);
    }

    @Test
    void expiresAtTheIdleDeadlineAndDoesNotReviveTheOldSession() {
        var clock = mock(Clock.class);
        when(clock.instant()).thenReturn(Instant.EPOCH);
        var sessions = new McpSessionOwnershipStore(configuration(), clock);
        sessions.bind("session-1", "agent-a");

        when(clock.instant()).thenReturn(Instant.EPOCH.plus(5, ChronoUnit.MINUTES));
        assertThat(sessions.checkAccess("session-1", "agent-a")).isEqualTo(NOT_FOUND);
        assertThat(sessions.checkAccess("session-1", "agent-a")).isEqualTo(NOT_FOUND);
        assertThat(sessions.checkAccess("unknown-session", "agent-a")).isEqualTo(NOT_FOUND);
        assertThat(sessions.bind("new-session", "agent-a")).isTrue();
        assertThat(sessions.checkAccess("new-session", "agent-a")).isEqualTo(ALLOWED);
    }

    @Test
    void onlyTheOwnerCanExtendTheIdleDeadline() {
        var clock = mock(Clock.class);
        when(clock.instant()).thenReturn(Instant.EPOCH);
        var sessions = new McpSessionOwnershipStore(configuration(), clock);
        sessions.bind("active-session", "agent-a");
        sessions.bind("idle-session", "agent-a");

        when(clock.instant()).thenReturn(Instant.EPOCH.plus(4, ChronoUnit.MINUTES));
        assertThat(sessions.checkAccess("active-session", "agent-a")).isEqualTo(ALLOWED);
        assertThat(sessions.checkAccess("idle-session", "agent-b")).isEqualTo(FORBIDDEN);
        assertThat(sessions.bind("idle-session", "agent-b")).isFalse();

        when(clock.instant()).thenReturn(Instant.EPOCH.plus(5, ChronoUnit.MINUTES));
        assertThat(sessions.checkAccess("active-session", "agent-a")).isEqualTo(ALLOWED);
        assertThat(sessions.checkAccess("idle-session", "agent-a")).isEqualTo(NOT_FOUND);
        sessions.clear();
        assertThat(sessions.checkAccess("active-session", "agent-a")).isEqualTo(NOT_FOUND);
    }

    private McpConfigurationService configuration() {
        McpConfigurationService configuration = mock(McpConfigurationService.class);
        when(configuration.snapshot()).thenReturn(new McpRuntimeConfig(
                new McpRuntimeConfig.Settings(true, 100, 1_000, 50_000, 2_000_000,
                        20_000, 100_000, 30, 100, 500, 100, 500, 5, 1, 60),
                Set.of(), Map.of()
        ));
        return configuration;
    }
}
