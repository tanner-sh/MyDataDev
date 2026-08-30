package com.example.dbadmin.auth;

import com.example.dbadmin.config.AppProperties;
import com.example.dbadmin.repo.UserAccountRepository;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LocalDatabaseIdentityProviderTest {
    @Test
    void seedsFirstAdministratorOnlyWhenTheUserTableIsEmpty() {
        UserAccountRepository repository = mock(UserAccountRepository.class);
        when(repository.count()).thenReturn(0L);
        AppProperties properties = localProperties("Initial.Admin", "correct-horse-battery-staple");
        LocalDatabaseIdentityProvider provider = new LocalDatabaseIdentityProvider(repository, properties);

        provider.bootstrapFirstAdministrator();

        verify(repository).insert(
                eq("LOCAL"), eq("initial.admin"), eq("initial.admin"), eq("initial.admin"),
                anyString(), eq("ADMIN"), eq(true)
        );
        assertThat(properties.getAuth().getPassword()).isNull();
    }

    @Test
    void existingUsersDoNotDependOnTheBootstrapPasswordAtLaterStarts() {
        UserAccountRepository repository = mock(UserAccountRepository.class);
        when(repository.count()).thenReturn(2L);
        AppProperties properties = localProperties("ignored", null);
        LocalDatabaseIdentityProvider provider = new LocalDatabaseIdentityProvider(repository, properties);

        provider.bootstrapFirstAdministrator();

        assertThat(properties.getAuth().getPassword()).isNull();
    }

    @Test
    void emptyInstallationRequiresAStrongBootstrapPassword() {
        UserAccountRepository repository = mock(UserAccountRepository.class);
        when(repository.count()).thenReturn(0L);
        LocalDatabaseIdentityProvider provider = new LocalDatabaseIdentityProvider(repository, localProperties("admin", "short"));

        assertThatThrownBy(provider::bootstrapFirstAdministrator)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("至少 12 位");
    }

    private AppProperties localProperties(String username, String password) {
        AppProperties properties = new AppProperties();
        properties.getAuth().setMode("LOCAL");
        properties.getAuth().setUsername(username);
        properties.getAuth().setPassword(password);
        return properties;
    }
}
