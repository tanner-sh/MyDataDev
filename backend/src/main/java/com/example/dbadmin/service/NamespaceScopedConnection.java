package com.example.dbadmin.service;

import com.example.dbadmin.core.DatabaseDialect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;

/**
 * 池化连接的命名空间作用域：归还前把 {@link DatabaseDialect#activateNamespace} 改掉的
 * catalog/schema 还原成借出时的值。
 *
 * <p>HikariCP 只有在 {@code HikariConfig} 显式配置了 catalog/schema 时才会在归还时重置
 * 这两项（{@code PoolBase.resetConnectionState} 里对两者都有 {@code != null} 前置判断）。
 * {@link RemoteDataSourceRegistry} 刻意不配置它们 —— 配置就必须在建池时先连一次库探测默认
 * 值，而建池是在注册表锁内完成的，一个连不上的库会卡住所有池。结果是 setCatalog/setSchema
 * 会一直留在物理连接上被下一个借用者继承，表现为「全库备份备错库」「元数据落到别的库」
 * 「MCP 不带 namespace 的查询继承了 UI 上一次选的 schema」。</p>
 *
 * <p>还原发生在委托 {@code close()} 之前；只有在确实无法还原时（驱动不报告命名空间，或
 * 还原语句本身失败）才回退到淘汰整个连接池，因为留一条脏连接在池里比重建池危险得多。</p>
 */
final class NamespaceScopedConnection implements InvocationHandler {
    private static final Logger log = LoggerFactory.getLogger(NamespaceScopedConnection.class);

    private final Connection delegate;
    private final DatabaseDialect.NamespaceKind kind;
    private final String original;
    private final Runnable onUnrestorable;

    private NamespaceScopedConnection(
            Connection delegate,
            DatabaseDialect.NamespaceKind kind,
            String original,
            Runnable onUnrestorable
    ) {
        this.delegate = delegate;
        this.kind = kind;
        this.original = original;
        this.onUnrestorable = onUnrestorable;
    }

    /**
     * 读取连接当前的命名空间。驱动不支持时返回 {@code null}，调用方据此判断还原是否可行。
     */
    static String readNamespace(Connection connection, DatabaseDialect.NamespaceKind kind) {
        try {
            String value = kind == DatabaseDialect.NamespaceKind.CATALOG
                    ? connection.getCatalog()
                    : connection.getSchema();
            return value == null || value.isBlank() ? null : value;
        } catch (SQLException error) {
            log.debug("驱动不支持读取当前{}，归还时将无法还原", label(kind), error);
            return null;
        }
    }

    static Connection wrap(
            Connection delegate,
            DatabaseDialect.NamespaceKind kind,
            String original,
            Runnable onUnrestorable
    ) {
        return (Connection) Proxy.newProxyInstance(
                NamespaceScopedConnection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                new NamespaceScopedConnection(delegate, kind, original, onUnrestorable)
        );
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (!isClose(method)) return call(method, args);
        boolean restored = restore();
        Object result = call(method, args);
        // 连接已经归还，此时淘汰连接池才不会与正在关闭的连接互相等待。
        if (!restored) onUnrestorable.run();
        return result;
    }

    private Object call(Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(delegate, args);
        } catch (InvocationTargetException error) {
            throw error.getCause() == null ? error : error.getCause();
        }
    }

    private static boolean isClose(Method method) {
        return "close".equals(method.getName()) && method.getParameterCount() == 0;
    }

    private boolean restore() {
        String current;
        try {
            current = readNamespace(delegate, kind);
            if (Objects.equals(current, original)) return true;
            if (original == null) {
                // 借出时驱动没有报告命名空间，没有可写回的目标值。
                log.warn("无法还原池化连接的{}：借出时未知，当前为 {}，将淘汰该连接池", label(kind), current);
                return false;
            }
            if (kind == DatabaseDialect.NamespaceKind.CATALOG) delegate.setCatalog(original);
            else delegate.setSchema(original);
            return true;
        } catch (SQLException error) {
            log.warn("还原池化连接的{}失败，将淘汰该连接池", label(kind), error);
            return false;
        }
    }

    private static String label(DatabaseDialect.NamespaceKind kind) {
        return kind == DatabaseDialect.NamespaceKind.CATALOG ? "数据库" : "Schema";
    }
}
