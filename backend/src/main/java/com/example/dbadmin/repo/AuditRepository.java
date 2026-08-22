package com.example.dbadmin.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

@Repository
public class AuditRepository {
    private static final Logger log = LoggerFactory.getLogger(AuditRepository.class);
    private final JdbcTemplate jdbc;
    private final MetadataWriteQueue writes;

    public AuditRepository(JdbcTemplate jdbc, MetadataWriteQueue writes) {
        this.jdbc = jdbc;
        this.writes = writes;
    }

    public void log(String actor, String action, String target, String detail) {
        // 参数在提交时就截断并固定下来，后台线程不再依赖调用方的任何可变状态。
        String safeActor = truncate(actor == null || actor.isBlank() ? "anonymous" : actor, 120);
        String safeAction = truncate(action, 80);
        String safeTarget = truncate(target, 500);
        String safeDetail = truncate(detail, 100_000);
        // 审计只写不读，挪出请求线程可以省掉一次同步 H2 写；写失败的处理与之前一致。
        writes.submit(() -> insert(safeActor, safeAction, safeTarget, safeDetail));
    }

    private void insert(String actor, String action, String target, String detail) {
        try {
            jdbc.update("INSERT INTO audit_log(actor, action, target, detail) VALUES (?, ?, ?, ?)",
                    actor, action, target, detail);
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
