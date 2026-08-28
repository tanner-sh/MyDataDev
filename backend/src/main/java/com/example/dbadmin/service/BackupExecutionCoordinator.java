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

@Component
public class BackupExecutionCoordinator {
    private final Set<Long> running = ConcurrentHashMap.newKeySet();
    private final java.util.Map<Long, BackgroundJobHandle> handles = new ConcurrentHashMap<>();
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
        return submit(taskId, beforeStart, task, () -> { });
    }

    /**
     * Runs completion cleanup even when cancellation happens before a queued
     * task starts and the task body is intentionally skipped.
     */
    public boolean submit(long taskId, Runnable beforeStart, Runnable task, Runnable afterCompletion) {
        if (!running.add(taskId)) return false;
        BackgroundJobHandle handle = new BackgroundJobHandle();
        handles.put(taskId, handle);
        try {
            beforeStart.run();
            // execute() rather than submit(): the wrapper must run even when the
            // job was cancelled while still queued, so that exactly one path —
            // the worker's own finally — clears the bookkeeping once the job has
            // genuinely stopped.
            executor.execute(() -> {
                try {
                    if (handle.begin()) task.run();
                } finally {
                    try {
                        afterCompletion.run();
                    } finally {
                        handle.finish();
                        running.remove(taskId);
                        handles.remove(taskId, handle);
                    }
                }
            });
            return true;
        } catch (RuntimeException e) {
            running.remove(taskId);
            handles.remove(taskId, handle);
            throw e;
        }
    }

    public boolean isRunning(long taskId) {
        return running.contains(taskId);
    }

    /**
     * Requests cancellation. The task stays registered as running until the
     * worker actually stops, so a cancelled job cannot be resubmitted while its
     * predecessor is still draining.
     */
    public boolean cancel(long taskId) {
        BackgroundJobHandle handle = handles.get(taskId);
        return handle != null && handle.cancel();
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
