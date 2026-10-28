package com.example.dbadmin.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class RemotePoolSettingsRepository {
    private final JdbcTemplate jdbc;

    public RemotePoolSettingsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Integer> findMaximumPoolSize() {
        return jdbc.query("SELECT maximum_pool_size FROM remote_pool_settings WHERE id = 1",
                (rs, row) -> rs.getInt(1)).stream().findFirst();
    }

    public void saveMaximumPoolSize(int size) {
        jdbc.update("""
                MERGE INTO remote_pool_settings(id, maximum_pool_size, updated_at)
                KEY(id) VALUES (1, ?, CURRENT_TIMESTAMP)
                """, size);
    }
}
