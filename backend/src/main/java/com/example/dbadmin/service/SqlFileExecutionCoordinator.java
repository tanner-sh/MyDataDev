package com.example.dbadmin.service;

import com.example.dbadmin.config.AppProperties;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final ThreadPoolExecutor executor;

    public SqlFileExecutionCoordinator() {
        this(2, 20, null);
    }

    @Autowired
    public SqlFileExecutionCoordinator(AppProperties properties, ObjectProvider<MeterRegistry> meterRegistry) {
        this(properties.getBackgroundTasks().getSqlFileWorkerThreads(),
                properties.getBackgroundTasks().getQueueCapacity(), meterRegistry.getIfAvailable());
    }

    private SqlFileExecutionCoordinator(int workers, int queueCapacity, MeterRegistry meterRegistry) {
        int safeWorkers = Math.max(1, workers);
        executor = new ThreadPoolExecutor(
            safeWorkers, safeWorkers, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(Math.max(1, queueCapacity)), new ThreadFactory() {
        private int sequence;
        @Override public synchronized Thread newThread(Runnable task) {
            Thread thread = new Thread(task, "dbadmin-sql-file-" + ++sequence);
            thread.setDaemon(true);
            return thread;
        }
            }, new ThreadPoolExecutor.AbortPolicy());
        if (meterRegistry != null) {
            Gauge.builder("dbadmin.background.queue.size", executor, value -> value.getQueue().size())
                    .tag("type", "sql-file").register(meterRegistry);
            Gauge.builder("dbadmin.background.active", executor, ThreadPoolExecutor::getActiveCount)
                    .tag("type", "sql-file").register(meterRegistry);
        }
    }

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
