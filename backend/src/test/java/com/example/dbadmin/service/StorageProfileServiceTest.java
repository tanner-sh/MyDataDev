package com.example.dbadmin.service;

import com.example.dbadmin.api.ApiProblemException;
import com.example.dbadmin.dto.StorageDtos.StorageProfileRequest;
import com.example.dbadmin.model.StorageProfile;
import com.example.dbadmin.repo.AuditRepository;
import com.example.dbadmin.repo.StorageProfileRepository;
import com.example.dbadmin.storage.BackupStorageRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StorageProfileServiceTest {
    @Test
    void encryptsSecretsAndNeverReturnsThem() {
        StorageProfileRepository repository = mock(StorageProfileRepository.class);
        CryptoService crypto = mock(CryptoService.class);
        BackupStorageRegistry storage = mock(BackupStorageRegistry.class);
        AuditRepository audit = mock(AuditRepository.class);
        when(crypto.encrypt("secret")).thenReturn("encrypted-secret");
        when(repository.insert(any())).thenReturn(7L);
        when(repository.findById(7L)).thenReturn(Optional.of(profile(7, "encrypted-secret", true)));
        StorageProfileService service = new StorageProfileService(repository, crypto, storage, audit);

        var response = service.create(sftpRequest("secret", "SHA256:host-key", true), "admin");

        ArgumentCaptor<StorageProfile> saved = ArgumentCaptor.forClass(StorageProfile.class);
        verify(repository).insert(saved.capture());
        assertThat(saved.getValue().encryptedPassword()).isEqualTo("encrypted-secret");
        assertThat(response.passwordConfigured()).isTrue();
        assertThat(response.toString()).doesNotContain("secret", "encrypted-secret");
    }

    @Test
    void preservesEncryptedPasswordWhenMaskIsSubmitted() {
        StorageProfileRepository repository = mock(StorageProfileRepository.class);
        CryptoService crypto = mock(CryptoService.class);
        StorageProfile old = profile(7, "existing-ciphertext", true);
        when(repository.findById(7L)).thenReturn(Optional.of(old));
        StorageProfileService service = new StorageProfileService(repository, crypto, mock(BackupStorageRegistry.class), mock(AuditRepository.class));

        service.update(7L, sftpRequest(StorageProfileService.SECRET_MASK, "SHA256:host-key", true), "admin");

        ArgumentCaptor<StorageProfile> updated = ArgumentCaptor.forClass(StorageProfile.class);
        verify(repository).update(org.mockito.ArgumentMatchers.eq(7L), updated.capture());
        assertThat(updated.getValue().encryptedPassword()).isEqualTo("existing-ciphertext");
        verify(crypto, never()).encrypt(any());
    }

    @Test
    void rejectsSftpWithoutHostKeyVerification() {
        StorageProfileService service = new StorageProfileService(mock(StorageProfileRepository.class), mock(CryptoService.class),
                mock(BackupStorageRegistry.class), mock(AuditRepository.class));

        assertThatThrownBy(() -> service.create(sftpRequest("secret", null, true), "admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("主机密钥指纹");
    }

    @Test
    void refusesToDisableProfileUsedByScheduledTask() {
        StorageProfileRepository repository = mock(StorageProfileRepository.class);
        StorageProfile old = profile(7, "ciphertext", true);
        when(repository.findById(7L)).thenReturn(Optional.of(old));
        when(repository.enabledScheduledTaskReferences(7L)).thenReturn(1);
        StorageProfileService service = new StorageProfileService(repository, mock(CryptoService.class),
                mock(BackupStorageRegistry.class), mock(AuditRepository.class));

        assertThatThrownBy(() -> service.update(7L, sftpRequest(StorageProfileService.SECRET_MASK, "SHA256:host-key", false), "admin"))
                .isInstanceOf(ApiProblemException.class)
                .hasMessageContaining("定时备份任务");
        verify(repository, never()).update(org.mockito.ArgumentMatchers.eq(7L), any());
    }

    @Test
    void refusesToDeleteReferencedProfile() {
        StorageProfileRepository repository = mock(StorageProfileRepository.class);
        when(repository.findById(7L)).thenReturn(Optional.of(profile(7, "ciphertext", true)));
        when(repository.historyReferences(7L)).thenReturn(2);
        StorageProfileService service = new StorageProfileService(repository, mock(CryptoService.class),
                mock(BackupStorageRegistry.class), mock(AuditRepository.class));

        assertThatThrownBy(() -> service.delete(7L, "admin"))
                .isInstanceOf(ApiProblemException.class)
                .hasMessageContaining("历史记录引用");
        verify(repository, never()).delete(7L);
    }

    private StorageProfileRequest sftpRequest(String password, String fingerprint, boolean enabled) {
        return new StorageProfileRequest("SFTP 备份", "SFTP", "files.internal", 22, "db-backups", "backup",
                password, null, null, null, null, null, List.of(), null, "PASSWORD", null, null,
                fingerprint, false, enabled);
    }

    private StorageProfile profile(long id, String encryptedPassword, boolean enabled) {
        Instant now = Instant.parse("2026-08-14T10:00:00Z");
        return new StorageProfile(id, "SFTP 备份", "SFTP", "files.internal", 22, "db-backups", "backup",
                encryptedPassword, null, null, null, null, null, "", null, "PASSWORD", null, null,
                "SHA256:host-key", false, enabled, null, null, null, now, now);
    }
}
