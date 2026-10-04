package com.example.dbadmin.service;

import com.example.dbadmin.config.AppProperties;
import com.example.dbadmin.core.DialectRegistry;
import com.example.dbadmin.model.DbConnection;
import com.example.dbadmin.model.RestoreJob;
import com.example.dbadmin.repo.AuditRepository;
import com.example.dbadmin.repo.BackupHistoryRepository;
import com.example.dbadmin.repo.RestoreJobRepository;
import com.example.dbadmin.repo.RestoreUploadRepository;
import com.example.dbadmin.storage.BackupStorageRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RestoreServiceTransactionTest {
    @Test
    void commitAcknowledgementFailureReportsUnknownEvenWhenRollbackReturnsNormally() throws Exception {
        String url = "jdbc:h2:mem:restore-ack-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        try (var c = DriverManager.getConnection(url)) { c.createStatement().execute("CREATE TABLE restored_item(id INT PRIMARY KEY)"); }
        var script = Files.createTempFile("restore-ack", ".sql");
        Files.writeString(script, "INSERT INTO restored_item VALUES(5);");
        var connections = mock(ConnectionService.class);
        when(connections.require(1L)).thenReturn(new DbConnection(1L, "test", "h2", url, null, null, "dev", false, Instant.now(), Instant.now()));
        when(connections.open(1L)).thenAnswer(ignored -> {
            var real = DriverManager.getConnection(url);
            return java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{java.sql.Connection.class}, (proxy, method, args) -> {
                try {
                    Object result = method.invoke(real, args);
                    if (method.getName().equals("commit")) throw new java.sql.SQLException("acknowledgement lost", "08006");
                    return result;
                } catch (java.lang.reflect.InvocationTargetException error) { throw error.getCause(); }
            });
        });
        var properties = new AppProperties();
        var jobs = mock(RestoreJobRepository.class);
        var service = new RestoreService(mock(RestoreUploadRepository.class), jobs, mock(BackupHistoryRepository.class), connections, mock(ExecutionGuard.class), mock(AuditRepository.class), properties,
                new SqlRestoreTranslator(), new DialectRegistry(), mock(BackupExecutionCoordinator.class), new ObjectMapper(), mock(NativeToolLocator.class), mock(BackgroundTaskControl.class), new LargeFileUploadGuard(properties), mock(BackupStorageRegistry.class));
        var checksum = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(script)));
        var job = new RestoreJob(9, "UPLOAD", 3, "ack.sql", script.toString(), checksum, "SQL", "h2", 1, "h2", "APPEND", "{}", "RUNNING", "RESTORING_DATA", 0L, 1L, null, false, "admin", Instant.now(), null, Instant.now());
        when(jobs.findById(9)).thenReturn(java.util.Optional.of(job));
        try {
            assertThatThrownBy(() -> service.runSql(job)).isInstanceOf(RestoreService.CommitOutcomeUnknownException.class).hasMessageContaining("未知");
            try (var c = DriverManager.getConnection(url); var rows = c.createStatement().executeQuery("SELECT id FROM restored_item")) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getInt(1)).isEqualTo(5);
            }
            try (var c = DriverManager.getConnection(url)) { c.createStatement().execute("DELETE FROM restored_item"); }
            var run = RestoreService.class.getDeclaredMethod("run", long.class, String.class, String.class);
            run.setAccessible(true);
            run.invoke(service, 9L, null, null);
            org.mockito.Mockito.verify(jobs).updateProgress(org.mockito.ArgumentMatchers.eq(9L), org.mockito.ArgumentMatchers.eq("FAILED"), org.mockito.ArgumentMatchers.eq("UNKNOWN"), org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.contains("不要重新"), org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.any());
        } finally { Files.deleteIfExists(script); }
    }

    @Test
    void failedSqlRestoreRollsBackStatementsBeyondTheOldBatchBoundary() throws Exception {
        String url = "jdbc:h2:mem:restore-transaction-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        try (var connection = DriverManager.getConnection(url)) {
            connection.createStatement().execute("CREATE TABLE restored_item(id INT PRIMARY KEY)");
        }
        var script = Files.createTempFile("restore-transaction-", ".sql");
        StringBuilder sql = new StringBuilder();
        for (int id = 1; id <= 501; id++) {
            sql.append("INSERT INTO restored_item(id) VALUES (").append(id).append(");\n");
        }
        sql.append("INSERT INTO missing_table(id) VALUES (502);\n");
        Files.writeString(script, sql);

        ConnectionService connections = mock(ConnectionService.class);
        DbConnection target = new DbConnection(1L, "test", "h2", url, null, null,
                "dev", false, Instant.now(), Instant.now());
        when(connections.require(1L)).thenReturn(target);
        when(connections.open(1L)).thenAnswer(ignored -> DriverManager.getConnection(url));
        AppProperties properties = new AppProperties();
        RestoreService service = new RestoreService(
                mock(RestoreUploadRepository.class), mock(RestoreJobRepository.class), mock(BackupHistoryRepository.class),
                connections, mock(ExecutionGuard.class), mock(AuditRepository.class), properties,
                new SqlRestoreTranslator(), new DialectRegistry(), mock(BackupExecutionCoordinator.class),
                new ObjectMapper(), mock(NativeToolLocator.class), mock(BackgroundTaskControl.class),
                new LargeFileUploadGuard(properties), mock(BackupStorageRegistry.class)
        );
        RestoreJob job = new RestoreJob(
                9L, "UPLOAD", 3L, "restore.sql", script.toString(), "checksum", "SQL", "h2",
                1L, "h2", "APPEND", "{}", "RUNNING", "RESTORING_DATA", 0L, 502L,
                null, false, "admin", Instant.now(), null, Instant.now()
        );

        try {
            assertThatThrownBy(() -> service.runSql(job)).hasMessageContaining("MISSING_TABLE");
            try (var connection = DriverManager.getConnection(url);
                 var rows = connection.createStatement().executeQuery("SELECT COUNT(*) FROM restored_item")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isZero();
            }
        } finally {
            Files.deleteIfExists(script);
        }
    }
}
