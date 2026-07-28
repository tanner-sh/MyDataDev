package com.example.dbadmin.service;

import com.example.dbadmin.config.AppProperties;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class BackgroundTaskControlTest {
    @Test
    void sharesOnePermitAcrossBackgroundTaskTypesForTheSameConnection() {
        BackgroundTaskControl control = new BackgroundTaskControl(new AppProperties());

        assertThat(control.tryAcquire(7, "backup:1")).isTrue();
        assertThat(control.tryAcquire(7, "restore:2")).isFalse();
        assertThat(control.tryAcquire(8, "restore:3")).isTrue();

        control.release(7, "backup:1");
        assertThat(control.tryAcquire(7, "sql-file:4")).isTrue();
    }

    @Test
    void throttlesPersistentCancellationPollingAndHonorsMemorySignalImmediately() {
        AppProperties properties = new AppProperties();
        properties.getBackgroundTasks().setCancelPollIntervalMs(10_000);
        BackgroundTaskControl control = new BackgroundTaskControl(properties);
        AtomicInteger probes = new AtomicInteger();

        assertThat(control.isCancelled("restore:1", () -> {
            probes.incrementAndGet();
            return false;
        })).isFalse();
        assertThat(control.isCancelled("restore:1", () -> {
            probes.incrementAndGet();
            return false;
        })).isFalse();
        assertThat(probes).hasValue(1);

        control.requestCancel("restore:1");
        assertThat(control.isCancelled("restore:1", () -> false)).isTrue();
    }
}
