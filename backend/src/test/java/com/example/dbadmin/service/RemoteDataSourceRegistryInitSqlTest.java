package com.example.dbadmin.service;

import com.example.dbadmin.model.DbConnection;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 会话初始化 SQL 的行为验证。
 *
 * <p>关键点不是「语句执行了」，而是「在物理连接上执行、并且被池里的后续借出继承」—— 这正是
 * Hikari 自带的 connectionInitSql 只支持一条语句时做不到的事。</p>
 */
class RemoteDataSourceRegistryInitSqlTest {
    @Test
    void runsEveryInitStatementOnTheSessionAndKeepsItAcrossBorrows() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        RemoteDataSourceRegistry registry = new RemoteDataSourceRegistry();
        try {
            DbConnection plain = connection(1L, url, null);
            try (var jdbc = registry.open(plain, "")) {
                jdbc.createStatement().execute("CREATE SCHEMA APP");
            }
            registry.evict(1L);

            // 两条语句：Hikari 的 connectionInitSql 只能执行一条，能同时生效才说明走了自己的建连路径。
            DbConnection configured = connection(1L, url, "SET SCHEMA APP;\nSET @init_marker = 'ok'");
            try (var jdbc = registry.open(configured, "");
                 var marker = jdbc.createStatement().executeQuery("SELECT @init_marker")) {
                assertThat(jdbc.getSchema()).isEqualTo("APP");
                assertThat(marker.next()).isTrue();
                assertThat(marker.getString(1)).isEqualTo("ok");
            }
            // 归还后再借出的是同一条物理连接，会话设置应当还在。
            try (var jdbc = registry.open(configured, "")) {
                assertThat(jdbc.getSchema()).isEqualTo("APP");
            }
        } finally {
            registry.close();
        }
    }

    @Test
    void changingInitSqlRebuildsThePoolInsteadOfReusingOldSessions() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        RemoteDataSourceRegistry registry = new RemoteDataSourceRegistry();
        try {
            try (var jdbc = registry.open(connection(1L, url, null), "")) {
                jdbc.createStatement().execute("CREATE SCHEMA APP");
                jdbc.createStatement().execute("CREATE SCHEMA REPORTING");
            }
            try (var jdbc = registry.open(connection(1L, url, "SET SCHEMA APP"), "")) {
                assertThat(jdbc.getSchema()).isEqualTo("APP");
            }
            try (var jdbc = registry.open(connection(1L, url, "SET SCHEMA REPORTING"), "")) {
                // 池按指纹重建；沿用旧池会拿到还停在 APP 的连接。
                assertThat(jdbc.getSchema()).isEqualTo("REPORTING");
            }
            assertThat(registry.size()).isEqualTo(1);
        } finally {
            registry.close();
        }
    }

    @Test
    void ignoresHistoricallyInvalidInitSqlRatherThanMakingTheConnectionUnusable() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        RemoteDataSourceRegistry registry = new RemoteDataSourceRegistry();
        try {
            // 保存时会拒绝这种语句；万一库里已有历史数据，也不该让整条连接打不开。
            try (var jdbc = registry.open(connection(1L, url, "DELETE FROM nonexistent_table"), "")) {
                assertThat(jdbc.isValid(2)).isTrue();
            }
        } finally {
            registry.close();
        }
    }

    private DbConnection connection(long id, String url, String initSql) {
        return new DbConnection(id, "h2", "h2", url, "sa", "", "dev", false,
                null, null, null, initSql, null, Instant.now(), Instant.now());
    }
}
