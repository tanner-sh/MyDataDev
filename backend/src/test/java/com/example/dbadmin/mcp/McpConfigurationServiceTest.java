package com.example.dbadmin.mcp;

import com.example.dbadmin.config.AppProperties;
import com.example.dbadmin.mcp.McpRuntimeConfig.Settings;
import com.example.dbadmin.repo.AuditRepository;
import com.example.dbadmin.repo.McpConfigurationRepository;
import com.example.dbadmin.service.ConnectionService;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpConfigurationServiceTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {0, -1, 17})
    void rejectsInvalidScriptConcurrencyWithoutPersisting(int limit) {
        McpConfigurationRepository repository = mock(McpConfigurationRepository.class);
        Settings persisted = new Settings(false, 100, 500, 20_000, 1_000_000,
                20_000, 200_000, 30, 50, 200, 50, 100, 120, 1);
        when(repository.findSettings()).thenReturn(Optional.of(persisted));
        when(repository.findAgents()).thenReturn(List.of());
        when(repository.findOrigins()).thenReturn(Set.of());
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any(TransactionDefinition.class))).thenReturn(mock(TransactionStatus.class));
        McpConfigurationService service = new McpConfigurationService(repository, mock(ConnectionService.class),
                mock(AuditRepository.class), new AppProperties(), manager);
        service.initialize();
        var limits = new com.example.dbadmin.dto.McpAdminDtos.McpLimits(100, 500, 20_000, 1_000_000,
                20_000, 200_000, 30, 50, 200, 50, 100, 120, limit);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.updateConfig(
                new com.example.dbadmin.dto.McpAdminDtos.McpConfigUpdateRequest(List.of(), limits), "admin"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("同连接脚本并发上限");
        verify(repository, never()).updateSettings(any());
        assertThat(service.snapshot().settings().maxScriptsPerConnection()).isEqualTo(1);
    }

    @Test
    void keepsPersistedDisabledStatusWhenNewInstallDefaultIsEnabled() {
        McpConfigurationRepository repository = mock(McpConfigurationRepository.class);
        Settings persisted = new Settings(
                false, 100, 500, 20_000, 1_000_000,
                20_000, 200_000, 30, 50, 200, 50, 100, 120, 1
        );
        when(repository.findSettings()).thenReturn(Optional.of(persisted));
        when(repository.findAgents()).thenReturn(List.of());
        when(repository.findOrigins()).thenReturn(Set.of());

        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenReturn(mock(TransactionStatus.class));
        AppProperties properties = new AppProperties();
        assertThat(properties.getMcp().isEnabled()).isTrue();

        McpConfigurationService service = new McpConfigurationService(
                repository,
                mock(ConnectionService.class),
                mock(AuditRepository.class),
                properties,
                transactionManager
        );
        service.initialize();

        assertThat(service.snapshot().settings().enabled()).isFalse();
        verify(repository, never()).insertSettings(any());
        verify(repository, never()).updateSettings(any());
    }
}
