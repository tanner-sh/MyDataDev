package com.example.dbadmin.service;

import com.example.dbadmin.api.ApiProblemException;
import com.example.dbadmin.config.AppProperties;
import com.example.dbadmin.model.DbConnection;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.metrics.micrometer.MicrometerMetricsTrackerFactory;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

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
    private static final SqlScriptSplitter SPLITTER = new SqlScriptSplitter();
    private static final SqlStatementClassifier CLASSIFIER = new SqlStatementClassifier();

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
                        create(connection.jdbcUrl(), connection.username(), password, connection.readonly(),
                                "remote-" + connection.id(), false, initStatements(connection)),
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
                // 这是服务端容量问题而不是调用方输入错误：走 IllegalStateException 会被统一
                // 异常处理器映射成 400 BAD_REQUEST，前端只能提示「请检查输入」。
                .orElseThrow(() -> new ApiProblemException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "REMOTE_POOL_EXHAUSTED",
                        "同时活跃的数据库连接已达到上限（" + maxPools + " 个），请等待正在执行的操作完成后重试。"
                ));
        pools.remove(idle.getKey());
        idle.getValue().dataSource().close();
    }

    private HikariDataSource create(String jdbcUrl, String username, String password, boolean readonly, String poolName, boolean failFast) {
        return create(jdbcUrl, username, password, readonly, poolName, failFast, List.of());
    }

    private HikariDataSource create(String jdbcUrl, String username, String password, boolean readonly, String poolName,
                                    boolean failFast, List<String> initStatements) {
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

        if (!initStatements.isEmpty()) {
            // Hikari 的 connectionInitSql 只能执行一条语句；会话初始化常常需要好几条 SET，
            // 所以自己接管建连这一步。只在配置了初始化 SQL 时替换，其余连接仍走 Hikari
            // 原本的驱动解析逻辑，不受影响。
            config.setDataSource(new InitializingDataSource(config.getJdbcUrl(), username, password, initStatements));
            config.setJdbcUrl(null);
            config.setUsername(null);
            config.setPassword(null);
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
            // 初始化 SQL 改了必须重建池：已有物理连接上跑的是旧语句。
            appendFingerprintValue(value, connection.initSql());
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

    /**
     * 解析连接上配置的会话初始化语句。
     *
     * <p>保存连接时已经校验过一遍；这里再解析一次是因为建池发生在很久之后，且要容忍历史数据。
     * 解析失败不能让连接直接不可用 —— 忽略掉比整条连接打不开更容易排查。</p>
     */
    private List<String> initStatements(DbConnection connection) {
        try {
            return ConnectionProfile.initStatements(connection.initSql(), SPLITTER, CLASSIFIER);
        } catch (RuntimeException error) {
            return List.of();
        }
    }

    /**
     * 建连后立刻执行会话初始化语句的 DataSource。
     *
     * <p>语句只在建立物理连接时执行一次，之后这条连接在池里被反复借出都保持同一份会话设置 ——
     * 这正是「会话初始化」应有的语义。</p>
     */
    private record InitializingDataSource(String jdbcUrl, String username, String password, List<String> initStatements)
            implements DataSource {
        @Override
        public Connection getConnection() throws SQLException {
            Connection connection = username == null && password == null
                    ? DriverManager.getConnection(jdbcUrl)
                    : DriverManager.getConnection(jdbcUrl, username, password);
            try (Statement statement = connection.createStatement()) {
                for (String sql : initStatements) statement.execute(sql);
            } catch (SQLException error) {
                try {
                    connection.close();
                } catch (SQLException closeError) {
                    error.addSuppressed(closeError);
                }
                throw error;
            }
            return connection;
        }

        @Override
        public Connection getConnection(String user, String secret) throws SQLException {
            return new InitializingDataSource(jdbcUrl, user, secret, initStatements).getConnection();
        }

        @Override
        public PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(PrintWriter out) {
        }

        @Override
        public void setLoginTimeout(int seconds) {
        }

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public Logger getParentLogger() {
            return Logger.getLogger(InitializingDataSource.class.getName());
        }

        @Override
        public <T> T unwrap(Class<T> type) throws SQLException {
            if (type.isInstance(this)) return type.cast(this);
            throw new SQLException("不支持的类型：" + type);
        }

        @Override
        public boolean isWrapperFor(Class<?> type) {
            return type.isInstance(this);
        }
    }

    private record PoolEntry(HikariDataSource dataSource, String fingerprint, long lastAccessNanos, int pendingBorrows) {
    }
}
