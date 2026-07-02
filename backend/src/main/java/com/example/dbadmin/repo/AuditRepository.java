package com.example.dbadmin.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AuditRepository {
    private final JdbcTemplate jdbc;

    public AuditRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void log(String actor, String action, String target, String detail) {
        jdbc.update("INSERT INTO audit_log(actor, action, target, detail) VALUES (?, ?, ?, ?)",
                actor == null || actor.isBlank() ? "anonymous" : actor, action, target, detail);
    }
}
