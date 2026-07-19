package com.example.dbadmin.service;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Component
public class SqlFileExecutionCoordinator {
    private final Map<Long, Future<?>> futures = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(
            2, 2, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(20), new ThreadFactory() {
        private int sequence;
        @Override public synchronized Thread newThread(Runnable task) {
            Thread thread = new Thread(task, "dbadmin-sql-file-" + ++sequence);
            thread.setDaemon(true);
            return thread;
        }
    }, new ThreadPoolExecutor.AbortPolicy());

    public void submit(long id, Runnable task) {
        Future<?> future = executor.submit(() -> {
            try { task.run(); }
            finally { futures.remove(id); }
        });
        futures.put(id, future);
        if (future.isDone()) futures.remove(id, future);
    }

    public boolean cancel(long id) {
        Future<?> future = futures.remove(id);
        return future != null && future.cancel(true);
    }

    @PreDestroy
    public void close() { executor.shutdownNow(); }
}
