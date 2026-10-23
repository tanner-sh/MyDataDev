package com.example.dbadmin.service;

import com.zaxxer.hikari.HikariDataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;

/** Keeps eviction attached to the borrowed connection's original pool, even after pool replacement. */
final class BorrowedConnection implements InvocationHandler {
    interface Lease { void discardOnClose(); }
    private final Connection delegate;
    private final HikariDataSource owner;
    private boolean discard;
    private boolean closed;

    private BorrowedConnection(Connection delegate, HikariDataSource owner) {
        this.delegate = delegate;
        this.owner = owner;
    }

    static Connection wrap(Connection delegate, HikariDataSource owner) {
        return (Connection) Proxy.newProxyInstance(BorrowedConnection.class.getClassLoader(),
                new Class<?>[]{Connection.class, Lease.class}, new BorrowedConnection(delegate, owner));
    }

    static void discardOnClose(Connection connection) throws java.sql.SQLException {
        connection.unwrap(Lease.class).discardOnClose();
    }

    @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (method.getDeclaringClass() == Lease.class) { discard = true; return null; }
        if (method.getName().equals("unwrap") && args[0] == Lease.class) return proxy;
        if (method.getName().equals("isWrapperFor") && args[0] == Lease.class) return true;
        if (method.getName().equals("close") && method.getParameterCount() == 0) {
            if (closed) return null;
            closed = true;
            // Eviction already removes and closes the physical connection. Closing the Hikari proxy
            // afterwards resets dirty state through a now-empty PoolEntry (setAutoCommit NPE).
            // Returning it first would expose contaminated session state to another borrower.
            if (discard) owner.evictConnection(delegate);
            else delegate.close();
            return null;
        }
        if (method.getName().equals("isClosed") && closed) return true;
        if (method.getName().equals("isValid") && closed) return false;
        if (closed && method.getDeclaringClass() != Object.class) throw new SQLException("Connection is closed");
        try { return method.invoke(delegate, args); }
        catch (InvocationTargetException error) { throw error.getCause(); }
    }
}
