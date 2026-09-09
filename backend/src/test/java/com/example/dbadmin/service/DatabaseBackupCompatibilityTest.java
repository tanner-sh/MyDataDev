package com.example.dbadmin.service;

import com.example.dbadmin.dto.ApiDtos.*;
import com.example.dbadmin.model.BackupTask;
import com.example.dbadmin.model.RestoreJob;
import com.example.dbadmin.repo.*;
import com.example.dbadmin.storage.BackupStorageRegistry;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 调用真实备份/恢复服务；仅元数据仓库及调度边界使用 mock，原生进程和目标 JDBC 都实际执行。 */
class DatabaseBackupCompatibilityTest {
    @TempDir Path directory;

    @ParameterizedTest
    @CsvSource({"mysql, SQL, false", "mysql, SQL, true", "postgresql, SQL, false", "mysql, MYSQLDUMP, false", "postgresql, PG_DUMP, false"})
    void backupRestoresRowsPrimaryKeyAndIndex(String type, String method, boolean noBackslashEscapes) throws Exception {
        if (!method.equals("SQL")) {
            if (Boolean.parseBoolean(System.getenv("TEST_DATABASES_REQUIRED"))) {
                assertThat(System.getenv("TEST_NATIVE_TOOLS")).as("CI 必须执行原生备份恢复").isEqualTo("true");
            }
            assumeTrue(Boolean.parseBoolean(System.getenv("TEST_NATIVE_TOOLS")), "原生往返需要 TEST_NATIVE_TOOLS=true 及真实客户端工具");
        }
        try (var f = new DatabaseCompatibilityTest.Fixture(type)) {
            f.properties.getBackup().setDirectory(directory.toString());
            String table = f.table("id BIGINT PRIMARY KEY, name VARCHAR(200), amount DECIMAL(20,4)");
            String index = "idx_" + table;
            f.execute("CREATE INDEX " + f.dialect.quoteIdentifier(index) + " ON " + f.q(table) + " (name)");
            String text = "中文 O'Reilly\\path\n第二行";
            try (var insert = f.jdbc.prepareStatement("INSERT INTO " + f.q(table) + " VALUES (?, ?, ?)")) {
                insert.setLong(1, 9007199254740993L); insert.setString(2, text);
                insert.setBigDecimal(3, new java.math.BigDecimal("123456789012.3456")); insert.executeUpdate();
                insert.setLong(1, 2); insert.setNull(2, java.sql.Types.VARCHAR); insert.setNull(3, java.sql.Types.DECIMAL); insert.executeUpdate();
                insert.setLong(1, 3); insert.setString(2, ""); insert.executeUpdate();
            }
            var tasks = mock(BackupTaskRepository.class);
            var histories = mock(BackupHistoryRepository.class);
            var task = new BackupTask(1, "实库往返", 1, "TABLE", f.schema, table, method,
                    System.getenv("TEST_" + method + "_PATH"),
                    method.equals("MYSQLDUMP") ? "--single-transaction\n--no-tablespaces\n--set-gtid-purged=OFF" : null,
                    null, null, false, null, null, null, null, null);
            when(tasks.findById(1L)).thenReturn(Optional.of(task));
            var backup = BackupServiceTestFixture.create(tasks, histories, f.connections, f.audit, f.properties);
            backup.run(1L, "ci");
            var history = ArgumentCaptor.forClass(com.example.dbadmin.model.BackupHistory.class);
            verify(histories).insert(history.capture());
            assertThat(history.getValue().status()).isEqualTo("SUCCESS");
            assertThat(Files.size(Path.of(history.getValue().filePath()))).isPositive();
            when(histories.findById(1L)).thenReturn(Optional.of(history.getValue()));

            // 只移除本例的随机表；之后由恢复服务重建，避免直接回放 SQL 绕过产品恢复路径。
            f.execute("DROP TABLE " + f.q(table));
            if (noBackslashEscapes) f.sessionSql = "SET SESSION sql_mode='NO_BACKSLASH_ESCAPES'";
            var jobs = mock(RestoreJobRepository.class);
            var saved = new AtomicReference<RestoreJob>();
            when(jobs.insert(any())).thenAnswer(call -> {
                RestoreJob d = call.getArgument(0);
                saved.set(new RestoreJob(1, d.sourceKind(), d.sourceId(), d.sourceName(), d.sourceFilePath(), d.sourceChecksum(),
                        d.fileFormat(), d.sourceDbType(), d.targetConnectionId(), d.targetDbType(), d.conflictMode(),
                        d.namespaceMapping(), d.status(), d.phase(), d.progressCurrent(), d.progressTotal(), d.message(),
                        d.cancelRequested(), d.actor(), d.startedAt(), d.finishedAt(), d.createdAt()));
                return 1L;
            });
            when(jobs.findById(1L)).thenAnswer(call -> Optional.ofNullable(saved.get()));
            var coordinator = mock(BackupExecutionCoordinator.class);
            when(coordinator.submit(anyLong(), any(), any(), any())).thenAnswer(call -> {
                ((Runnable) call.getArgument(1)).run();
                try { ((Runnable) call.getArgument(2)).run(); }
                finally { ((Runnable) call.getArgument(3)).run(); }
                return true;
            });
            var restore = new RestoreService(mock(RestoreUploadRepository.class), jobs, histories, f.connections, f.guard,
                    f.audit, f.properties, new SqlRestoreTranslator(), f.dialects, coordinator, f.mapper,
                    new NativeToolLocator(f.properties), new BackgroundTaskControl(f.properties),
                    new LargeFileUploadGuard(f.properties), mock(BackupStorageRegistry.class));
            String tool = System.getenv("TEST_" + (method.equals("PG_DUMP") ? "PG_RESTORE" : "MYSQL") + "_PATH");
            var source = new RestoreSourceRef("HISTORY", 1L);
            var preflight = restore.preflight(new RestorePreflightRequest(source, 1L, type, method, "SAFE", Map.of(), tool, null));
            assertThat(preflight.valid()).as("恢复预检：%s", preflight.errors()).isTrue();
            restore.start(new RestoreStartRequest(preflight.planToken(), source, 1L, type, method, "SAFE", Map.of(), tool, null, null), "ci");
            verify(jobs).updateProgress(eq(1L), eq("SUCCESS"), eq("COMPLETED"), anyLong(), any(), anyString(), isNull(), any());
            verify(jobs, never()).updateProgress(anyLong(), eq("FAILED"), anyString(), anyLong(), any(), any(), any(), any());
            try (var statement = f.jdbc.createStatement(); var rows = statement.executeQuery("SELECT * FROM " + f.q(table) + " ORDER BY id")) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getLong("id")).isEqualTo(2);
                assertThat(rows.getString("name")).isNull(); assertThat(rows.getBigDecimal("amount")).isNull();
                assertThat(rows.next()).isTrue(); assertThat(rows.getLong("id")).isEqualTo(3); assertThat(rows.getString("name")).isEmpty();
                assertThat(rows.next()).isTrue(); assertThat(rows.getLong("id")).isEqualTo(9007199254740993L);
                assertThat(rows.getString("name")).isEqualTo(text);
                assertThat(rows.getBigDecimal("amount")).isEqualByComparingTo("123456789012.3456");
                assertThat(rows.next()).isFalse();
            }
            var detail = f.metadata.detail(1L, f.schema, table, true);
            assertThat(detail.primaryKeys()).containsExactly("id");
            assertThat(detail.indexes()).extracting("name").contains(index);
        }
    }
}
