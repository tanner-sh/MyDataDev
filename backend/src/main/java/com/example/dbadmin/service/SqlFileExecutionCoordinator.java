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
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Component
public class SqlFileExecutionCoordinator {
    private final Map<Long, BackgroundJobHandle> handles = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor executor;

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
        BackgroundJobHandle handle = new BackgroundJobHandle();
        handles.put(id, handle);
        try {
            // execute() rather than submit(), so the wrapper still runs when the
            // job is cancelled while queued and the bookkeeping is cleared by
            // exactly one path: the worker, once it has genuinely stopped.
            executor.execute(() -> {
                try {
                    if (handle.begin()) task.run();
                } finally {
                    handle.finish();
                    handles.remove(id, handle);
                }
            });
        } catch (RuntimeException e) {
            handles.remove(id, handle);
            throw e;
        }
    }

    /**
     * Requests cancellation without claiming the job has stopped; the worker
     * clears its own registration once it actually exits.
     */
    public boolean cancel(long id) {
        BackgroundJobHandle handle = handles.get(id);
        return handle != null && handle.cancel();
    }

    @PreDestroy
    public void close() { executor.shutdownNow(); }
}
