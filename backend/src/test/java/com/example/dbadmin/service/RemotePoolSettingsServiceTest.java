package com.example.dbadmin.service;

import com.example.dbadmin.config.AppProperties;
import com.example.dbadmin.repo.AuditRepository;
import com.example.dbadmin.repo.RemotePoolSettingsRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class RemotePoolSettingsServiceTest {
    @Test
    void keepsDeploymentValueUntilSavedAndRestoresSavedValueAfterRestart() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:pool-settings-" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(source).load().migrate();
        var repository = new RemotePoolSettingsRepository(new JdbcTemplate(source));
        var properties = new AppProperties();
        properties.getRemotePool().setMaximumPoolSize(7);
        var pools = new RemoteDataSourceRegistry(properties, mock(ObjectProvider.class));
        var audit = mock(AuditRepository.class);
        var service = new RemotePoolSettingsService(repository, pools, audit);
        try {
            service.initialize();
            assertThat(service.maximumPoolSize()).isEqualTo(7);
            assertThat(repository.findMaximumPoolSize()).isEmpty();
            service.updateMaximumPoolSize(16, "admin");
            service.updateMaximumPoolSize(12, "admin");
            assertThat(repository.findMaximumPoolSize()).contains(12);
            assertThat(service.maximumPoolSize()).isEqualTo(12);
            verify(audit).global("admin", "CONNECTION_POOL_SETTINGS_UPDATE", "connection-pools", "maximumPoolSize=16 -> 12");
            var restartedPools = new RemoteDataSourceRegistry();
            try {
                var restarted = new RemotePoolSettingsService(repository, restartedPools, audit);
                restarted.initialize();
                assertThat(restarted.maximumPoolSize()).isEqualTo(12);
            } finally { restartedPools.close(); }
        } finally { pools.close(); }
    }

    @Test
    void invalidValuesAndPersistenceFailureLeaveRunningPoolsUnchanged() {
        var repository = mock(RemotePoolSettingsRepository.class);
        when(repository.findMaximumPoolSize()).thenReturn(Optional.empty());
        var pools = new RemoteDataSourceRegistry();
        var audit = mock(AuditRepository.class);
        var service = new RemotePoolSettingsService(repository, pools, audit);
        try {
            for (int value : new int[]{-1, 0, 1, 51, Integer.MAX_VALUE}) {
                assertThatThrownBy(() -> service.updateMaximumPoolSize(value, "admin"))
                        .isInstanceOf(IllegalArgumentException.class);
            }
            verifyNoInteractions(repository, audit);
            doThrow(new org.springframework.dao.DataAccessResourceFailureException("disk unavailable"))
                    .when(repository).saveMaximumPoolSize(20);
            assertThatThrownBy(() -> service.updateMaximumPoolSize(20, "admin"))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(service.maximumPoolSize()).isEqualTo(10);
            verifyNoInteractions(audit);
        } finally { pools.close(); }
    }
}
