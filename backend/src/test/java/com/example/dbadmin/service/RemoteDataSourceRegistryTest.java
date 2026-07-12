package com.example.dbadmin.service;

import com.example.dbadmin.model.DbConnection;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

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

    private DbConnection connection(long id, String url) {
        return new DbConnection(id, "h2", "h2", url, "sa", "", "dev", false, Instant.now(), Instant.now());
    }
}
