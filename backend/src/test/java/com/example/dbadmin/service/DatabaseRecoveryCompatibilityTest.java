package com.example.dbadmin.service;

import com.example.dbadmin.dto.ApiDtos.*;
import com.example.dbadmin.model.BackupHistory;
import com.example.dbadmin.model.BackupTask;
import com.example.dbadmin.model.RestoreJob;
import com.example.dbadmin.repo.*;
import com.example.dbadmin.storage.BackupStorageRegistry;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Timeout(value = 5, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class DatabaseRecoveryCompatibilityTest {
    @TempDir Path directory;

    @ParameterizedTest @ValueSource(strings = {"mysql", "mariadb", "postgresql", "sqlserver", "oracle"})
    void relatedTablesRestoreDataCompositeKeyUniqueAndForeignKey(String type) throws Exception {
        try (var f = new DatabaseCompatibilityTest.Fixture(type)) {
            f.properties.getBackup().setDirectory(directory.toString());
            String parent = f.table(f.pk("id") + ", " + f.varchar("name", 80));
            String child = f.table(f.id("parent_id") + " NOT NULL, " + f.id("seq") + " NOT NULL, "
                    + f.varchar("code", 40) + " NOT NULL, PRIMARY KEY (parent_id, seq), "
                    + "FOREIGN KEY (parent_id) REFERENCES " + f.q(parent) + " (id)");
            f.execute("CREATE UNIQUE INDEX " + f.dialect.quoteIdentifier("idx_" + child) + " ON " + f.q(child) + " (code)");
            f.insert(parent, "1, 'parent'", "2, 'other'");
            f.insert(child, "1, 1, 'a'", "1, 2, 'b'", "2, 1, 'c'");
            var tasks = mock(BackupTaskRepository.class);
            var histories = mock(BackupHistoryRepository.class);
            var task = new BackupTask(1, "关联表恢复", 1, "TABLES", f.schema, parent, List.of(parent, child),
                    "SQL", null, null, null, null, false, null, null, null, null, null);
            when(tasks.findById(1L)).thenReturn(Optional.of(task));
            BackupServiceTestFixture.create(tasks, histories, f.connections, f.audit, f.properties).run(1, "ci");
            var captured = ArgumentCaptor.forClass(BackupHistory.class);
            verify(histories).insert(captured.capture());
            assertThat(captured.getValue().status()).isEqualTo("SUCCESS");
            f.execute("DROP TABLE " + f.q(child));
            f.execute("DROP TABLE " + f.q(parent));
            var harness = new RestoreHarness(f, captured.getValue());
            harness.run("SAFE");
            assertThat(harness.status.get()).as(harness.message.get()).isEqualTo("SUCCESS");
            assertThat(f.number("SELECT COUNT(*) FROM " + f.q(child) + " c JOIN " + f.q(parent) + " p ON c.parent_id=p.id")).isEqualTo(3);
            assertThat(f.metadata.detail(1, f.schema, child, true).primaryKeys()).containsExactly(f.col("parent_id"), f.col("seq"));
            assertThatThrownBy(() -> f.execute("INSERT INTO " + f.q(child) + " VALUES (99, 1, 'orphan')"))
                    .isInstanceOf(java.sql.SQLException.class);
            assertThatThrownBy(() -> f.execute("INSERT INTO " + f.q(child) + " VALUES (1, 3, 'a')"))
                    .isInstanceOf(java.sql.SQLException.class);
            assertThatThrownBy(() -> f.execute("INSERT INTO " + f.q(child) + " VALUES (1, 1, 'new')"))
                    .isInstanceOf(java.sql.SQLException.class);
            assertThat(f.number("SELECT COUNT(*) FROM " + f.q(child))).isEqualTo(3);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"mysql", "mariadb", "postgresql", "sqlserver", "oracle"})
    void restoreFailureAfter501InsertsLeavesExistingDataAndAllowsRetry(String type) throws Exception {
        try (var f = new DatabaseCompatibilityTest.Fixture(type)) {
            f.properties.getBackup().setDirectory(directory.toString());
            String table = f.table(f.pk("id"));
            f.execute("INSERT INTO " + f.q(table) + " VALUES (9999)");
            String separator = f.dialect.scriptStatementSeparator() + "\n";
            StringBuilder content = new StringBuilder();
            for (int id = 1; id <= 501; id++) content.append("INSERT INTO ").append(f.q(table)).append(" VALUES (").append(id).append(")").append(separator);
            Path file = directory.resolve("restore.sql");
            Files.writeString(file, content + "INSERT INTO " + f.q(table) + " VALUES (1)" + separator);
            var failed = new RestoreHarness(f, history(file, type));
            failed.run("APPEND");
            assertThat(failed.status.get()).as(failed.message.get()).isEqualTo("FAILED");
            assertThat(f.number("SELECT COUNT(*) FROM " + f.q(table))).isEqualTo(1);
            assertThat(f.number("SELECT id FROM " + f.q(table))).isEqualTo(9999);
            // 修正文件并重新预检后可重试；不能把旧失败任务留下的连接占用带入下一轮。
            Files.writeString(file, content);
            var retry = new RestoreHarness(f, history(file, type));
            retry.run("APPEND");
            assertThat(retry.status.get()).as(retry.message.get()).isEqualTo("SUCCESS");
            assertThat(f.number("SELECT COUNT(*) FROM " + f.q(table))).isEqualTo(502);
        }
    }

    private static BackupHistory history(Path file, String type) throws Exception {
        return new BackupHistory(1, 1, 1, "SUCCESS", null, file.toString(), Files.size(file), Instant.now(), Instant.now(),
                "SQL", "SQL", type, FileIntegrity.sha256(file), "COMPLETED", 0L, 0L, false);
    }

    /** JDBC、解析、预检、恢复和事务都是真实实现，只把调度同步化及任务存储放在内存边界。 */
    static final class RestoreHarness {
        final AtomicReference<String> status = new AtomicReference<>();
        final AtomicReference<String> message = new AtomicReference<>();
        final RestoreService service;
        final DatabaseCompatibilityTest.Fixture fixture;
        final BackgroundTaskControl control;

        RestoreHarness(DatabaseCompatibilityTest.Fixture f, BackupHistory history) {
            fixture = f;
            control = new BackgroundTaskControl(f.properties);
            var histories = mock(BackupHistoryRepository.class);
            when(histories.findById(1L)).thenReturn(Optional.of(history));
            var jobs = mock(RestoreJobRepository.class);
            var saved = new AtomicReference<RestoreJob>();
            when(jobs.insert(any())).thenAnswer(call -> {
                RestoreJob d = call.getArgument(0);
                saved.set(new RestoreJob(1, d.sourceKind(), d.sourceId(), d.sourceName(), d.sourceFilePath(), d.sourceChecksum(),
                        d.fileFormat(), d.sourceDbType(), d.targetConnectionId(), d.targetDbType(), d.conflictMode(), d.namespaceMapping(),
                        d.status(), d.phase(), d.progressCurrent(), d.progressTotal(), d.message(), d.cancelRequested(), d.actor(),
                        d.startedAt(), d.finishedAt(), d.createdAt()));
                return 1L;
            });
            when(jobs.findById(1L)).thenAnswer(call -> Optional.ofNullable(saved.get()));
            doAnswer(call -> {
                status.set(call.getArgument(1));
                message.set(call.getArgument(5));
                return null;
            }).when(jobs).updateProgress(anyLong(), anyString(), anyString(), anyLong(), any(), any(), any(), any());
            var coordinator = mock(BackupExecutionCoordinator.class);
            when(coordinator.submit(anyLong(), any(), any(), any())).thenAnswer(call -> {
                ((Runnable) call.getArgument(1)).run();
                try { ((Runnable) call.getArgument(2)).run(); }
                finally { ((Runnable) call.getArgument(3)).run(); }
                return true;
            });
            service = new RestoreService(mock(RestoreUploadRepository.class), jobs, histories, f.connections, f.guard, f.audit,
                    f.properties, new SqlRestoreTranslator(), f.dialects, coordinator, f.mapper, new NativeToolLocator(f.properties),
                    control, new LargeFileUploadGuard(f.properties), mock(BackupStorageRegistry.class));
        }

        void run(String mode) throws Exception {
            var source = new RestoreSourceRef("HISTORY", 1L);
            var preflight = service.preflight(new RestorePreflightRequest(source, 1L, fixture.type, "SQL", mode, Map.of(), null, null));
            assertThat(preflight.valid()).as("恢复预检：%s", preflight.errors()).isTrue();
            service.start(new RestoreStartRequest(preflight.planToken(), source, 1L, fixture.type, "SQL", mode, Map.of(), null, null, null), "ci");
            assertThat(control.tryAcquire(1L, "after-restore")).as("恢复结束必须释放连接的后台任务占用").isTrue();
            control.releaseCompleted(1L, "after-restore");
        }
    }
}
