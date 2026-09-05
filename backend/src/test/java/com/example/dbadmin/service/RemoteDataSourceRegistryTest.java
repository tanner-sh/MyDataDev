package com.example.dbadmin.service;

import com.example.dbadmin.api.ApiProblemException;
import com.example.dbadmin.config.AppProperties;
import com.example.dbadmin.model.DbConnection;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RemoteDataSourceRegistryTest {
    @Test
    void reusesAndEvictsSmallPerConnectionPools() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        RemoteDataSourceRegistry registry = new RemoteDataSourceRegistry();
        DbConnection connection = connection(1L, url);
        try {
            try (var jdbc = registry.open(connection, "")) {
                jdbc.createStatement().execute("CREATE TABLE users(id INT PRIMARY KEY)");
            }
            try (var jdbc = registry.open(connection, "");
                 var resultSet = jdbc.createStatement().executeQuery("SELECT COUNT(*) FROM users")) {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getInt(1)).isZero();
            }
            assertThat(registry.size()).isEqualTo(1);

            registry.evict(connection.id());
            assertThat(registry.size()).isZero();
        } finally {
            registry.close();
        }
    }

    /**
     * 池此前只在报出 REMOTE_POOL_EXHAUSTED 的那一刻才「被看见」，而那条报错既说不出名额被谁
     * 占着，也说不出哪个早就闲了。
     */
    @Test
    void reportsWhatEachPoolIsHolding() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        RemoteDataSourceRegistry registry = new RemoteDataSourceRegistry();
        DbConnection connection = connection(1L, url);
        try {
            assertThat(registry.poolSnapshot()).isEmpty();
            try (var held = registry.open(connection, "")) {
                var busy = registry.poolSnapshot();
                assertThat(busy).singleElement().satisfies(pool -> {
                    assertThat(pool.connectionId()).isEqualTo(1L);
                    assertThat(pool.active()).isEqualTo(1);
                    assertThat(pool.total()).isGreaterThanOrEqualTo(1);
                    assertThat(pool.maxPoolSize()).isGreaterThanOrEqualTo(2);
                    // 直连没有隧道，不该显示成「隧道已断」。
                    assertThat(pool.tunnelAlive()).isNull();
                });
                assertThat(held.isClosed()).isFalse();
            }
            assertThat(registry.poolSnapshot()).singleElement()
                    .satisfies(pool -> assertThat(pool.active()).isZero());
            assertThat(registry.capacity()).isEqualTo(RemoteDataSourceRegistry.MAX_POOLS);

            registry.evict(1L);
            assertThat(registry.poolSnapshot()).isEmpty();
        } finally {
            registry.close();
        }
    }

    @Test
    void replacesPoolWhenConnectionConfigurationChanges() throws Exception {
        RemoteDataSourceRegistry registry = new RemoteDataSourceRegistry();
        try {
            DbConnection first = connection(1L, "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
            DbConnection second = connection(1L, "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
            try (var jdbc = registry.open(first, "")) {
                jdbc.createStatement().execute("CREATE TABLE first_database(id INT)");
            }
            try (var jdbc = registry.open(second, "");
                 var tables = jdbc.getMetaData().getTables(null, null, "FIRST_DATABASE", null)) {
                assertThat(tables.next()).isFalse();
            }
            assertThat(registry.size()).isEqualTo(1);
        } finally {
            registry.close();
        }
    }

    @Test
    void reportsAnExhaustedPoolBudgetAsServiceUnavailable() throws Exception {
        AppProperties properties = new AppProperties();
        properties.getRemotePool().setMaxPools(1);
        RemoteDataSourceRegistry registry = new RemoteDataSourceRegistry(properties, new EmptyObjectProvider<>());
        try {
            DbConnection busy = connection(1L, "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
            DbConnection blocked = connection(2L, "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
            try (var held = registry.open(busy, "")) {
                assertThat(held.isClosed()).isFalse();
                assertThatThrownBy(() -> registry.open(blocked, ""))
                        .isInstanceOfSatisfying(ApiProblemException.class, problem -> {
                            assertThat(problem.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                            assertThat(problem.code()).isEqualTo("REMOTE_POOL_EXHAUSTED");
                        });
            }
        } finally {
            registry.close();
        }
    }

    @Test
    void fingerprintDistinguishesNullFromLiteralNullCredentials() {
        RemoteDataSourceRegistry registry = new RemoteDataSourceRegistry();
        String url = "jdbc:h2:mem:" + UUID.randomUUID();
        Instant now = Instant.now();
        DbConnection nullUsername = new DbConnection(1L, "h2", "h2", url, null, "", "dev", false, now, now);
        DbConnection literalNullUsername = new DbConnection(1L, "h2", "h2", url, "null", "", "dev", false, now, now);

        assertThat(registry.fingerprint(nullUsername, null))
                .isNotEqualTo(registry.fingerprint(literalNullUsername, null));
        assertThat(registry.fingerprint(nullUsername, null))
                .isNotEqualTo(registry.fingerprint(nullUsername, "null"));
    }

    /** 注册表只用 ObjectProvider 取可选的 MeterRegistry，测试里不需要指标。 */
    private static final class EmptyObjectProvider<T> implements ObjectProvider<T> {
        @Override
        public T getObject() {
            throw new UnsupportedOperationException();
        }

        @Override
        public T getObject(Object... args) {
            throw new UnsupportedOperationException();
        }

        @Override
        public T getIfAvailable() {
            return null;
        }

        @Override
        public T getIfUnique() {
            return null;
        }
    }

    private DbConnection connection(long id, String url) {
        return new DbConnection(id, "h2", "h2", url, "sa", "", "dev", false, Instant.now(), Instant.now());
    }
}
