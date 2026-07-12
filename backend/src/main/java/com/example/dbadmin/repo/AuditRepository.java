package com.example.dbadmin.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

@Repository
public class AuditRepository {
    private static final Logger log = LoggerFactory.getLogger(AuditRepository.class);
    private final JdbcTemplate jdbc;

    public AuditRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void log(String actor, String action, String target, String detail) {
        try {
            jdbc.update("INSERT INTO audit_log(actor, action, target, detail) VALUES (?, ?, ?, ?)",
                    truncate(actor == null || actor.isBlank() ? "anonymous" : actor, 120),
                    truncate(action, 80),
                    truncate(target, 500),
                    truncate(detail, 100_000));
        } catch (RuntimeException error) {
            // A local observability failure must never make an already-completed
            // remote database operation look failed to the caller.
            log.error("Unable to persist audit event action={} target={}", action, target, error);
        }
    }

    private String truncate(String value, int maximumLength) {
        if (value == null || value.length() <= maximumLength) return value;
        return value.substring(0, maximumLength);
    }
}
