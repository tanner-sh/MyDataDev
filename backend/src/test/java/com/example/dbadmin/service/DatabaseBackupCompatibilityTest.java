package com.example.dbadmin.service;

import com.example.dbadmin.dto.ApiDtos.*;
import com.example.dbadmin.model.BackupTask;
import com.example.dbadmin.model.RestoreJob;
import com.example.dbadmin.repo.*;
import com.example.dbadmin.storage.BackupStorageRegistry;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 调用真实备份/恢复服务；仅元数据仓库及调度边界使用 mock，原生进程和目标 JDBC 都实际执行。
 *
 * <p>每个用例五分钟上限（比兼容性用例宽，这里要真起进程读写文件）。理由同那边：挂住的方式
 * 是等锁或等子进程，都不会自己超时，没有上限就只能等作业被外部取消，那时拿不到任何栈。</p>
 */
@Timeout(value = 5, unit = TimeUnit.MINUTES)
class DatabaseBackupCompatibilityTest {
    @TempDir Path directory;

    /**
     * 工具路径先看按数据库限定的变量，再退回按方法命名的那个。
     *
     * <p>MariaDB 用的是它自己的 mariadb-dump / mariadb 客户端：MySQL 8.4 的 mysqldump 打到
     * MariaDB 11 上会因为版本探测与 information_schema 差异失败，把两家指到同一个二进制
     * 等于用一个跑不通的组合去证明兼容性。</p>
     */
    private static String toolPath(String type, String tool) {
        String scoped = System.getenv("TEST_" + type.toUpperCase(java.util.Locale.ROOT) + "_" + tool + "_PATH");
        return scoped != null && !scoped.isBlank() ? scoped : System.getenv("TEST_" + tool + "_PATH");
    }

    /**
     * dump 参数按类型给。{@code --set-gtid-purged} 与 {@code --no-tablespaces} 是 mysqldump
     * 独有的，mariadb-dump 不认这两个开关，照抄过去整条命令直接报错。
     */
    private static String dumpOptions(String type, String method) {
        if (!method.equals("MYSQLDUMP")) return null;
        return type.equals("mariadb") ? "--single-transaction"
                : "--single-transaction\n--no-tablespaces\n--set-gtid-purged=OFF";
    }

    @ParameterizedTest
    @CsvSource({"mysql, SQL, false", "mysql, SQL, true", "mariadb, SQL, false", "postgresql, SQL, false",
            "sqlserver, SQL, false", "oracle, SQL, false",
            "mysql, MYSQLDUMP, false", "mariadb, MYSQLDUMP, false", "postgresql, PG_DUMP, false"})
    void backupRestoresRowsPrimaryKeyAndIndex(String type, String method, boolean noBackslashEscapes) throws Exception {
        if (!method.equals("SQL")) {
            if (DatabaseCompatibilityTest.required().contains(type)) {
                assertThat(System.getenv("TEST_NATIVE_TOOLS")).as("CI 点名了 %s，原生备份恢复不能跳过", type).isEqualTo("true");
            }
            assumeTrue(Boolean.parseBoolean(System.getenv("TEST_NATIVE_TOOLS")), "原生往返需要 TEST_NATIVE_TOOLS=true 及真实客户端工具");
        }
        try (var f = new DatabaseCompatibilityTest.Fixture(type)) {
            f.properties.getBackup().setDirectory(directory.toString());
            String table = f.table(f.pk("id") + ", " + f.varchar("name", 200) + ", " + f.decimal("amount", 20, 4));
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
                    toolPath(type, method), dumpOptions(type, method),
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
            String tool = toolPath(type, method.equals("PG_DUMP") ? "PG_RESTORE" : "MYSQL");
            var source = new RestoreSourceRef("HISTORY", 1L);
            var preflight = restore.preflight(new RestorePreflightRequest(source, 1L, type, method, "SAFE", Map.of(), tool, null));
            // 预检失败时把备份文件的开头一起报出来。这条断言只说「解析不过」，而要修的是「写出去的
            // 是什么、为什么解析器不认」—— 少了这段，每次都要再跑一轮 CI 才知道语句长什么样。
            // 只在 SQL 逻辑备份上取（原生 dump 可能是二进制），且截断，避免把整份数据刷进日志。
            String head = "";
            if (!preflight.valid() && method.equals("SQL")) {
                String script = Files.readString(Path.of(history.getValue().filePath()));
                head = "\n备份文件开头：\n" + script.substring(0, Math.min(600, script.length()));
            }
            assertThat(preflight.valid()).as("恢复预检：%s%s", preflight.errors(), head).isTrue();
            restore.start(new RestoreStartRequest(preflight.planToken(), source, 1L, type, method, "SAFE", Map.of(), tool, null, null), "ci");
            verify(jobs).updateProgress(eq(1L), eq("SUCCESS"), eq("COMPLETED"), anyLong(), any(), anyString(), isNull(), any());
            verify(jobs, never()).updateProgress(anyLong(), eq("FAILED"), anyString(), anyLong(), any(), any(), any(), any());
            try (var statement = f.jdbc.createStatement(); var rows = statement.executeQuery("SELECT * FROM " + f.q(table) + " ORDER BY id")) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getLong("id")).isEqualTo(2);
                assertThat(rows.getString("name")).isNull(); assertThat(rows.getBigDecimal("amount")).isNull();
                assertThat(rows.next()).isTrue(); assertThat(rows.getLong("id")).isEqualTo(3);
                // Oracle 的 VARCHAR2 把空串存成 NULL，往返之后读回来也只能是 NULL。
                if (f.flavor.distinguishesEmptyString()) assertThat(rows.getString("name")).isEmpty();
                else assertThat(rows.getString("name")).isNull();
                assertThat(rows.next()).isTrue(); assertThat(rows.getLong("id")).isEqualTo(9007199254740993L);
                assertThat(rows.getString("name")).isEqualTo(text);
                assertThat(rows.getBigDecimal("amount")).isEqualByComparingTo("123456789012.3456");
                assertThat(rows.next()).isFalse();
            }
            var detail = f.metadata.detail(1L, f.schema, table, true);
            assertThat(detail.primaryKeys()).containsExactly(f.col("id"));
            assertThat(detail.indexes()).extracting("name").contains(index);
        }
    }
}
