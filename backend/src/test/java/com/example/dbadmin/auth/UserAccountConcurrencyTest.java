package com.example.dbadmin.auth;

import com.example.dbadmin.dto.UserAdminDtos.UserUpdateRequest;
import com.example.dbadmin.repo.AuditRepository;
import com.example.dbadmin.repo.UserAccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class UserAccountConcurrencyTest {
    @Test
    void concurrentDemotionsStillLeaveOneEnabledAdministrator() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:user-admin-race-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000", "sa", ""
        );
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(dataSource);
        UserAccountRepository repository = new UserAccountRepository(new JdbcTemplate(dataSource));
        long firstId = repository.insert("LOCAL", "admin-a", "admin-a", "Admin A", "hash", "ADMIN", true);
        long secondId = repository.insert("LOCAL", "admin-b", "admin-b", "Admin B", "hash", "ADMIN", true);
        UserAccountService service = new UserAccountService(
                repository, mock(LocalDatabaseIdentityProvider.class), mock(AuditRepository.class)
        );
        TransactionTemplate transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        WebIdentity actor = new WebIdentity(99L, "LOCAL", "root", "root", "Root", "ADMIN", 0L);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);

        try {
            var first = executor.submit(() -> demote(transactions, service, firstId, "admin-a", "Admin A", actor, ready, start));
            var second = executor.submit(() -> demote(transactions, service, secondId, "admin-b", "Admin B", actor, ready, start));
            ready.await();
            start.countDown();

            assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(true, false);
            assertThat(repository.lockEnabledAdministrators()).isEqualTo(1L);
        } finally {
            executor.shutdownNow();
        }
    }

    private boolean demote(TransactionTemplate transactions, UserAccountService service, long id,
                           String username, String displayName, WebIdentity actor,
                           CountDownLatch ready, CountDownLatch start) throws InterruptedException {
        ready.countDown();
        start.await();
        try {
            transactions.executeWithoutResult(ignored -> service.update(
                    id, new UserUpdateRequest(username, displayName, "OPERATOR", null, true), actor
            ));
            return true;
        } catch (IllegalArgumentException rejected) {
            assertThat(rejected).hasMessageContaining("至少需要保留一个");
            return false;
        }
    }
}
