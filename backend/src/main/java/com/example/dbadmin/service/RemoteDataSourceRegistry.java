package com.example.dbadmin.service;

import com.example.dbadmin.model.DbConnection;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class RemoteDataSourceRegistry {
    static final int MAX_POOLS = 20;
    private static final int MAX_POOL_SIZE = 3;
    private static final long CONNECTION_TIMEOUT_MS = 10_000;
    private final Map<Long, PoolEntry> pools = new LinkedHashMap<>();

    public Connection open(DbConnection connection, String password) throws Exception {
        HikariDataSource dataSource;
        synchronized (pools) {
            String fingerprint = fingerprint(connection, password);
            PoolEntry existing = pools.get(connection.id());
            if (existing != null && !existing.fingerprint().equals(fingerprint)) {
                pools.remove(connection.id());
                existing.dataSource().close();
                existing = null;
            }
            if (existing == null) {
                makeRoom();
                existing = new PoolEntry(
                        create(connection.jdbcUrl(), connection.username(), password, connection.readonly(), "remote-" + connection.id()),
                        fingerprint,
                        System.nanoTime(),
                        1
                );
            } else {
                existing = new PoolEntry(
                        existing.dataSource(), existing.fingerprint(), System.nanoTime(), existing.pendingBorrows() + 1
                );
            }
            pools.put(connection.id(), existing);
            dataSource = existing.dataSource();
        }
        try {
            return dataSource.getConnection();
        } finally {
            synchronized (pools) {
                PoolEntry current = pools.get(connection.id());
                if (current != null && current.dataSource() == dataSource && current.pendingBorrows() > 0) {
                    pools.put(connection.id(), new PoolEntry(
                            current.dataSource(), current.fingerprint(), current.lastAccessNanos(), current.pendingBorrows() - 1
                    ));
                }
            }
        }
    }

    public void test(String jdbcUrl, String username, String password) throws Exception {
        try (HikariDataSource dataSource = create(jdbcUrl, username, password, false, "connection-test");
             Connection ignored = dataSource.getConnection()) {
            // Obtaining a connection is the test.
        }
    }

    public void evict(long connectionId) {
        PoolEntry removed;
        synchronized (pools) {
            removed = pools.remove(connectionId);
        }
        if (removed != null) removed.dataSource().close();
    }

    int size() {
        synchronized (pools) {
            return pools.size();
        }
    }

    @PreDestroy
    public void close() {
        PoolEntry[] entries;
        synchronized (pools) {
            entries = pools.values().toArray(PoolEntry[]::new);
            pools.clear();
        }
        for (PoolEntry entry : entries) entry.dataSource().close();
    }

    private void makeRoom() {
        if (pools.size() < MAX_POOLS) return;
        Map.Entry<Long, PoolEntry> idle = pools.entrySet().stream()
                .filter(entry -> entry.getValue().pendingBorrows() == 0)
                .filter(entry -> entry.getValue().dataSource().getHikariPoolMXBean() == null
                        || entry.getValue().dataSource().getHikariPoolMXBean().getActiveConnections() == 0)
                .min(Comparator.comparingLong(entry -> entry.getValue().lastAccessNanos()))
                .orElseThrow(() -> new IllegalStateException("远程数据库连接池已达到上限，请等待正在执行的操作完成。"));
        pools.remove(idle.getKey());
        idle.getValue().dataSource().close();
    }

    private HikariDataSource create(String jdbcUrl, String username, String password, boolean readonly, String poolName) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(password);
        config.setReadOnly(readonly);
        config.setMinimumIdle(0);
        config.setMaximumPoolSize(MAX_POOL_SIZE);
        config.setConnectionTimeout(CONNECTION_TIMEOUT_MS);
        config.setValidationTimeout(5_000);
        config.setIdleTimeout(60_000);
        config.setMaxLifetime(600_000);
        // Pool construction happens under the registry lock; defer the first
        // network attempt so one unreachable database cannot block every pool.
        config.setInitializationFailTimeout(-1);
        config.setPoolName("dbadmin-" + poolName + "-" + Integer.toUnsignedString(System.identityHashCode(config)));
        return new HikariDataSource(config);
    }

    String fingerprint(DbConnection connection, String password) {
        try {
            StringBuilder value = new StringBuilder();
            appendFingerprintValue(value, connection.jdbcUrl());
            appendFingerprintValue(value, connection.username());
            appendFingerprintValue(value, password);
            appendFingerprintValue(value, Boolean.toString(connection.readonly()));
            return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(value.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("无法生成远程连接池标识", error);
        }
    }

    private void appendFingerprintValue(StringBuilder target, String value) {
        if (value == null) {
            target.append("-1:");
        } else {
            target.append(value.length()).append(':').append(value);
        }
        target.append('|');
    }

    private record PoolEntry(HikariDataSource dataSource, String fingerprint, long lastAccessNanos, int pendingBorrows) {
    }
}
