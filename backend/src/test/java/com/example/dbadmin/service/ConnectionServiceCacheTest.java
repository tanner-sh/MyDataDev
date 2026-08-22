package com.example.dbadmin.service;

import com.example.dbadmin.dto.ApiDtos.ConnectionRequest;
import com.example.dbadmin.model.DbConnection;
import com.example.dbadmin.repo.AuditRepository;
import com.example.dbadmin.repo.BackupTaskRepository;
import com.example.dbadmin.repo.ConnectionRepository;
import com.example.dbadmin.repo.RestoreJobRepository;
import com.example.dbadmin.core.DialectRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConnectionServiceCacheTest {
    private static final DbConnection ROW = new DbConnection(
            7, "prod-main", "mysql", "jdbc:mysql://localhost:3306/demo", "app",
            "cipher", "prod", false, Instant.EPOCH, Instant.EPOCH
    );

    private ConnectionRepository repository;
    private CryptoService crypto;
    private RemoteDataSourceRegistry dataSources;
    private BackupTaskRepository backupTasks;
    private RestoreJobRepository restoreJobs;
    private ConnectionService service;

    @BeforeEach
    void setUp() {
        repository = mock(ConnectionRepository.class);
        crypto = mock(CryptoService.class);
        dataSources = mock(RemoteDataSourceRegistry.class);
        backupTasks = mock(BackupTaskRepository.class);
        restoreJobs = mock(RestoreJobRepository.class);
        when(repository.findById(7)).thenReturn(Optional.of(ROW));
        when(crypto.decrypt("cipher")).thenReturn("secret");
        service = new ConnectionService(
                repository, crypto, mock(AuditRepository.class), backupTasks,
                mock(MetadataCacheService.class), dataSources, new DialectRegistry(), restoreJobs,
                new SqlScriptSplitter(), new SqlStatementClassifier()
        );
    }

    @Test
    void readsOneConnectionRowOnceAcrossRepeatedLookups() {
        assertThat(service.require(7)).isEqualTo(ROW);
        assertThat(service.require(7)).isEqualTo(ROW);
        assertThat(service.require(7)).isEqualTo(ROW);

        verify(repository, times(1)).findById(7);
    }

    @Test
    void decryptsOnePasswordOnceAcrossRepeatedLookups() {
        assertThat(service.password(7)).isEqualTo("secret");
        assertThat(service.password(7)).isEqualTo("secret");

        verify(crypto, times(1)).decrypt("cipher");
    }

    @Test
    void stillReportsAnUnknownConnection() {
        when(repository.findById(9)).thenReturn(Optional.empty());

        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> service.require(9)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Connection not found: 9");
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> service.require(9)))
                .isInstanceOf(IllegalArgumentException.class);
        // A miss must not be cached, or a later insert with that id stays invisible.
        verify(repository, times(2)).findById(9);
    }

    @Test
    void reloadsTheRowAfterAnUpdate() {
        DbConnection updated = new DbConnection(
                7, "prod-main", "mysql", "jdbc:mysql://other:3306/demo", "app",
                "cipher2", "prod", true, Instant.EPOCH, Instant.EPOCH
        );
        when(crypto.encrypt("new-secret")).thenReturn("cipher2");
        when(crypto.decrypt("cipher2")).thenReturn("new-secret");
        assertThat(service.password(7)).isEqualTo("secret");

        when(repository.findById(7)).thenReturn(Optional.of(updated));
        service.update(7, new ConnectionRequest(
                "prod-main", "mysql", "jdbc:mysql://other:3306/demo", "app", "new-secret", "prod", true,
                null, null, null, null, null
        ), "admin");

        assertThat(service.require(7)).isEqualTo(updated);
        assertThat(service.password(7)).isEqualTo("new-secret");
        verify(dataSources).evict(7);
    }

    @Test
    void reloadsTheRowAfterADelete() {
        assertThat(service.require(7)).isEqualTo(ROW);

        service.delete(7, "admin");

        when(repository.findById(7)).thenReturn(Optional.empty());
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> service.require(7)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(dataSources).evict(7);
    }

    @Test
    void keepsRejectingWritesWhileBackgroundWorkRuns() {
        when(backupTasks.countRunningByConnectionId(anyLong())).thenReturn(1);

        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> service.update(7, new ConnectionRequest(
                "prod-main", "mysql", "jdbc:mysql://localhost:3306/demo", "app", "x", "prod", false,
                null, null, null, null, null
        ), "admin"))).isInstanceOf(IllegalStateException.class);

        verify(repository, times(0)).update(anyLong(), any());
        verify(crypto, times(0)).encrypt(anyString());
    }
}
