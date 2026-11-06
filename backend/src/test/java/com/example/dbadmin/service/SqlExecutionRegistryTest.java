package com.example.dbadmin.service;

import org.junit.jupiter.api.Test;

import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SqlExecutionRegistryTest {
    /** 驱动报「取消」和「超时」常是同一个错误，只有登记处记得是不是用户点了取消。 */
    @Test
    void remembersThatTheUserCancelledUntilUnregistered() throws Exception {
        var registry = new SqlExecutionRegistry();
        Statement statement = mock(Statement.class);
        String id = registry.register(UUID.randomUUID().toString(), 1L, statement);
        assertThat(registry.cancelRequested(id, statement)).isFalse();

        assertThat(registry.cancel(id)).isTrue();
        verify(statement).cancel();
        assertThat(registry.cancelRequested(id, statement)).isTrue();
        assertThat(registry.cancelRequested(id, mock(Statement.class))).isFalse();

        registry.unregister(id, statement);
        assertThat(registry.cancelRequested(id, statement)).isFalse();
        assertThat(registry.cancelRequested(null, statement)).isFalse();
    }
}
