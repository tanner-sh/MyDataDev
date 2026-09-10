package com.example.dbadmin.service;

import com.example.dbadmin.repo.SqlHistoryRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.sql.SQLException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 故障只注入本例创建的数据库会话和连接池，不重启或修改数据库服务器配置。 */
@Timeout(value = 1, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class DatabaseFaultCompatibilityTest {
    @ParameterizedTest @ValueSource(strings = {"mysql", "postgresql"})
    void queryTimeoutReleasesStatementAndPoolConnection(String type) throws Exception {
        try (var f = new DatabaseCompatibilityTest.Fixture(type); var fault = new FaultFixture(f)) {
            f.properties.getSql().setTimeoutSeconds(1);
            long start = System.nanoTime();
            assertThatThrownBy(() -> fault.sql.execute(1, fault.slowQuery(), 10, "ci"))
                    .isInstanceOf(SQLException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(15));
            fault.assertRecovered();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"mysql", "postgresql"})
    void cancellingAnExecutingQueryReleasesStatementAndPoolConnection(String type) throws Exception {
        try (var f = new DatabaseCompatibilityTest.Fixture(type); var fault = new FaultFixture(f)) {
            Future<Throwable> running = fault.startQuery();
            fault.awaitServerSession();
            assertThat(fault.sql.cancel(fault.executionId)).isTrue();
            assertThat(running.get(10, TimeUnit.SECONDS)).isInstanceOf(SQLException.class);
            fault.assertRecovered();
            assertThat(fault.sql.cancel(fault.executionId)).isFalse();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"mysql", "postgresql"})
    void serverDisconnectFailsTheQueryAndPoolCreatesAUsableConnection(String type) throws Exception {
        try (var f = new DatabaseCompatibilityTest.Fixture(type); var fault = new FaultFixture(f)) {
            Future<Throwable> running = fault.startQuery();
            long session = fault.awaitServerSession();
            // 终止本例带随机标记的正在运行的会话，真实驱动会收到连接断开；不是 mock SQLException。
            if (type.equals("mysql")) f.execute("KILL CONNECTION " + session);
            else assertThat(f.scalar("SELECT pg_terminate_backend(" + session + ")")).isEqualTo(true);
            assertThat(running.get(10, TimeUnit.SECONDS)).isInstanceOf(SQLException.class);
            fault.assertRecovered();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"mysql", "postgresql"})
    void poolExhaustionTimesOutWithoutLeakingAndRecoversAfterReturn(String type) throws Exception {
        try (var f = new DatabaseCompatibilityTest.Fixture(type); var fault = new FaultFixture(f)) {
            try (var first = f.connections.open(1); var second = f.connections.open(1)) {
                long start = System.nanoTime();
                assertThatThrownBy(() -> fault.sql.execute(1, "SELECT 1", 10, "ci"))
                        .isInstanceOf(SQLException.class);
                assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(10));
                assertThat(fault.executions.size()).isZero();
            }
            fault.assertRecovered();
        }
    }

    private static final class FaultFixture implements AutoCloseable {
        final DatabaseCompatibilityTest.Fixture f;
        final RemoteDataSourceRegistry pools;
        final SqlExecutionRegistry executions = new SqlExecutionRegistry();
        final SqlHistoryRepository history = mock(SqlHistoryRepository.class);
        final SqlService sql;
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        final String executionId = UUID.randomUUID().toString();
        final String marker = "ci_fault_" + UUID.randomUUID().toString().replace("-", "");
        long serverSession;

        FaultFixture(DatabaseCompatibilityTest.Fixture f) throws Exception {
            this.f = f;
            f.properties.getRemotePool().setMaximumPoolSize(2);
            f.properties.getRemotePool().setConnectionTimeoutMs(1000);
            f.properties.getSql().setTimeoutSeconds(20);
            pools = new RemoteDataSourceRegistry(f.properties, new StaticListableBeanFactory().getBeanProvider(MeterRegistry.class));
            var db = f.connections.require(1);
            String password = f.connections.password(1);
            when(f.connections.open(anyLong())).thenAnswer(call -> pools.open(db, password));
            when(f.connections.open(anyLong(), nullable(String.class))).thenAnswer(call -> pools.open(db, password));
            sql = new SqlService(f.connections, f.properties, f.audit, f.dialects, history, f.metadata,
                    new SqlScriptSplitter(), new SqlStatementClassifier(), f.guard, executions, f.edits, new SqlExecutionMetrics());
        }

        String slowQuery() {
            return "SELECT " + (f.type.equals("mysql") ? "SLEEP(60)" : "pg_sleep(60)") + " /* " + marker + " */";
        }
        Future<Throwable> startQuery() {
            return executor.submit(() -> {
                try { sql.execute(1, slowQuery(), 10, "ci", executionId, null, f.schema); return null; }
                catch (Exception error) { return error; }
            });
        }
        long awaitServerSession() throws Exception {
            String query = f.type.equals("mysql")
                    ? "SELECT ID FROM information_schema.PROCESSLIST WHERE ID <> CONNECTION_ID() AND INFO LIKE ?"
                    : "SELECT pid FROM pg_stat_activity WHERE pid <> pg_backend_pid() AND state='active' AND query LIKE ?";
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            try (var statement = f.jdbc.prepareStatement(query)) {
                statement.setString(1, "%" + marker + "%");
                statement.setQueryTimeout(2);
                while (System.nanoTime() < deadline) {
                    try (var rows = statement.executeQuery()) {
                        if (rows.next()) { serverSession = rows.getLong(1); return serverSession; }
                    }
                    Thread.sleep(50);
                }
            }
            throw new AssertionError("数据库未观测到本例的慢查询，不能提前取消一个尚未执行的 Statement");
        }
        void assertRecovered() throws Exception {
            assertThat(executions.size()).isZero();
            assertThat(pools.poolSnapshot()).allSatisfy(pool -> assertThat(pool.active()).isZero());
            assertThat(sql.execute(1, "SELECT 1", 10, "ci").rows()).hasSize(1);
            assertThat(executions.size()).isZero();
            assertThat(pools.poolSnapshot()).allSatisfy(pool -> {
                assertThat(pool.active()).isZero();
                assertThat(pool.waiting()).isZero();
                assertThat(pool.pendingBorrows()).isZero();
            });
            verify(history, atLeastOnce()).insert(eq(1L), anyString(), eq("EXECUTE"), eq("FAILED"), anyLong(), any(), eq("ci"));
        }
        @Override public void close() throws Exception {
            try {
                sql.cancel(executionId);
                if (serverSession != 0) {
                    // 若断言先失败，清理仍释放服务器上的慢查询；只针对刚观测到的本例会话。
                    try {
                        if (f.type.equals("mysql")) f.execute("KILL QUERY " + serverSession);
                        else f.execute("SELECT pg_cancel_backend(" + serverSession + ")");
                    } catch (SQLException ignored) { }
                }
            } finally {
                executor.shutdownNow();
                pools.close();
                assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            }
        }
    }
}
