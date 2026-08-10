package com.example.dbadmin.mcp;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class McpApiKeyRegistry {
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);
    private final String dummyHash = encoder.encode("unused-mcp-key");
    private final McpConfigurationService configuration;

    public McpApiKeyRegistry(McpConfigurationService configuration) {
        this.configuration = configuration;
    }

    public Optional<McpAgentPrincipal> authenticate(String credential) {
        int separator = credential == null ? -1 : credential.indexOf('.');
        if (separator <= 0 || separator == credential.length() - 1) {
            encoder.matches("invalid", dummyHash);
            return Optional.empty();
        }
        String id = credential.substring(0, separator);
        String secret = credential.substring(separator + 1);
        McpRuntimeConfig.Agent configured = configuration.snapshot().agents().get(id);
        String hash = configured == null ? dummyHash : configured.keyHash();
        if (!encoder.matches(secret, hash) || configured == null || !configured.enabled()) {
            return Optional.empty();
        }
        return Optional.of(new McpAgentPrincipal(
                configured.agentId(), configured.connectionIds(), configured.allowProduction()
        ));
    }
}
