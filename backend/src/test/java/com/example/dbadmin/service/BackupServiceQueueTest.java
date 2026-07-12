package com.example.dbadmin.service;

import com.example.dbadmin.api.ApiProblemException;
import com.example.dbadmin.config.AppProperties;
import com.example.dbadmin.core.DialectRegistry;
import com.example.dbadmin.model.BackupTask;
import com.example.dbadmin.repo.AuditRepository;
import com.example.dbadmin.repo.BackupHistoryRepository;
import com.example.dbadmin.repo.BackupTaskRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BackupServiceQueueTest {
    @Test
    void restoresTerminalStatusWhenExecutionQueueIsFull() {
        Fixture fixture = fixture();
        when(fixture.coordinator.submit(eq(1L), any(Runnable.class), any(Runnable.class)))
                .thenThrow(new RejectedExecutionException());

        assertThatThrownBy(() -> fixture.service.enqueue(1L, "admin"))
                .isInstanceOfSatisfying(ApiProblemException.class, problem -> {
                    assertThat(problem.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(problem.code()).isEqualTo("BACKUP_QUEUE_FULL");
                });
        verify(fixture.repository).updateStatus(1L, "FAILED", "备份执行队列已满，任务未启动。");
    }

    @Test
    void rejectsTaskDeletionWhileItIsQueuedOrRunning() {
        Fixture fixture = fixture();
        when(fixture.coordinator.isRunning(1L)).thenReturn(true);

        assertThatThrownBy(() -> fixture.service.delete(1L, false, "admin"))
                .isInstanceOfSatisfying(ApiProblemException.class, problem -> {
                    assertThat(problem.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(problem.code()).isEqualTo("BACKUP_ALREADY_RUNNING");
                });
    }

    private Fixture fixture() {
        BackupTaskRepository repository = mock(BackupTaskRepository.class);
        BackupExecutionCoordinator coordinator = mock(BackupExecutionCoordinator.class);
        BackupTask task = new BackupTask(1, "backup", 1, "DATABASE", null, null, null, false, null, null, null, null, null);
        when(repository.findById(1L)).thenReturn(Optional.of(task));
        BackupService service = new BackupService(
                repository,
                mock(BackupHistoryRepository.class),
                mock(ConnectionService.class),
                mock(AuditRepository.class),
                new AppProperties(),
                new DialectRegistry(),
                coordinator
        );
        return new Fixture(service, repository, coordinator);
    }

    private record Fixture(BackupService service, BackupTaskRepository repository, BackupExecutionCoordinator coordinator) {
    }
}
