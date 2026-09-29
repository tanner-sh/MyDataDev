package com.example.dbadmin.service;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.locks.ReentrantLock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class SqlTransactionRegistryTest {
    @Test
    void aUseBetweenTheIdleCheckAndLockAcquisitionMustNotBeReclaimed() throws Exception {
        SqlTransactionRegistry registry = new SqlTransactionRegistry(Duration.ofMinutes(10));
        Connection connection = mock(Connection.class);
        var transaction = registry.open(1L, connection, null, "admin", null);
        ReflectionTestUtils.setField(transaction, "lastUsedAt", Instant.now().minusSeconds(1200));
        ReentrantLock lock = spy(new ReentrantLock());
        ReflectionTestUtils.setField(transaction, "lock", lock);
        // 确定性重现：初筛已读到旧时间，执行线程恰好在回收线程拿锁前完成。
        doAnswer(invocation -> {
            transaction.recordUse(1);
            return invocation.callRealMethod();
        }).when(lock).tryLock();

        assertThat(registry.sweepIdle()).isEmpty();
        assertThat(registry.activeFor(1L)).isSameAs(transaction);
        verify(connection, never()).rollback();
        verify(connection, never()).close();
        assertThat(lock.isLocked()).isFalse();
        registry.closeAll();
    }

    @Test
    void aFinishBetweenTheIdleCheckAndLockAcquisitionMustNotRollbackAClosedConnection() throws Exception {
        SqlTransactionRegistry registry = new SqlTransactionRegistry(Duration.ofMinutes(10));
        Connection connection = mock(Connection.class);
        var transaction = registry.open(1L, connection, null, "admin", null);
        ReflectionTestUtils.setField(transaction, "lastUsedAt", Instant.now().minusSeconds(1200));
        ReentrantLock lock = spy(new ReentrantLock());
        ReflectionTestUtils.setField(transaction, "lock", lock);
        doAnswer(invocation -> {
            registry.close(transaction.id());
            return invocation.callRealMethod();
        }).when(lock).tryLock();

        assertThat(registry.sweepIdle()).isEmpty();
        verify(connection, never()).rollback();
        verify(connection, times(1)).close();
        assertThat(lock.isLocked()).isFalse();
    }
}
