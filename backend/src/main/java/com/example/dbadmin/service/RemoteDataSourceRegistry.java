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
    private final SshTunnel.Timeouts sshTimeouts;
    private static final SqlScriptSplitter SPLITTER = new SqlScriptSplitter();
    private static final SqlStatementClassifier CLASSIFIER = new SqlStatementClassifier();

    public RemoteDataSourceRegistry() {
        this.maxPools = MAX_POOLS;
        this.maximumPoolSize = MAX_POOL_SIZE;
        this.connectionTimeoutMs = CONNECTION_TIMEOUT_MS;
        this.idleTimeoutMs = IDLE_TIMEOUT_MS;
        this.maxLifetimeMs = MAX_LIFETIME_MS;
        this.meterRegistry = null;
        this.sshTimeouts = SshTunnel.Timeouts.DEFAULTS;
    }

    @Autowired
    public RemoteDataSourceRegistry(AppProperties properties, ObjectProvider<MeterRegistry> meterRegistry) {
        this.maxPools = Math.max(1, properties.getRemotePool().getMaxPools());
        this.maximumPoolSize = Math.max(2, properties.getRemotePool().getMaximumPoolSize());
        this.connectionTimeoutMs = Math.max(1_000, properties.getRemotePool().getConnectionTimeoutMs());
        this.idleTimeoutMs = Math.max(10_000, properties.getRemotePool().getIdleTimeoutMs());
        this.maxLifetimeMs = Math.max(30_000, properties.getRemotePool().getMaxLifetimeMs());
        this.meterRegistry = meterRegistry.getIfAvailable();
        this.sshTimeouts = new SshTunnel.Timeouts(
                java.time.Duration.ofSeconds(Math.max(1, properties.getSsh().getConnectTimeoutSeconds())),
                java.time.Duration.ofSeconds(Math.max(1, properties.getSsh().getAuthTimeoutSeconds())),
                java.time.Duration.ofSeconds(Math.max(5, properties.getSsh().getHeartbeatSeconds())));
    }

    public Connection open(DbConnection connection, String password) throws Exception {
        return open(connection, password, null);
    }

    /**
     * 按连接配置借出一条连接，必要时先建好 SSH 隧道。
     *
     * @param ssh 已解密的隧道参数；{@code null} 表示直连
     */
    public Connection open(DbConnection connection, String password, SshTunnelSpec ssh) throws Exception {
        String fingerprint = fingerprint(connection, password, ssh);
        HikariDataSource dataSource = borrow(connection.id(), fingerprint);
        // 建池要握手 SSH，可能耗到超时上限，所以放在注册表锁外面：一条连不上的跳板机不该
        // 让其他连接的借还全部排队。代价是可能有两个线程同时建池，由 install 收拾多余的那个。
        if (dataSource == null) dataSource = install(connection.id(), createEntry(connection, password, ssh, fingerprint));
        try {
            return dataSource.getConnection();
        } finally {
            release(connection.id(), dataSource);
        }
    }

    private HikariDataSource borrow(long connectionId, String fingerprint) {
        PoolEntry stale = null;
        HikariDataSource dataSource = null;
        synchronized (pools) {
            PoolEntry existing = pools.get(connectionId);
            // 隧道断了池里的连接就全废了，和配置变更一样要重建。
            if (existing != null && (!existing.fingerprint().equals(fingerprint) || !existing.tunnelAlive())) {
                pools.remove(connectionId);
                stale = existing;
                existing = null;
            }
            if (existing != null) {
                pools.put(connectionId, existing.borrowed());
                dataSource = existing.dataSource();
            }
        }
        if (stale != null) stale.close();
        return dataSource;
    }

    private PoolEntry createEntry(DbConnection connection, String password, SshTunnelSpec ssh, String fingerprint) throws Exception {
        SshTunnel tunnel = null;
        String jdbcUrl = connection.jdbcUrl();
        if (ssh != null) {
            JdbcEndpoints.Endpoint endpoint = JdbcEndpoints.locate(jdbcUrl);
            tunnel = SshTunnel.open(ssh, endpoint.host(), endpoint.port(), sshTimeouts);
            jdbcUrl = JdbcEndpoints.rewrite(connection.jdbcUrl(), tunnel.localHost(), tunnel.localPort());
        }
        try {
            HikariDataSource dataSource = create(jdbcUrl, connection.username(), password, connection.readonly(),
                    "remote-" + connection.id(), false, initStatements(connection));
            return new PoolEntry(dataSource, tunnel, fingerprint, System.nanoTime(), 1);
        } catch (RuntimeException error) {
            if (tunnel != null) tunnel.close();
            throw error;
        }
    }

    private HikariDataSource install(long connectionId, PoolEntry created) {
        PoolEntry stale = null;
        PoolEntry redundant = null;
        RuntimeException failure = null;
        HikariDataSource dataSource;
        synchronized (pools) {
            PoolEntry existing = pools.get(connectionId);
            if (existing != null && existing.fingerprint().equals(created.fingerprint()) && existing.tunnelAlive()) {
                pools.put(connectionId, existing.borrowed());
                dataSource = existing.dataSource();
                redundant = created;
            } else {
                if (existing != null) {
                    pools.remove(connectionId);
                    stale = existing;
                }
                try {
                    makeRoom();
                    pools.put(connectionId, created);
                    dataSource = created.dataSource();
                } catch (RuntimeException error) {
                    failure = error;
                    redundant = created;
                    dataSource = null;
                }
            }
        }
        if (stale != null) stale.close();
        if (redundant != null) redundant.close();
        if (failure != null) throw failure;
        return dataSource;
    }

    private void release(long connectionId, HikariDataSource dataSource) {
        synchronized (pools) {
            PoolEntry current = pools.get(connectionId);
            if (current != null && current.dataSource() == dataSource && current.pendingBorrows() > 0) {
                pools.put(connectionId, current.returned());
            }
        }
    }

    public void test(String jdbcUrl, String username, String password) throws Exception {
        test(jdbcUrl, username, password, null);
    }

    /** 测试连接：配了隧道就连隧道测，否则「测试通过但保存后连不上」会让人无从下手。 */
    public void test(String jdbcUrl, String username, String password, SshTunnelSpec ssh) throws Exception {
        SshTunnel tunnel = null;
        try {
            String effectiveUrl = jdbcUrl;
            if (ssh != null) {
                JdbcEndpoints.Endpoint endpoint = JdbcEndpoints.locate(jdbcUrl);
                tunnel = SshTunnel.open(ssh, endpoint.host(), endpoint.port(), sshTimeouts);
                effectiveUrl = JdbcEndpoints.rewrite(jdbcUrl, tunnel.localHost(), tunnel.localPort());
            }
            try (HikariDataSource dataSource = create(effectiveUrl, username, password, false, "connection-test", true);
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
        } finally {
            if (tunnel != null) tunnel.close();
        }
    }

    /**
     * 为外部进程解析一个真正连得上的 JDBC 地址。
     *
     * <p>{@code mysqldump}、{@code pg_dump}、{@code exp} 这些原生工具是独立进程，用不了
     * 连接池里的隧道 —— 隧道是 {@link PoolEntry} 的私有设施。它们此前直接拿原始 URL 里的
     * host:port，于是只能经跳板机访问的库一律连接超时。</p>
     *
     * <p>这里现开一条短命隧道而不是复用池里那条：一次 dump 可能跑一个小时，而池会因为闲置
     * 被淘汰、因为改配置被 evict，隧道跟着关掉就会把 dump 拦腰截断。</p>
     *
     * <p>未启用隧道时不建任何东西，原样返回。</p>
     */
    public NativeAccess openNativeAccess(String jdbcUrl, SshTunnelSpec ssh) throws Exception {
        if (ssh == null) return new NativeAccess(jdbcUrl, null);
        JdbcEndpoints.Endpoint endpoint = JdbcEndpoints.locate(jdbcUrl);
        SshTunnel tunnel = SshTunnel.open(ssh, endpoint.host(), endpoint.port(), sshTimeouts);
        try {
            return new NativeAccess(JdbcEndpoints.rewrite(jdbcUrl, tunnel.localHost(), tunnel.localPort()), tunnel);
        } catch (RuntimeException error) {
            tunnel.close();
            throw error;
        }
    }

    /**
     * 一个原生工具可以直接连的地址，以及为它开的隧道（没有隧道时为 {@code null}）。
     *
     * <p>必须放在 try-with-resources 里：关掉它就是关掉隧道。</p>
     */
    public record NativeAccess(String jdbcUrl, SshTunnel tunnel) implements AutoCloseable {
        @Override
        public void close() {
            if (tunnel != null) tunnel.close();
        }
    }

    /**
     * 池的当前状态，给运维界面用。
     *
     * <p>池此前只在出问题时才「被看见」—— 报出 REMOTE_POOL_EXHAUSTED 的那一刻，用户既不知道
     * 20 个名额被谁占着，也不知道哪条早就闲着了。快照只读，不碰任何池的生命周期。</p>
     *
     * @param idleMillis 距上次借出的毫秒数；makeRoom 淘汰的就是这个值最大且空闲的那个
     * @param tunnelAlive 隧道是否还活着；无隧道的连接为 {@code null}，隧道死掉的池下次借用时会重建
     */
    public record PoolSnapshot(long connectionId, int total, int active, int idle, int waiting,
                               int maxPoolSize, int pendingBorrows, long idleMillis, Boolean tunnelAlive) {
    }

    public List<PoolSnapshot> poolSnapshot() {
        List<Map.Entry<Long, PoolEntry>> entries;
        synchronized (pools) {
            entries = List.copyOf(pools.entrySet());
        }
        long now = System.nanoTime();
        return entries.stream().map(entry -> {
            PoolEntry pool = entry.getValue();
            var mx = pool.dataSource().getHikariPoolMXBean();
            return new PoolSnapshot(
                    entry.getKey(),
                    mx == null ? 0 : mx.getTotalConnections(),
                    mx == null ? 0 : mx.getActiveConnections(),
                    mx == null ? 0 : mx.getIdleConnections(),
                    mx == null ? 0 : mx.getThreadsAwaitingConnection(),
                    maximumPoolSize,
                    pool.pendingBorrows(),
                    Math.max(0, (now - pool.lastAccessNanos()) / 1_000_000),
                    pool.tunnel() == null ? null : pool.tunnel().isOpen());
        }).toList();
    }

    /** 同时能存在多少个池。快照里的条数除以它就是「还剩多少名额」。 */
    public int capacity() {
        return maxPools;
    }

    public void evict(long connectionId) {
        PoolEntry removed;
        synchronized (pools) {
            removed = pools.remove(connectionId);
        }
        if (removed != null) removed.close();
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
        for (PoolEntry entry : entries) entry.close();
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
        idle.getValue().close();
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
        return fingerprint(connection, password, null);
    }

    String fingerprint(DbConnection connection, String password, SshTunnelSpec ssh) {
        try {
            StringBuilder value = new StringBuilder();
            appendFingerprintValue(value, connection.jdbcUrl());
            appendFingerprintValue(value, connection.username());
            appendFingerprintValue(value, password);
            appendFingerprintValue(value, Boolean.toString(connection.readonly()));
            // 初始化 SQL 改了必须重建池：已有物理连接上跑的是旧语句。
            appendFingerprintValue(value, connection.initSql());
            // 隧道参数改了也要重建池：池里的连接连的是上一条隧道的本地端口。
            appendFingerprintValue(value, ssh == null ? null : ssh.fingerprintMaterial());
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

    /**
     * 一个远程连接池，外加它依赖的 SSH 隧道。
     *
     * <p>两者生命周期必须绑在一起：隧道先关，池里的连接会在下一次使用时才发现自己断了。</p>
     */
    private record PoolEntry(HikariDataSource dataSource, SshTunnel tunnel, String fingerprint,
                             long lastAccessNanos, int pendingBorrows) {
        PoolEntry borrowed() {
            return new PoolEntry(dataSource, tunnel, fingerprint, System.nanoTime(), pendingBorrows + 1);
        }

        PoolEntry returned() {
            return new PoolEntry(dataSource, tunnel, fingerprint, lastAccessNanos, Math.max(0, pendingBorrows - 1));
        }

        boolean tunnelAlive() {
            return tunnel == null || tunnel.isOpen();
        }

        void close() {
            try {
                dataSource.close();
            } finally {
                if (tunnel != null) tunnel.close();
            }
        }
    }
}
