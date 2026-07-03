package com.example.dbadmin.service;

import com.example.dbadmin.config.AppProperties;
import com.example.dbadmin.model.BackupTask;
import com.example.dbadmin.model.DbConnection;
import com.example.dbadmin.repo.AuditRepository;
import com.example.dbadmin.repo.BackupTaskRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BackupServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void writesDatabaseBackupAsSqlAndSkipsViews() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            connection.createStatement().execute("CREATE TABLE users(id INT PRIMARY KEY, name VARCHAR(40), note VARCHAR(80))");
            connection.createStatement().execute("CREATE TABLE roles(id INT PRIMARY KEY, name VARCHAR(40))");
            connection.createStatement().execute("CREATE VIEW user_view AS SELECT * FROM users");
            connection.createStatement().execute("INSERT INTO users(id, name, note) VALUES (1, 'Alice', 'a ''quoted'' value')");
            connection.createStatement().execute("INSERT INTO roles(id, name) VALUES (10, 'Admin')");
        }

        BackupTaskRepository repository = mock(BackupTaskRepository.class);
        BackupTask task = task("DATABASE", null, null);
        when(repository.findById(1L)).thenReturn(Optional.of(task));
        BackupService service = service(url, repository);

        service.run(1L, "admin");

        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> pathCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Long> sizeCaptor = ArgumentCaptor.forClass(Long.class);
        verify(repository).updateStatus(eq(1L), eq("SUCCESS"), messageCaptor.capture(), pathCaptor.capture(), sizeCaptor.capture());
        Path file = Path.of(pathCaptor.getValue());
        assertThat(messageCaptor.getValue()).isEqualTo("SQL 备份已生成：" + file.getFileName());
        String sql = Files.readString(file);
        assertThat(sizeCaptor.getValue()).isGreaterThan(0);
        assertThat(sql).contains("INSERT INTO \"PUBLIC\".\"USERS\"");
        assertThat(sql).contains("'a ''quoted'' value'");
        assertThat(sql).contains("INSERT INTO \"PUBLIC\".\"ROLES\"");
        assertThat(sql).doesNotContain("USER_VIEW");
    }

    @Test
    void writesSingleTableBackup() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            connection.createStatement().execute("CREATE TABLE users(id INT PRIMARY KEY, name VARCHAR(40))");
            connection.createStatement().execute("CREATE TABLE roles(id INT PRIMARY KEY)");
            connection.createStatement().execute("INSERT INTO users(id, name) VALUES (1, 'Alice')");
        }

        BackupTaskRepository repository = mock(BackupTaskRepository.class);
        when(repository.findById(1L)).thenReturn(Optional.of(task("TABLE", "PUBLIC", "USERS")));
        BackupService service = service(url, repository);

        service.run(1L, "admin");

        ArgumentCaptor<String> pathCaptor = ArgumentCaptor.forClass(String.class);
        verify(repository).updateStatus(eq(1L), eq("SUCCESS"), anyString(), pathCaptor.capture(), anyLong());
        String sql = Files.readString(Path.of(pathCaptor.getValue()));
        assertThat(sql).contains("INSERT INTO \"PUBLIC\".\"USERS\"");
        assertThat(sql).doesNotContain("\"ROLES\"");
    }

    @Test
    void rejectsDownloadWhenBackupFileIsMissing() {
        BackupTaskRepository repository = mock(BackupTaskRepository.class);
        BackupTask task = new BackupTask(1, "backup", 1, "DATABASE", null, null, null, true, "SUCCESS", "ok", tempDir.resolve("missing.sql").toString(), 10L, Instant.now());
        when(repository.findById(1L)).thenReturn(Optional.of(task));
        BackupService service = service("jdbc:h2:mem:" + UUID.randomUUID(), repository);

        assertThatThrownBy(() -> service.backupFile(1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("备份文件不存在，请重新执行备份任务。");
    }

    private BackupService service(String url, BackupTaskRepository repository) {
        ConnectionService connections = mock(ConnectionService.class);
        try {
            when(connections.open(anyLong())).thenAnswer(_invocation -> DriverManager.getConnection(url, "sa", ""));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        when(connections.require(anyLong())).thenReturn(new DbConnection(1, "Local H2", "h2", url, "sa", "", "dev", false, Instant.now(), Instant.now()));
        AppProperties properties = new AppProperties();
        properties.getBackup().setDirectory(tempDir.toString());
        return new BackupService(repository, connections, mock(AuditRepository.class), properties);
    }

    private BackupTask task(String scope, String schema, String table) {
        return new BackupTask(1, "backup", 1, scope, schema, table, null, true, null, null, null, null, null);
    }
}
