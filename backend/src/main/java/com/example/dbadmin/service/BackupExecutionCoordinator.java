package com.example.dbadmin.service;

import jakarta.annotation.PreDestroy;
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
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(
            2,
            2,
            30,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(20),
            new BackupThreadFactory(),
            new ThreadPoolExecutor.AbortPolicy()
    );

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
