package com.example.dbadmin.service;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tracks one queued background job so cancellation can be delivered without
 * pretending the job has already stopped.
 *
 * <p>{@link java.util.concurrent.Future#cancel} reports success as soon as the
 * interrupt is delivered, which is not the same as the worker having finished.
 * Bookkeeping keyed off that return value therefore frees the job's slot while
 * the worker is still draining, letting a second run of the same job start
 * alongside the first. This handle keeps the two events distinct: a cancel only
 * ever sets state and interrupts, while the worker itself reports completion.</p>
 */
final class BackgroundJobHandle {
    private static final int PENDING = 0;
    private static final int RUNNING = 1;
    private static final int CANCELLED_BEFORE_START = 2;
    private static final int FINISHED = 3;

    private final AtomicInteger state = new AtomicInteger(PENDING);
    private volatile Thread worker;

    /**
     * Claims the handle for the calling worker thread. Returns {@code false}
     * when the job was cancelled before it ever started, in which case the
     * worker must skip the work but still call {@link #finish()}.
     */
    boolean begin() {
        if (!state.compareAndSet(PENDING, RUNNING)) return false;
        worker = Thread.currentThread();
        return true;
    }

    void finish() {
        state.set(FINISHED);
        worker = null;
    }

    /**
     * Requests cancellation. Returns {@code true} when the job was still
     * pending or running, i.e. when the request could still have an effect.
     */
    boolean cancel() {
        if (state.compareAndSet(PENDING, CANCELLED_BEFORE_START)) return true;
        if (state.get() != RUNNING) return false;
        Thread current = worker;
        if (current == null) return false;
        current.interrupt();
        return true;
    }
}
