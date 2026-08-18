package com.example.dbadmin.service;

import com.example.dbadmin.config.AppProperties;
import com.example.dbadmin.model.DbConnection;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.metrics.micrometer.MicrometerMetricsTrackerFactory;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.SQLException;
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
    private static final long IDLE_TIMEOUT_MS = 300_000;
    private static final long MAX_LIFETIME_MS = 1_800_000;
    private final Map<Long, PoolEntry> pools = new LinkedHashMap<>();
    private final int maxPools;
    private final int maximumPoolSize;
    private final long connectionTimeoutMs;
    private final long idleTimeoutMs;
    private final long maxLifetimeMs;
    private final MeterRegistry meterRegistry;

    public RemoteDataSourceRegistry() {
        this.maxPools = MAX_POOLS;
        this.maximumPoolSize = MAX_POOL_SIZE;
        this.connectionTimeoutMs = CONNECTION_TIMEOUT_MS;
        this.idleTimeoutMs = IDLE_TIMEOUT_MS;
        this.maxLifetimeMs = MAX_LIFETIME_MS;
        this.meterRegistry = null;
    }

    @Autowired
    public RemoteDataSourceRegistry(AppProperties properties, ObjectProvider<MeterRegistry> meterRegistry) {
        this.maxPools = Math.max(1, properties.getRemotePool().getMaxPools());
        this.maximumPoolSize = Math.max(2, properties.getRemotePool().getMaximumPoolSize());
        this.connectionTimeoutMs = Math.max(1_000, properties.getRemotePool().getConnectionTimeoutMs());
        this.idleTimeoutMs = Math.max(10_000, properties.getRemotePool().getIdleTimeoutMs());
        this.maxLifetimeMs = Math.max(30_000, properties.getRemotePool().getMaxLifetimeMs());
        this.meterRegistry = meterRegistry.getIfAvailable();
    }

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
        try {
            try (HikariDataSource dataSource = create(jdbcUrl, username, password, false, "connection-test", true);
                 Connection ignored = dataSource.getConnection()) {
                // Obtaining a connection is the test.
            }
        } catch (RuntimeException error) {
            // Hikari wraps authentication and database-selection failures in a
            // pool timeout when initialization is deferred. Return the JDBC
            // exception so the API can tell the user what actually failed.
            SQLException jdbcError = findSqlException(error);
            if (jdbcError != null) throw jdbcError;
            throw error;
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
        if (pools.size() < maxPools) return;
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
        return create(jdbcUrl, username, password, readonly, poolName, false);
    }

    private HikariDataSource create(String jdbcUrl, String username, String password, boolean readonly, String poolName, boolean failFast) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(password);
        config.setReadOnly(readonly);
        config.setMinimumIdle(0);
        config.setMaximumPoolSize(maximumPoolSize);
        config.setConnectionTimeout(connectionTimeoutMs);
        config.setValidationTimeout(5_000);
        config.setIdleTimeout(idleTimeoutMs);
        config.setMaxLifetime(maxLifetimeMs);
        // Pool construction happens under the registry lock; defer the first
        // network attempt so one unreachable database cannot block every pool.
        config.setInitializationFailTimeout(failFast ? 1 : -1);
        config.setPoolName("dbadmin-" + poolName + "-" + Integer.toUnsignedString(System.identityHashCode(config)));

        // Enable batch rewrite for MySQL/MariaDB
        String lowerUrl = jdbcUrl.toLowerCase();
        if (lowerUrl.startsWith("jdbc:mysql:") || lowerUrl.startsWith("jdbc:mariadb:")) {
            String separator = jdbcUrl.contains("?") ? "&" : "?";
            config.setJdbcUrl(jdbcUrl + separator + "rewriteBatchedStatements=true");
        }

        if (meterRegistry != null) config.setMetricsTrackerFactory(new MicrometerMetricsTrackerFactory(meterRegistry));
        return new HikariDataSource(config);
    }

    private SQLException findSqlException(Throwable error) {
        for (Throwable current = error; current != null && current != current.getCause(); current = current.getCause()) {
            if (current instanceof SQLException sqlException) return sqlException;
        }
        return error instanceof SQLException sqlException ? sqlException : null;
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
