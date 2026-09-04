package com.example.dbadmin.service.ai;

import com.example.dbadmin.api.ApiProblemException;
import com.example.dbadmin.config.AppProperties;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** 有界 Agent 执行池、按用户并发限制和真正可中断的请求句柄。 */
@Component
public class AiAgentCoordinator {
    private final ThreadPoolExecutor executor;
    private final int maxConcurrentPerUser;
    private final Map<String, Integer> userCounts = new ConcurrentHashMap<>();
    private final Map<String, Handle> handles = new ConcurrentHashMap<>();

    public AiAgentCoordinator(AppProperties properties, ObjectProvider<MeterRegistry> meterRegistry) {
        AppProperties.AiAgent config = properties.getAiAgent();
        int workers = Math.max(1, config.getWorkerThreads());
        maxConcurrentPerUser = Math.max(1, config.getMaxConcurrentPerUser());
        executor = new ThreadPoolExecutor(workers, workers, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(Math.max(1, config.getQueueCapacity())),
                new AgentThreadFactory(), new ThreadPoolExecutor.AbortPolicy());
        MeterRegistry registry = meterRegistry.getIfAvailable();
        if (registry != null) {
            Gauge.builder("dbadmin.ai.agent.queue.size", executor, value -> value.getQueue().size()).register(registry);
            Gauge.builder("dbadmin.ai.agent.active", executor, ThreadPoolExecutor::getActiveCount).register(registry);
            Gauge.builder("dbadmin.ai.agent.requests.running", handles, Map::size).register(registry);
        }
    }

    public String submit(String ownerKey, Consumer<String> task) {
        if (!reserve(ownerKey)) {
            throw busy("当前账号同时运行的 AI 请求已达到上限，请等待已有请求结束。");
        }
        String requestId = UUID.randomUUID().toString();
        Handle handle = new Handle(ownerKey);
        handles.put(requestId, handle);
        try {
            executor.execute(() -> {
                try {
                    handle.begin();
                    task.accept(requestId);
                } finally {
                    handles.remove(requestId, handle);
                    release(ownerKey);
                }
            });
            return requestId;
        } catch (RejectedExecutionException e) {
            handles.remove(requestId, handle);
            release(ownerKey);
            throw busy("AI 请求队列已满，请稍后重试。");
        } catch (RuntimeException e) {
            handles.remove(requestId, handle);
            release(ownerKey);
            throw e;
        }
    }

    public boolean cancel(String requestId, String ownerKey) {
        Handle handle = handles.get(requestId);
        return handle != null && handle.ownerKey.equals(ownerKey) && handle.cancel();
    }

    public static void checkCancelled() {
        if (Thread.currentThread().isInterrupted()) throw new AgentCancelledException();
    }

    private boolean reserve(String ownerKey) {
        boolean[] accepted = {false};
        userCounts.compute(ownerKey, (ignored, current) -> {
            int count = current == null ? 0 : current;
            if (count >= maxConcurrentPerUser) return count;
            accepted[0] = true;
            return count + 1;
        });
        return accepted[0];
    }

    private void release(String ownerKey) {
        userCounts.computeIfPresent(ownerKey, (ignored, count) -> count <= 1 ? null : count - 1);
    }

    private static ApiProblemException busy(String message) {
        return new ApiProblemException(HttpStatus.TOO_MANY_REQUESTS, "AI_AGENT_BUSY", message);
    }

    @PreDestroy
    void close() {
        handles.values().forEach(Handle::cancel);
        executor.shutdownNow();
    }

    static final class AgentCancelledException extends RuntimeException {
        AgentCancelledException() { super("AI 请求已取消"); }
    }

    private static final class Handle {
        private final String ownerKey;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private volatile Thread worker;

        private Handle(String ownerKey) { this.ownerKey = ownerKey; }

        void begin() {
            worker = Thread.currentThread();
            if (cancelled.get()) worker.interrupt();
        }

        boolean cancel() {
            if (!cancelled.compareAndSet(false, true)) return false;
            Thread current = worker;
            if (current != null) current.interrupt();
            return true;
        }
    }

    private static final class AgentThreadFactory implements ThreadFactory {
        private final AtomicInteger sequence = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "dbadmin-ai-agent-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
