package com.example.dbadmin.service;

import com.example.dbadmin.config.AppProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class BackupExecutionCoordinatorTest {
    @SuppressWarnings("unchecked")
    private static final ObjectProvider<MeterRegistry> NO_METRICS = mock(ObjectProvider.class);

    @Test
    void keepsACancelledTaskRegisteredUntilItsWorkerActuallyStops() throws Exception {
        BackupExecutionCoordinator coordinator = new BackupExecutionCoordinator(new AppProperties(), NO_METRICS);
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean mayFinish = new AtomicBoolean(false);
        AtomicBoolean sawInterrupt = new AtomicBoolean(false);

        // A worker that does not abandon its work the instant it is interrupted,
        // which is what mysqldump and long JDBC batches actually look like.
        assertThat(coordinator.submit(1L, () -> { }, () -> {
            started.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!mayFinish.get() && System.nanoTime() < deadline) {
                if (Thread.currentThread().isInterrupted()) sawInterrupt.set(true);
                Thread.onSpinWait();
            }
        })).isTrue();
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

        assertThat(coordinator.cancel(1L)).isTrue();

        assertThat(sawInterrupt.get()).isTrue();
        assertThat(coordinator.isRunning(1L)).isTrue();
        assertThat(coordinator.submit(1L, () -> { }, () -> { })).isFalse();

        mayFinish.set(true);
        assertThat(awaitStopped(coordinator, 1L)).isTrue();
        assertThat(coordinator.submit(1L, () -> { }, () -> { })).isTrue();

        coordinator.close();
    }

    @Test
    void skipsTheWorkAndReleasesTheSlotWhenCancelledBeforeStarting() throws Exception {
        AppProperties properties = new AppProperties();
        properties.getBackgroundTasks().setBackupWorkerThreads(1);
        BackupExecutionCoordinator coordinator = new BackupExecutionCoordinator(properties, NO_METRICS);
        CountDownLatch blocking = new CountDownLatch(1);
        AtomicBoolean queuedTaskRan = new AtomicBoolean(false);

        // Occupy the single worker so the next task stays queued.
        coordinator.submit(1L, () -> { }, () -> {
            try {
                blocking.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });
        coordinator.submit(2L, () -> { }, () -> queuedTaskRan.set(true));

        assertThat(coordinator.cancel(2L)).isTrue();
        blocking.countDown();

        assertThat(awaitStopped(coordinator, 2L)).isTrue();
        assertThat(queuedTaskRan.get()).isFalse();

        coordinator.close();
    }

    private boolean awaitStopped(BackupExecutionCoordinator coordinator, long taskId) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (!coordinator.isRunning(taskId)) return true;
            Thread.sleep(10);
        }
        return false;
    }
}
