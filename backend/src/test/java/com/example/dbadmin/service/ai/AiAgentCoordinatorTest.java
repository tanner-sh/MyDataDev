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
