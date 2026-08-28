package com.example.dbadmin.repo;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuditRepositoryTest {
    @Test
    void observabilityFailureDoesNotChangeCompletedBusinessOutcome() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenThrow(new IllegalStateException("audit unavailable"));
        // inline 队列让「写失败被吞掉」这条契约仍然可以同步断言。
        AuditRepository repository = new AuditRepository(jdbc, MetadataWriteQueue.inline());

        assertThatCode(() -> repository.onConnection("admin", "DATA_COMMIT", 1L, "table:users", "done"))
                .doesNotThrowAnyException();
    }
}
