package com.example.dbadmin.service;

import db.migration.V1__BaselineSchema;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class FlywayBaselineMigrationTest {
    @Test
    void baselinesAnExistingMetadataDatabaseAndAppliesTheIdempotentSchema() throws Exception {
        String url = "jdbc:h2:mem:flyway-existing-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        try (var connection = DriverManager.getConnection(url, "sa", "")) {
            connection.createStatement().execute("CREATE TABLE legacy_marker(id INT PRIMARY KEY)");
            connection.createStatement().execute("INSERT INTO legacy_marker(id) VALUES (42)");
        }

        Flyway.configure()
                .dataSource(url, "sa", "")
                .baselineOnMigrate(true)
                .baselineVersion(MigrationVersion.fromVersion("0"))
                .javaMigrations(new V1__BaselineSchema())
                .load()
                .migrate();

        try (var connection = DriverManager.getConnection(url, "sa", "")) {
            try (var tables = connection.getMetaData().getTables(null, "PUBLIC", "BACKUP_TASK", null)) {
                assertThat(tables.next()).isTrue();
            }
            try (var users = connection.getMetaData().getTables(null, "PUBLIC", "APP_USER", null)) {
                assertThat(users.next()).isTrue();
            }
            try (var marker = connection.createStatement().executeQuery("SELECT id FROM legacy_marker")) {
                assertThat(marker.next()).isTrue();
                assertThat(marker.getInt(1)).isEqualTo(42);
            }
        }
    }
}
