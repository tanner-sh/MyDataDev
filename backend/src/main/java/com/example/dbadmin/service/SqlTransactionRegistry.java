package com.example.dbadmin.service;

import com.example.dbadmin.api.ApiProblemException;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 手动事务的持有者。
 *
 * <p>此前脚本执行一律是逐条 autocommit、失败即停，前面已提交的不会回滚 —— 想在生产上「先看
 * 效果再决定提交」是做不到的。手动事务把一个标签页里的多次执行绑到同一条连接、同一个事务上。</p>
 *
 * <p>代价必须讲清楚：一个开着的事务会独占远程连接池里的一条连接，而每个连接的池上限只有
 * 个位数（{@code app.remote-pool.maximum-pool-size} 默认 3）。因此：</p>
 * <ul>
 *   <li>每条数据库连接同时只允许一个手动事务；</li>
 *   <li>空闲超过 {@link #idleTimeout} 自动回滚并归还连接，避免忘记提交把池占死；</li>
 *   <li>每次执行都会刷新空闲计时，正在用的事务不会被收走。</li>
 * </ul>
 */
@Component
public class SqlTransactionRegistry {
    private static final Logger log = LoggerFactory.getLogger(SqlTransactionRegistry.class);

    private final Map<String, OpenTransaction> byId = new ConcurrentHashMap<>();
    private final Map<Long, String> byConnection = new ConcurrentHashMap<>();
    private final Duration idleTimeout;

    public SqlTransactionRegistry() {
        this(Duration.ofMinutes(10));
    }

    SqlTransactionRegistry(Duration idleTimeout) {
        this.idleTimeout = idleTimeout;
    }

    public Duration idleTimeout() {
        return idleTimeout;
    }

    /**
     * 开启一个事务。调用方负责已经把连接设成 autoCommit=false。
     */
    public OpenTransaction open(long connectionId, Connection connection, String schemaName, String actor) {
        String id = UUID.randomUUID().toString();
        OpenTransaction transaction = new OpenTransaction(id, connectionId, connection, schemaName, actor, Instant.now());
        String previous = byConnection.putIfAbsent(connectionId, id);
        if (previous != null) {
            throw new ApiProblemException(
                    HttpStatus.CONFLICT,
                    "TRANSACTION_ALREADY_OPEN",
                    "该连接已有一个进行中的手动事务，请先提交或回滚。"
            );
        }
        byId.put(id, transaction);
        return transaction;
    }

    public OpenTransaction require(String id) {
        OpenTransaction transaction = id == null ? null : byId.get(id);
        if (transaction == null) {
            throw new ApiProblemException(
                    HttpStatus.CONFLICT,
                    "TRANSACTION_NOT_FOUND",
                    "事务不存在或已结束（可能因超时被自动回滚），请重新开启。"
            );
        }
        return transaction;
    }

    public OpenTransaction activeFor(long connectionId) {
        String id = byConnection.get(connectionId);
        return id == null ? null : byId.get(id);
    }

    /** 结束一个事务：从注册表摘掉并关闭连接。返回是否确实存在。 */
    public boolean close(String id) {
        OpenTransaction transaction = byId.remove(id);
        if (transaction == null) return false;
        byConnection.remove(transaction.connectionId(), id);
        try {
            transaction.connection().close();
        } catch (Exception error) {
            log.warn("归还手动事务连接失败 transaction={}", id, error);
        }
        return true;
    }

    /** 回滚并释放所有空闲超时的事务，返回被回收的事务 id。 */
    public List<String> sweepIdle() {
        Instant deadline = Instant.now().minus(idleTimeout);
        List<String> reclaimed = new java.util.ArrayList<>();
        for (OpenTransaction transaction : byId.values()) {
            if (transaction.lastUsedAt().isAfter(deadline)) continue;
            if (!transaction.lock().tryLock()) continue;
            try {
                log.warn("手动事务空闲超过 {}，自动回滚 transaction={} connection={}",
                        idleTimeout, transaction.id(), transaction.connectionId());
                try {
                    transaction.connection().rollback();
                } catch (Exception error) {
                    log.warn("自动回滚失败 transaction={}", transaction.id(), error);
                }
                if (close(transaction.id())) reclaimed.add(transaction.id());
            } finally {
                transaction.lock().unlock();
            }
        }
        return List.copyOf(reclaimed);
    }

    int size() {
        return byId.size();
    }

    @PreDestroy
    public void closeAll() {
        for (String id : List.copyOf(byId.keySet())) {
            OpenTransaction transaction = byId.get(id);
            if (transaction == null) continue;
            try {
                transaction.connection().rollback();
            } catch (Exception ignored) {
                // 进程正在退出，尽力而为。
            }
            close(id);
        }
    }

    /**
     * 一个进行中的事务。
     *
     * <p>{@code lock} 保证同一个事务不会被并发执行 —— 一条 JDBC 连接不是线程安全的，而前端
     * 完全可能在上一次执行还没返回时又点一次。</p>
     */
    public static final class OpenTransaction {
        private final String id;
        private final long connectionId;
        private final Connection connection;
        private final String schemaName;
        private final String actor;
        private final Instant startedAt;
        private final ReentrantLock lock = new ReentrantLock();
        private volatile Instant lastUsedAt;
        private volatile int statementCount;

        OpenTransaction(String id, long connectionId, Connection connection, String schemaName, String actor, Instant startedAt) {
            this.id = id;
            this.connectionId = connectionId;
            this.connection = connection;
            this.schemaName = schemaName;
            this.actor = actor;
            this.startedAt = startedAt;
            this.lastUsedAt = startedAt;
        }

        public String id() {
            return id;
        }

        public long connectionId() {
            return connectionId;
        }

        public Connection connection() {
            return connection;
        }

        public String schemaName() {
            return schemaName;
        }

        public String actor() {
            return actor;
        }

        public Instant startedAt() {
            return startedAt;
        }

        public Instant lastUsedAt() {
            return lastUsedAt;
        }

        public int statementCount() {
            return statementCount;
        }

        public ReentrantLock lock() {
            return lock;
        }

        public void recordUse(int statements) {
            this.statementCount += statements;
            this.lastUsedAt = Instant.now();
        }
    }
}
