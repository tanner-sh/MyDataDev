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
        AuditRepository repository = new AuditRepository(jdbc);

        assertThatCode(() -> repository.log("admin", "DATA_COMMIT", "table:users", "done"))
                .doesNotThrowAnyException();
    }
}
