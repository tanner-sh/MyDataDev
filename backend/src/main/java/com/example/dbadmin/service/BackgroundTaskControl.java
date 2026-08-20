package com.example.dbadmin.service;

import com.example.dbadmin.config.AppProperties;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/**
 * Coordinates expensive background work across task types.
 *
 * <p>A remote connection has a deliberately small pool. Backups, restores and
 * SQL-file executions must therefore share one connection-level permit instead
 * of filling the pool independently. Cancellation is kept in memory and only
 * falls back to the metadata database periodically, avoiding one H2 query per
 * executed statement.</p>
 */
@Component
public class BackgroundTaskControl {
    private final long cancelPollNanos;
    private final int maxPerConnection;
    private final Map<Long, Set<String>> connectionOwners = new ConcurrentHashMap<>();
    private final Set<String> cancelled = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> lastCancelPoll = new ConcurrentHashMap<>();

    public BackgroundTaskControl(AppProperties properties) {
        cancelPollNanos = Math.max(100, properties.getBackgroundTasks().getCancelPollIntervalMs()) * 1_000_000L;
        maxPerConnection = Math.max(1, properties.getBackgroundTasks().getMaxPerConnection());
    }

    public boolean tryAcquire(long connectionId, String operationKey) {
        synchronized (connectionOwners) {
            Set<String> owners = connectionOwners.computeIfAbsent(connectionId, ignored -> new HashSet<>());
            if (owners.contains(operationKey)) return true;
            if (owners.size() >= maxPerConnection) return false;
            owners.add(operationKey);
            return true;
        }
    }

    /**
     * Releases the connection permit only.
     *
     * <p>The cancellation marker is deliberately left in place: a cancel request
     * and the permit have different lifetimes. Cancelling asks a worker to stop,
     * but the worker keeps holding the permit until it actually stops, and it
     * still needs to observe the marker on its next {@link #isCancelled} check.
     * Use {@link #releaseCompleted} once the work has genuinely finished.</p>
     */
    public void release(long connectionId, String operationKey) {
        synchronized (connectionOwners) {
            Set<String> owners = connectionOwners.get(connectionId);
            if (owners != null) {
                owners.remove(operationKey);
                if (owners.isEmpty()) connectionOwners.remove(connectionId);
            }
        }
    }

    /**
     * Releases the permit and forgets the cancellation marker. Only the worker
     * that owns the operation may call this, from its terminal {@code finally},
     * or a caller that knows no worker will ever start.
     */
    public void releaseCompleted(long connectionId, String operationKey) {
        release(connectionId, operationKey);
        clear(operationKey);
    }

    public void requestCancel(String operationKey) {
        cancelled.add(operationKey);
    }

    public boolean isCancelled(String operationKey, BooleanSupplier persistentProbe) {
        if (Thread.currentThread().isInterrupted() || cancelled.contains(operationKey)) return true;
        long now = System.nanoTime();
        Long previous = lastCancelPoll.get(operationKey);
        if (previous == null || now - previous >= cancelPollNanos) {
            lastCancelPoll.put(operationKey, now);
            if (persistentProbe.getAsBoolean()) {
                cancelled.add(operationKey);
                return true;
            }
        }
        return false;
    }

    public void clear(String operationKey) {
        cancelled.remove(operationKey);
        lastCancelPoll.remove(operationKey);
    }
}
