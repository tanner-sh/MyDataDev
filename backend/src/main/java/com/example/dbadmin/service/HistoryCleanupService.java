package com.example.dbadmin.service;

import com.example.dbadmin.config.AppProperties;
import com.example.dbadmin.repo.AuditRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Service
public class HistoryCleanupService {
    private final JdbcTemplate jdbc;
    private final AppProperties properties;
    private final AuditRepository audit;

    public HistoryCleanupService(JdbcTemplate jdbc, AppProperties properties, AuditRepository audit) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.audit = audit;
    }

    @Scheduled(cron = "0 17 3 * * *")
    public void cleanup() {
        int batch = Math.min(Math.max(properties.getMaintenance().getCleanupBatchSize(), 1), 10_000);
        purge("sql_history", "created_at", properties.getMaintenance().getSqlHistoryRetentionDays(), null, batch);
        purgeAudit(properties.getMaintenance().getAuditRetentionDays(), batch);
        String terminal = "status NOT IN ('ANALYZING','READY','QUEUED','RUNNING')";
        purge("restore_job", "created_at", properties.getMaintenance().getJobRetentionDays(),
                "status NOT IN ('QUEUED','RUNNING')", batch);
        purge("sql_file_execution", "created_at", properties.getMaintenance().getJobRetentionDays(), terminal, batch);
    }

    private void purgeAudit(int retentionDays, int batch) {
        if (retentionDays <= 0) return;
        Timestamp cutoff = Timestamp.from(Instant.now().minus(retentionDays, ChronoUnit.DAYS));
        while (audit.purgeBefore(cutoff, batch) == batch) {
            // 审计链锚点和删除必须由同一个串行入口更新。
        }
    }

    private void purge(String table, String timestampColumn, int retentionDays, String extraPredicate, int batch) {
        if (retentionDays <= 0) return;
        Timestamp cutoff = Timestamp.from(Instant.now().minus(retentionDays, ChronoUnit.DAYS));
        String predicate = extraPredicate == null ? "" : " AND " + extraPredicate;
        while (jdbc.update("DELETE FROM " + table + " WHERE id IN (SELECT id FROM " + table
                + " WHERE " + timestampColumn + " < ?" + predicate + " ORDER BY id LIMIT ?)", cutoff, batch) == batch) {
            // Delete in bounded transactions until the backlog is drained.
        }
    }
}
