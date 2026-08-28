package com.example.dbadmin.service;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class SqlFileExecutionCoordinatorTest {
    @Test
    void runsCompletionCleanupWhenQueuedTaskIsCancelledBeforeStart() throws Exception {
        SqlFileExecutionCoordinator coordinator = new SqlFileExecutionCoordinator(1, 10, null);
        CountDownLatch blockerStarted = new CountDownLatch(1);
        CountDownLatch unblock = new CountDownLatch(1);
        CountDownLatch cleanup = new CountDownLatch(1);
        AtomicBoolean queuedTaskRan = new AtomicBoolean(false);

        coordinator.submit(1L, () -> {
            blockerStarted.countDown();
            try {
                unblock.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });
        assertThat(blockerStarted.await(5, TimeUnit.SECONDS)).isTrue();
        coordinator.submit(2L, () -> queuedTaskRan.set(true), cleanup::countDown);

        assertThat(coordinator.cancel(2L)).isTrue();
        unblock.countDown();

        assertThat(cleanup.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(queuedTaskRan.get()).isFalse();
        coordinator.close();
    }
}
