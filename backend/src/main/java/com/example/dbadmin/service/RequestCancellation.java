package com.example.dbadmin.service;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/** Cancellation context for one synchronous tool request; never inherited by background workers. */
public final class RequestCancellation implements AutoCloseable {
    private static final ThreadLocal<RequestCancellation> CURRENT = new ThreadLocal<>();
    private final CopyOnWriteArrayList<Registration> actions = new CopyOnWriteArrayList<>();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final Executor executor;
    private final RequestCancellation previous;

    public RequestCancellation(Executor executor) {
        this.executor = executor;
        previous = CURRENT.get();
        CURRENT.set(this);
    }

    public static Registration watch(Statement statement) throws SQLException {
        check();
        return onCancel(statement::cancel);
    }

    public static void check() throws SQLException {
        RequestCancellation current = CURRENT.get();
        if (current != null && current.cancelled.get()) throw new SQLException("MCP 请求已取消", "HY008");
    }

    public static Registration onCancel(Action action) throws SQLException {
        RequestCancellation current = CURRENT.get();
        Registration registration = new Registration(action);
        if (current != null) {
            current.actions.add(registration);
            if (current.cancelled.get()) current.dispatch(registration);
        }
        return registration;
    }

    public void cancel() {
        cancelled.set(true);
        actions.forEach(this::dispatch);
    }

    private void dispatch(Registration action) {
        if (!action.active.get() || !action.dispatched.compareAndSet(false, true)) return;
        try { executor.execute(() -> {
            try { action.action.run(); }
            catch (Exception error) {
                org.slf4j.LoggerFactory.getLogger(RequestCancellation.class).warn("请求取消操作失败", error);
            }
        }); } catch (RuntimeException error) { action.dispatched.set(false); throw error; }
    }

    @Override public void close() {
        actions.forEach(Registration::close);
        actions.clear();
        if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
    }

    @FunctionalInterface public interface Action { void run() throws Exception; }
    public static final class Registration implements AutoCloseable {
        private final Action action;
        private final AtomicBoolean dispatched = new AtomicBoolean();
        private final AtomicBoolean active = new AtomicBoolean(true);
        private Registration(Action action) { this.action = action; }
        @Override public void close() { active.set(false); }
    }
}
