package com.example.dbadmin.service;

import com.example.dbadmin.core.DefaultDialect;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 驱动没实现 JDBC 4.1 的 getSchema/setSchema 时抛的是 AbstractMethodError（Error 而非 SQLException）。
 * 读不到只能算「未知」，还原不了只能淘汰连接池 —— 但无论如何，委托的 close() 都必须执行。
 */
class NamespaceScopedConnectionTest {
    @Test
    void readingANamespaceTheDriverDoesNotImplementYieldsUnknown() throws Exception {
        Connection driver = unimplementedSchemaDriver();

        assertThat(NamespaceScopedConnection.readNamespace(driver, new DefaultDialect())).isNull();
    }

    @Test
    void returnsTheConnectionEvenWhenTheDriverCannotRestoreTheNamespace() throws Exception {
        Connection driver = unimplementedSchemaDriver();
        AtomicBoolean evicted = new AtomicBoolean();

        Connection scoped = NamespaceScopedConnection.wrap(driver, new DefaultDialect(), "REPORTING", () -> evicted.set(true));
        scoped.close();

        verify(driver).close();
        assertThat(evicted).isTrue();
    }

    private static Connection unimplementedSchemaDriver() throws Exception {
        Connection driver = mock(Connection.class);
        when(driver.getSchema()).thenThrow(new AbstractMethodError("Unimplemented method: getSchema()"));
        doThrow(new AbstractMethodError("Unimplemented method: getSchema()")).when(driver).setSchema(anyString());
        return driver;
    }
}
