package com.example.dbadmin.service;

import db.migration.V23__ScriptConcurrencyLimit;
import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.sql.DriverManager;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ScriptConcurrencyMigrationTest {
    @Test void upgradesExistingSettingsAndPreservesSavedLimit() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:script-concurrency-migration")) {
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE db_connection(id BIGINT PRIMARY KEY)");
            }
            new ResourceDatabasePopulator(new ClassPathResource("mcp-schema.sql")).populate(connection);
            try (var statement = connection.createStatement()) {
                statement.execute("INSERT INTO mcp_settings VALUES (1, true, 100, 500, 20000, 1000000, 20000, 200000, 30, 50, 200, 50, 100, 120, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
                Context context = mock(Context.class);
                when(context.getConnection()).thenReturn(connection);
                var migration = new V23__ScriptConcurrencyLimit();
                migration.migrate(context);
                try (var rs = statement.executeQuery("SELECT max_scripts_per_connection, enabled FROM mcp_settings")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt(1)).isEqualTo(1);
                    assertThat(rs.getBoolean(2)).isTrue();
                }
                statement.execute("UPDATE mcp_settings SET max_scripts_per_connection = 2");
                migration.migrate(context);
                try (var rs = statement.executeQuery("SELECT max_scripts_per_connection FROM mcp_settings")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt(1)).isEqualTo(2);
                }
            }
        }
    }
}
