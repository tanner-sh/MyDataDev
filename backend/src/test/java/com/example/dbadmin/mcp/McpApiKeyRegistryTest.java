package com.example.dbadmin.mcp;

import com.example.dbadmin.config.AppProperties;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpApiKeyRegistryTest {
    @Test
    void authenticatesAgentAndReturnsConfiguredPermissions() {
        AppProperties properties = properties(agent("codex", "secret-value", List.of(1L, 2L), true));
        McpApiKeyRegistry registry = new McpApiKeyRegistry(properties);

        assertThat(registry.authenticate("codex.secret-value"))
                .contains(new McpAgentPrincipal("codex", java.util.Set.of(1L, 2L), true));
        assertThat(registry.authenticate("codex.wrong-secret")).isEmpty();
        assertThat(registry.authenticate("missing.secret-value")).isEmpty();
        assertThat(registry.authenticate("malformed")).isEmpty();
    }

    @Test
    void rejectsMissingDuplicateOrInvalidAgentConfiguration() {
        assertThatThrownBy(() -> new McpApiKeyRegistry(new AppProperties()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("没有配置");

        AppProperties duplicate = properties(
                agent("agent", "first", List.of(1L), false),
                agent("agent", "second", List.of(2L), false)
        );
        assertThatThrownBy(() -> new McpApiKeyRegistry(duplicate))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("重复");

        AppProperties invalidHash = new AppProperties();
        AppProperties.McpAgent invalid = new AppProperties.McpAgent();
        invalid.setId("agent");
        invalid.setKeyHash("plain-text-secret");
        invalid.setConnectionIds(List.of(1L));
        invalidHash.getMcp().setAgents(List.of(invalid));
        assertThatThrownBy(() -> new McpApiKeyRegistry(invalidHash))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BCrypt");
    }

    private AppProperties properties(AppProperties.McpAgent... agents) {
        AppProperties properties = new AppProperties();
        properties.getMcp().setAgents(List.of(agents));
        return properties;
    }

    private AppProperties.McpAgent agent(String id, String secret, List<Long> connectionIds, boolean allowProduction) {
        AppProperties.McpAgent agent = new AppProperties.McpAgent();
        agent.setId(id);
        agent.setKeyHash(new BCryptPasswordEncoder(4).encode(secret));
        agent.setConnectionIds(connectionIds);
        agent.setAllowProduction(allowProduction);
        return agent;
    }
}
