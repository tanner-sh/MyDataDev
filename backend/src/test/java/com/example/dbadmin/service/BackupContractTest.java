package com.example.dbadmin.service;

import com.example.dbadmin.config.AppProperties;
import com.example.dbadmin.dto.ApiDtos.CronPreviewResponse;
import com.example.dbadmin.model.BackupTask;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BackupContractTest {
    @Test
    void canonicalizesLegacyTableScopeAndExposesNextRun() {
        BackupTask task = new BackupTask(
                1, "legacy", 1, "TABLE", "PUBLIC", "USERS", "0 0 * * * *", true,
                null, null, null, null, (Instant) null
        );

        assertThat(task.scope()).isEqualTo("TABLES");
        assertThat(task.tableNames()).containsExactly("USERS");
        assertThat(task.zoneId()).isNotBlank();
        assertThat(task.nextRunAt()).isAfter(Instant.now());
    }

    @Test
    void previewsThreeRunsInSchedulerTimeZone() {
        BackupService service = new BackupService(null, null, null, null, new AppProperties());

        CronPreviewResponse preview = service.previewSchedule("0 30 2 * * *");

        assertThat(preview.cron()).isEqualTo("0 30 2 * * *");
        assertThat(preview.zoneId()).isNotBlank();
        assertThat(preview.nextRuns()).hasSize(3).isSorted();
        assertThatThrownBy(() -> service.previewSchedule("invalid"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cron 表达式不合法");
    }
}
