package com.example.dbadmin.auth;

import com.example.dbadmin.dto.UserAdminDtos.UserUpdateRequest;
import com.example.dbadmin.repo.AuditRepository;
import com.example.dbadmin.repo.UserAccountRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserAccountServiceTest {
    private final UserAccountRepository repository = mock(UserAccountRepository.class);
    private final LocalDatabaseIdentityProvider provider = mock(LocalDatabaseIdentityProvider.class);
    private final AuditRepository audit = mock(AuditRepository.class);
    private final UserAccountService service = new UserAccountService(repository, provider, audit);
    private final WebIdentity administrator = new WebIdentity(1L, "LOCAL", "admin", "admin", "Admin", "ADMIN", 0L);

    @Test
    void currentAdministratorCannotDisableItselfOrChangeItsRole() {
        when(repository.findById(1L)).thenReturn(Optional.of(account(1L, "admin", "ADMIN", true)));

        assertThatThrownBy(() -> service.update(
                1L, new UserUpdateRequest("admin", "Admin", "OPERATOR", null, true), administrator
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("自己的角色");

        verify(repository, never()).update(1L, "admin", "admin", "Admin", "OPERATOR", true, true);
    }

    @Test
    void keepsAtLeastOneEnabledAdministrator() {
        UserAccount lastAdmin = account(2L, "backup-admin", "ADMIN", true);
        when(repository.findById(2L)).thenReturn(Optional.of(lastAdmin));
        when(repository.lockEnabledAdministrators()).thenReturn(1L);

        assertThatThrownBy(() -> service.update(
                2L, new UserUpdateRequest("backup-admin", "Backup Admin", "OPERATOR", null, true), administrator
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("至少需要保留一个");

        verify(repository, never()).update(2L, "backup-admin", "backup-admin", "Backup Admin", "OPERATOR", true, true);
    }

    @Test
    void roleChangeInvalidatesExistingSessions() {
        when(repository.findById(2L))
                .thenReturn(Optional.of(account(2L, "analyst", "OPERATOR", true)))
                .thenReturn(Optional.of(account(2L, "analyst", "ADMIN", true)));

        service.update(2L, new UserUpdateRequest("analyst", "Analyst", "ADMIN", null, true), administrator);

        verify(repository).update(2L, "analyst", "analyst", "Analyst", "ADMIN", true, true);
        verify(repository, never()).updatePassword(org.mockito.ArgumentMatchers.anyLong(), anyString());
    }

    private UserAccount account(long id, String username, String role, boolean enabled) {
        return new UserAccount(
                id, "LOCAL", username, username, username, "hash", role, enabled, 0L,
                null, Instant.EPOCH, Instant.EPOCH
        );
    }
}
