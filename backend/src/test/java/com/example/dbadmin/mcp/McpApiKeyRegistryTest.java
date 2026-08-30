package com.example.dbadmin.mcp;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class McpApiKeyRegistryTest {
    @Test
    void authenticatesAgentAndReturnsConfiguredPermissions() {
        McpConfigurationService configuration = mock(McpConfigurationService.class);
        McpRuntimeConfig.Agent agent = agent("codex", "secret-value", Map.of(1L, McpAccessLevel.READ_ONLY, 2L, McpAccessLevel.DATA_WRITE), true, true);
        when(configuration.snapshot()).thenReturn(config(Map.of(agent.agentId(), agent)));
        McpApiKeyRegistry registry = new McpApiKeyRegistry(configuration);

        assertThat(registry.authenticate("codex.secret-value"))
                .contains(new McpAgentPrincipal("codex", Map.of(1L, McpAccessLevel.READ_ONLY, 2L, McpAccessLevel.DATA_WRITE), true));
        assertThat(registry.authenticate("codex.wrong-secret")).isEmpty();
        assertThat(registry.authenticate("missing.secret-value")).isEmpty();
        assertThat(registry.authenticate("malformed")).isEmpty();
    }

    @Test
    void readsLatestSnapshotAndRejectsDisabledAgent() {
        McpConfigurationService configuration = mock(McpConfigurationService.class);
        McpRuntimeConfig.Agent enabled = agent("agent", "secret", Map.of(1L, McpAccessLevel.READ_ONLY), false, true);
        McpRuntimeConfig.Agent disabled = agent("agent", "secret", Map.of(1L, McpAccessLevel.READ_ONLY), false, false);
        when(configuration.snapshot()).thenReturn(
                config(Map.of(enabled.agentId(), enabled)),
                config(Map.of(disabled.agentId(), disabled))
        );
        McpApiKeyRegistry registry = new McpApiKeyRegistry(configuration);

        assertThat(registry.authenticate("agent.secret")).isPresent();
        assertThat(registry.authenticate("agent.secret")).isEmpty();
    }

    private McpRuntimeConfig config(Map<String, McpRuntimeConfig.Agent> agents) {
        return new McpRuntimeConfig(settings(), Set.of(), agents);
    }

    private McpRuntimeConfig.Agent agent(String id, String secret, Map<Long, McpAccessLevel> connectionLevels,
                                         boolean allowProduction, boolean enabled) {
        return new McpRuntimeConfig.Agent(
                1L, id, new BCryptPasswordEncoder(4).encode(secret), enabled,
                allowProduction, connectionLevels, Instant.EPOCH, Instant.EPOCH
        );
    }

    private McpRuntimeConfig.Settings settings() {
        return new McpRuntimeConfig.Settings(true, 100, 1_000, 50_000, 2_000_000,
                20_000, 100_000, 30, 100, 500, 100, 500, 30);
    }
}
