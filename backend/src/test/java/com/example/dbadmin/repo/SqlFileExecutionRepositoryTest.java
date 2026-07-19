package com.example.dbadmin.repo;

import com.example.dbadmin.model.SqlFileExecution;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SqlFileExecutionRepositoryTest {
    @Test
    void extractsIdentityKeyAndQueuesOnlyOneTaskPerConnection() {
        JdbcTemplate jdbc = database();
        SqlFileExecutionRepository repository = new SqlFileExecutionRepository(jdbc);
        long first = repository.insert(job("first.sql"));
        long second = repository.insert(job("second.sql"));
        jdbc.update("UPDATE sql_file_execution SET status='READY' WHERE id IN (?, ?)", first, second);

        assertThat(first).isPositive();
        assertThat(second).isGreaterThan(first);
        assertThat(repository.queue(first)).isTrue();
        assertThat(repository.queue(second)).isFalse();
        assertThat(repository.countRunningByConnection(1)).isEqualTo(1);
    }

    private SqlFileExecution job(String name) {
        return new SqlFileExecution(0, 1, "local", "h2", name, "/tmp/" + name, 10, "abc", null,
                "ANALYZING", "ANALYZING", 0, null, 0, 0, 0, 0, 0, 0, 0, null, null, "test",
                false, false, false, "tester", Instant.now().plusSeconds(3600), null, null, Instant.now());
    }

    private JdbcTemplate database() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:sql-file-repository-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""
        );
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(dataSource);
        return jdbc;
    }
}
