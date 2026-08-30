package com.example.dbadmin.repo;

import com.example.dbadmin.model.BackupHistory;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class BackupHistoryRepositoryTest {
    @Test
    void cancellationCannotBeOverwrittenByLateProgressOrSuccess() {
        BackupHistoryRepository repository = repository();
        long id = repository.insert(history("RUNNING", "BACKING_UP", false));

        repository.requestCancel(id);
        assertThat(repository.updateExecution(id, "SUCCESS", "COMPLETED", 1, 1L, "完成",
                "/tmp/backup.sql", 10L, "sha", Instant.now())).isFalse();
        assertThat(repository.updateExecution(id, "CANCELLED", "CANCELLED", 0, 1L, "已取消",
                null, null, null, Instant.now())).isTrue();
        assertThat(repository.updateProgress(id, "RUNNING", "UPLOADING", 1, 1L, "迟到进度")).isFalse();
        assertThat(repository.updateExecution(id, "SUCCESS", "COMPLETED", 1, 1L, "迟到成功",
                "/tmp/backup.sql", 10L, "sha", Instant.now())).isFalse();

        BackupHistory saved = repository.findById(id).orElseThrow();
        assertThat(saved.status()).isEqualTo("CANCELLED");
        assertThat(saved.phase()).isEqualTo("CANCELLED");
        assertThat(saved.filePath()).isNull();
    }

    @Test
    void uploadRetryUsesAnExplicitTransitionAndClearsTheOldCancellationFlag() {
        BackupHistoryRepository repository = repository();
        long id = repository.insert(history("FAILED", "UPLOAD_FAILED", true));

        assertThat(repository.updateProgress(id, "QUEUED", "UPLOAD_RETRY_QUEUED", 0, 10L, "重试")).isFalse();
        assertThat(repository.queueUploadRetry(id, 10L, "重试")).isTrue();

        BackupHistory saved = repository.findById(id).orElseThrow();
        assertThat(saved.status()).isEqualTo("QUEUED");
        assertThat(saved.phase()).isEqualTo("UPLOAD_RETRY_QUEUED");
        assertThat(saved.cancelRequested()).isFalse();
    }

    private BackupHistoryRepository repository() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:backup-history-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""
        );
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(dataSource);
        return new BackupHistoryRepository(new JdbcTemplate(dataSource));
    }

    private BackupHistory history(String status, String phase, boolean cancelRequested) {
        return new BackupHistory(0, 1L, 1L, status, "message", "/tmp/staging.sql", 10L,
                Instant.now(), null, "SQL", "SQL", "h2", "sha", phase, 0L, 10L, cancelRequested);
    }
}
