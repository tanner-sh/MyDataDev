package com.example.dbadmin.service;

import com.example.dbadmin.config.AppProperties;
import com.example.dbadmin.dto.ApiDtos.CronPreviewResponse;
import com.example.dbadmin.model.BackupTask;
import com.example.dbadmin.repo.AuditRepository;
import com.example.dbadmin.repo.BackupHistoryRepository;
import com.example.dbadmin.repo.BackupTaskRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

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
    void previewsThreeRunsInTheRequestedTimeZone() {
        BackupService service = BackupServiceTestFixture.create(
                mock(BackupTaskRepository.class),
                mock(BackupHistoryRepository.class),
                mock(ConnectionService.class),
                mock(AuditRepository.class),
                new AppProperties()
        );

        CronPreviewResponse preview = service.previewSchedule("0 30 2 * * *", null);

        assertThat(preview.cron()).isEqualTo("0 30 2 * * *");
        assertThat(preview.zoneId()).isNotBlank();
        assertThat(preview.nextRuns()).hasSize(3).isSorted();
        assertThatThrownBy(() -> service.previewSchedule("invalid", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cron 表达式不合法");

        CronPreviewResponse zoned = service.previewSchedule("0 30 2 * * *", "Asia/Shanghai");

        assertThat(zoned.zoneId()).isEqualTo("Asia/Shanghai");
        assertThat(zoned.nextRuns()).allMatch(run -> run.contains("T02:30"));
        assertThatThrownBy(() -> service.previewSchedule("0 30 2 * * *", "Mars/Olympus"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("无法识别的时区");
    }
}
