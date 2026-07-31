package com.example.dbadmin.service;

import com.example.dbadmin.config.AppProperties;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Future;

@Component
public class BackupExecutionCoordinator {
    private final Set<Long> running = ConcurrentHashMap.newKeySet();
    private final java.util.Map<Long, Future<?>> futures = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor executor;

    @Autowired
    public BackupExecutionCoordinator(AppProperties properties, ObjectProvider<MeterRegistry> meterRegistry) {
        this(properties.getBackgroundTasks().getBackupWorkerThreads(),
                properties.getBackgroundTasks().getQueueCapacity(), meterRegistry.getIfAvailable());
    }

    private BackupExecutionCoordinator(int workers, int queueCapacity, MeterRegistry meterRegistry) {
        int safeWorkers = Math.max(1, workers);
        executor = new ThreadPoolExecutor(
            safeWorkers,
            safeWorkers,
            30,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(Math.max(1, queueCapacity)),
            new BackupThreadFactory(),
            new ThreadPoolExecutor.AbortPolicy()
        );
        if (meterRegistry != null) {
            Gauge.builder("dbadmin.background.queue.size", executor, value -> value.getQueue().size())
                    .tag("type", "backup-restore").register(meterRegistry);
            Gauge.builder("dbadmin.background.active", executor, ThreadPoolExecutor::getActiveCount)
                    .tag("type", "backup-restore").register(meterRegistry);
        }
    }

    public boolean submit(long taskId, Runnable beforeStart, Runnable task) {
        if (!running.add(taskId)) return false;
        try {
            beforeStart.run();
            Future<?> future = executor.submit(() -> {
                try {
                    task.run();
                } finally {
                    running.remove(taskId);
                    futures.remove(taskId);
                }
            });
            futures.put(taskId, future);
            if (future.isDone()) futures.remove(taskId, future);
            return true;
        } catch (RuntimeException e) {
            running.remove(taskId);
            throw e;
        }
    }

    public boolean isRunning(long taskId) {
        return running.contains(taskId);
    }

    public boolean cancel(long taskId) {
        Future<?> future = futures.remove(taskId);
        boolean cancelled = future != null && future.cancel(true);
        if (cancelled) running.remove(taskId);
        return cancelled;
    }

    @PreDestroy
    public void close() {
        executor.shutdownNow();
    }

    private static final class BackupThreadFactory implements ThreadFactory {
        private int sequence;

        @Override
        public synchronized Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "dbadmin-backup-" + ++sequence);
            thread.setDaemon(true);
            return thread;
        }
    }
}
