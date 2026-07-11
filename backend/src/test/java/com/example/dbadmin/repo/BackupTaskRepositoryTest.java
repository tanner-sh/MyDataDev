package com.example.dbadmin.repo;

import com.example.dbadmin.model.BackupTask;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class BackupTaskRepositoryTest {
    @Test
    void migratesLegacySingleTableAndPreservesOrderedTargets() {
        JdbcTemplate jdbc = database();
        jdbc.update("""
                INSERT INTO backup_task(name, connection_id, scope, schema_name, table_name, cron, enabled)
                VALUES ('legacy', 1, 'TABLE', 'PUBLIC', 'USERS', NULL, FALSE)
                """);

        applySchema(jdbc);

        BackupTaskRepository repository = new BackupTaskRepository(jdbc);
        BackupTask migrated = repository.findAll().get(0);
        assertThat(migrated.scope()).isEqualTo("TABLES");
        assertThat(migrated.tableNames()).containsExactly("USERS");

        BackupTask updated = new BackupTask(
                migrated.id(), migrated.name(), migrated.connectionId(), "TABLES", "PUBLIC", "ROLES",
                List.of("ROLES", "USERS"), "SQL", null, null, null, null, false,
                null, null, null, null, (Instant) null
        );
        repository.update(migrated.id(), updated);

        BackupTask reloaded = repository.findById(migrated.id()).orElseThrow();
        assertThat(reloaded.tableName()).isEqualTo("ROLES");
        assertThat(reloaded.tableNames()).containsExactly("ROLES", "USERS");
        assertThat(jdbc.queryForList(
                "SELECT table_name FROM backup_task_table WHERE task_id = ? ORDER BY target_order",
                String.class, migrated.id()
        )).containsExactly("ROLES", "USERS");
    }

    @Test
    void insertsAndListsMultipleTasksWithoutLosingTargets() {
        JdbcTemplate jdbc = database();
        BackupTaskRepository repository = new BackupTaskRepository(jdbc);
        long id = repository.insert(new BackupTask(
                0, "multi", 1, "TABLES", "PUBLIC", "BETA", List.of("BETA", "ALPHA"),
                "SQL", null, null, null, null, false, null, null, null, null, null
        ));
        repository.insert(new BackupTask(
                0, "database", 1, "DATABASE", null, null, List.of(),
                "SQL", null, null, null, null, false, null, null, null, null, null
        ));

        assertThat(repository.findAll()).hasSize(2);
        assertThat(repository.findById(id).orElseThrow().tableNames()).containsExactly("BETA", "ALPHA");
    }

    private JdbcTemplate database() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:backup-repository-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""
        );
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        applySchema(jdbc);
        return jdbc;
    }

    private void applySchema(JdbcTemplate jdbc) {
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(jdbc.getDataSource());
    }
}
