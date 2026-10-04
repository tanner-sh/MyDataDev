package com.example.dbadmin.service.ai;

import com.example.dbadmin.api.ApiProblemException;
import com.example.dbadmin.config.AppProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class AiAgentCoordinatorTest {
    @SuppressWarnings("unchecked")
    private static final ObjectProvider<MeterRegistry> NO_METRICS = mock(ObjectProvider.class);

    @Test
    void queuedCancellationCompletesImmediatelyWithoutWaitingForRunningWorker() throws Exception {
        var properties = new AppProperties(); properties.getAiAgent().setWorkerThreads(1); properties.getAiAgent().setQueueCapacity(20); properties.getAiAgent().setMaxConcurrentPerUser(1);
        var coordinator = new AiAgentCoordinator(properties, NO_METRICS);
        var started = new CountDownLatch(1); var unblock = new CountDownLatch(1); var callback = new CountDownLatch(1); var ran = new AtomicBoolean();
        try {
            coordinator.submit("blocker", id -> { started.countDown(); try { unblock.await(); } catch (InterruptedException error) { Thread.currentThread().interrupt(); } });
            assertThat(started.await(3, TimeUnit.SECONDS)).isTrue();
            String queued = coordinator.submit("owner", id -> ran.set(true), callback::countDown);
            assertThat(coordinator.cancel(queued, "intruder")).isFalse();
            assertThat(callback.getCount()).isEqualTo(1);
            assertThat(coordinator.cancel(queued, "owner")).isTrue();
            assertThat(callback.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(coordinator.cancel(queued, "owner")).isFalse();
            // Its per-user reservation and bounded queue slot are immediately available again.
            String replacement = coordinator.submit("owner", id -> ran.set(true), () -> {});
            assertThat(coordinator.cancel(replacement, "owner")).isTrue();
            assertThat(ran).isFalse();
        } finally { unblock.countDown(); coordinator.close(); }
    }

    @Test
    void limitsPerUserConcurrencyAndInterruptsTheRunningRequest() throws Exception {
        AppProperties properties = new AppProperties();
        properties.getAiAgent().setWorkerThreads(1);
        properties.getAiAgent().setMaxConcurrentPerUser(1);
        AiAgentCoordinator coordinator = new AiAgentCoordinator(properties, NO_METRICS);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();

        String id = coordinator.submit("user:1", ignored -> {
            started.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException e) {
                interrupted.set(true);
                Thread.currentThread().interrupt();
            } finally {
                finished.countDown();
            }
        });
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> coordinator.submit("user:1", ignored -> { }))
                .isInstanceOf(ApiProblemException.class).hasMessageContaining("达到上限");
        assertThat(coordinator.cancel(id, "user:2")).isFalse();
        assertThat(coordinator.cancel(id, "user:1")).isTrue();
        assertThat(finished.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(interrupted).isTrue();
        coordinator.close();
    }
}
