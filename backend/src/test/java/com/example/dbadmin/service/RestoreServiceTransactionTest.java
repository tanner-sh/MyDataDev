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
