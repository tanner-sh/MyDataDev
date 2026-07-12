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

@Component
public class BackupExecutionCoordinator {
    private final Set<Long> running = ConcurrentHashMap.newKeySet();
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
            executor.execute(() -> {
                try {
                    task.run();
                } finally {
                    running.remove(taskId);
                }
            });
            return true;
        } catch (RuntimeException e) {
            running.remove(taskId);
            throw e;
        }
    }

    public boolean isRunning(long taskId) {
        return running.contains(taskId);
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
