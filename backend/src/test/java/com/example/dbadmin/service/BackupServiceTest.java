package com.example.dbadmin.service;

import com.example.dbadmin.config.AppProperties;
import com.example.dbadmin.model.BackupHistory;
import com.example.dbadmin.model.BackupTask;
import com.example.dbadmin.model.DbConnection;
import com.example.dbadmin.repo.AuditRepository;
import com.example.dbadmin.repo.BackupHistoryRepository;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BackupServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void createsDisabledManualTaskWithoutCron() {
        BackupTaskRepository repository = mock(BackupTaskRepository.class);
        BackupTask saved = new BackupTask(1, "manual", 1, "DATABASE", null, null, null, false, null, null, null, null, null);
        when(repository.insert(any())).thenReturn(1L);
        when(repository.findById(1L)).thenReturn(Optional.of(saved));
        BackupService service = service("jdbc:h2:mem:" + UUID.randomUUID(), repository);

        service.create(new com.example.dbadmin.dto.ApiDtos.BackupTaskRequest("manual", 1L, "DATABASE", null, null, "", false), "admin");

        ArgumentCaptor<BackupTask> taskCaptor = ArgumentCaptor.forClass(BackupTask.class);
        verify(repository).insert(taskCaptor.capture());
        assertThat(taskCaptor.getValue().scope()).isEqualTo("DATABASE");
        assertThat(taskCaptor.getValue().cron()).isNull();
        assertThat(taskCaptor.getValue().enabled()).isFalse();
    }

    @Test
    void rejectsEnabledTaskWithoutCron() {
        BackupTaskRepository repository = mock(BackupTaskRepository.class);
        BackupService service = service("jdbc:h2:mem:" + UUID.randomUUID(), repository);

        assertThatThrownBy(() -> service.create(new com.example.dbadmin.dto.ApiDtos.BackupTaskRequest("scheduled", 1L, "DATABASE", null, null, "", true), "admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("启用定时备份需要填写 cron 表达式。");
        verify(repository, never()).insert(any());
    }

    @Test
    void rejectsInvalidCronOnUpdate() {
        BackupTaskRepository repository = mock(BackupTaskRepository.class);
        when(repository.findById(1L)).thenReturn(Optional.of(task("DATABASE", null, null)));
        BackupService service = service("jdbc:h2:mem:" + UUID.randomUUID(), repository);

        assertThatThrownBy(() -> service.update(1L, new com.example.dbadmin.dto.ApiDtos.BackupTaskRequest("backup", 1L, "DATABASE", null, null, "invalid cron", true), "admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("cron 表达式不合法：invalid cron");
        verify(repository, never()).update(eq(1L), any());
    }

    @Test
    void rejectsNativeBackupWithoutToolPath() {
        BackupTaskRepository repository = mock(BackupTaskRepository.class);
        BackupService service = service("jdbc:mysql://localhost:3306/demo", "mysql", repository);

        assertThatThrownBy(() -> service.create(new com.example.dbadmin.dto.ApiDtos.BackupTaskRequest("native", 1L, "DATABASE", null, null, "", false, "MYSQLDUMP", "", null, null), "admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("原生备份需要填写工具路径。");
        verify(repository, never()).insert(any());
    }

    @Test
    void rejectsDangerousNativeExtraArgs() {
        BackupTaskRepository repository = mock(BackupTaskRepository.class);
        BackupService service = service("jdbc:mysql://localhost:3306/demo", "mysql", repository);

        for (String argument : List.of("--result-file=/tmp/out.sql", "-psecret", "-P3307", "file=/tmp/out.sql", "parfile=/tmp/evil.par")) {
            assertThatThrownBy(() -> service.create(new com.example.dbadmin.dto.ApiDtos.BackupTaskRequest("native", 1L, "DATABASE", null, null, "", false, "MYSQLDUMP", "/usr/bin/mysqldump", argument, null), "admin"))
                    .as(argument)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("备份额外参数不能覆盖系统控制参数");
        }
        verify(repository, never()).insert(any());
    }

    @Test
    void runsMysqlDumpWithEnvironmentPasswordAndControlledOutput() throws Exception {
        Path script = tempDir.resolve("mysqldump");
        Files.writeString(script, """
                #!/bin/sh
                for arg in "$@"; do
                  case "$arg" in --result-file=*) out="${arg#--result-file=}" ;; esac
                done
                printf 'pwd=%s\\nargs=%s\\n' "$MYSQL_PWD" "$*" > "$out"
                exit 0
                """);
        script.toFile().setExecutable(true);
        BackupTaskRepository repository = mock(BackupTaskRepository.class);
        BackupTask task = new BackupTask(1, "native", 1, "DATABASE", null, null, "MYSQLDUMP", script.toString(), "--single-transaction", null, null, false, null, null, null, null, null);
        when(repository.findById(1L)).thenReturn(Optional.of(task));
        BackupService service = service("jdbc:mysql://db.example.com:3307/demo?useSSL=false", "mysql", repository);

        service.run(1L, "admin");

        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> pathCaptor = ArgumentCaptor.forClass(String.class);
        verify(repository).updateStatus(eq(1L), eq("SUCCESS"), messageCaptor.capture(), pathCaptor.capture(), anyLong());
        String dump = Files.readString(Path.of(pathCaptor.getValue()));
        assertThat(messageCaptor.getValue()).startsWith("mysqldump 备份已生成：");
        assertThat(dump).contains("pwd=secret");
        assertThat(dump).contains("--host=db.example.com");
        assertThat(dump).contains("--port=3307");
        assertThat(dump).contains("--user=sa");
        assertThat(dump).contains("--single-transaction");
        assertThat(dump).contains("demo");
    }

    @Test
    void keepsOraclePasswordOutOfProcessArgumentsAndUsesAParameterFile() throws Exception {
        Path script = tempDir.resolve("exp");
        Files.writeString(script, """
                #!/bin/sh
                printf '%s\n' "$@" > "$0.args"
                for arg in "$@"; do
                  case "$arg" in parfile=*) parfile="${arg#parfile=}" ;; esac
                done
                cp "$parfile" "$0.par"
                out=$(sed -n 's/^file=//p' "$parfile")
                : > "$out"
                exit 0
                """);
        script.toFile().setExecutable(true);
        BackupTaskRepository repository = mock(BackupTaskRepository.class);
        BackupTask task = new BackupTask(1, "oracle", 1, "DATABASE", null, null, "ORACLE_EXP", script.toString(), null, "//localhost:1521/ORCLPDB1", null, false, null, null, null, null, null);
        when(repository.findById(1L)).thenReturn(Optional.of(task));
        BackupService service = service("jdbc:oracle:thin:@//localhost:1521/ORCLPDB1", "oracle", repository);

        service.run(1L, "admin");

        String arguments = Files.readString(Path.of(script + ".args"));
        String parameterFile = Files.readString(Path.of(script + ".par"));
        assertThat(arguments).contains("parfile=").doesNotContain("secret").doesNotContain("userid=");
        assertThat(parameterFile).contains("userid=sa/secret@//localhost:1521/ORCLPDB1");
        assertThat(parameterFile).contains("full=y");
    }

    @Test
    void scrubsNativeBackupPasswordFromFailureMessage() throws Exception {
        Path script = tempDir.resolve("exp");
        Files.writeString(script, """
                #!/bin/sh
                echo 'failed with secret'
                exit 2
                """);
        script.toFile().setExecutable(true);
        BackupTaskRepository repository = mock(BackupTaskRepository.class);
        BackupHistoryRepository historyRepository = mock(BackupHistoryRepository.class);
        BackupTask task = new BackupTask(1, "oracle", 1, "DATABASE", null, null, "ORACLE_EXP", script.toString(), null, "//localhost:1521/ORCLPDB1", null, false, null, null, null, null, null);
        when(repository.findById(1L)).thenReturn(Optional.of(task));
        BackupService service = service("jdbc:oracle:thin:@//localhost:1521/ORCLPDB1", "oracle", repository, historyRepository);

        assertThatThrownBy(() -> service.run(1L, "admin"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("******")
                .hasMessageNotContaining("secret");
        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(repository).updateStatus(eq(1L), eq("FAILED"), messageCaptor.capture());
        assertThat(messageCaptor.getValue()).doesNotContain("secret");
        ArgumentCaptor<BackupHistory> historyCaptor = ArgumentCaptor.forClass(BackupHistory.class);
        verify(historyRepository).insert(historyCaptor.capture());
        assertThat(historyCaptor.getValue().status()).isEqualTo("FAILED");
        assertThat(historyCaptor.getValue().message()).doesNotContain("secret");
    }

    @Test
    void enablingTaskRequiresCron() {
        BackupTaskRepository repository = mock(BackupTaskRepository.class);
        when(repository.findById(1L)).thenReturn(Optional.of(new BackupTask(1, "manual", 1, "DATABASE", null, null, null, false, null, null, null, null, null)));
        BackupService service = service("jdbc:h2:mem:" + UUID.randomUUID(), repository);

        assertThatThrownBy(() -> service.setEnabled(1L, true, "admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("启用定时备份需要填写 cron 表达式。");
        verify(repository, never()).updateEnabled(eq(1L), eq(true));
    }

    @Test
    void deleteTaskKeepsBackupFileByDefault() throws Exception {
        Path file = tempDir.resolve("backup.sql");
        Files.writeString(file, "select 1");
        BackupTaskRepository repository = mock(BackupTaskRepository.class);
        BackupHistoryRepository historyRepository = mock(BackupHistoryRepository.class);
        when(repository.findById(1L)).thenReturn(Optional.of(new BackupTask(1, "backup", 1, "DATABASE", null, null, null, false, "SUCCESS", "ok", file.toString(), 8L, Instant.now())));
        BackupService service = service("jdbc:h2:mem:" + UUID.randomUUID(), repository, historyRepository);

        service.delete(1L, false, "admin");

        assertThat(file).exists();
        verify(historyRepository).deleteByTaskId(1L);
        verify(repository).delete(1L);
    }

    @Test
    void deleteTaskCanDeleteHistoryBackupFiles() throws Exception {
        Path file = tempDir.resolve("backup.sql");
        Files.writeString(file, "select 1");
        BackupTaskRepository repository = mock(BackupTaskRepository.class);
        BackupHistoryRepository historyRepository = mock(BackupHistoryRepository.class);
        when(repository.findById(1L)).thenReturn(Optional.of(new BackupTask(1, "backup", 1, "DATABASE", null, null, null, false, "SUCCESS", "ok", file.toString(), 8L, Instant.now())));
        when(historyRepository.findPageByTaskId(1L, 100, 0L)).thenReturn(List.of(new BackupHistory(1, 1, 1, "SUCCESS", "ok", file.toString(), 8L, Instant.now(), Instant.now())));
        BackupService service = service("jdbc:h2:mem:" + UUID.randomUUID(), repository, historyRepository);

        service.delete(1L, true, "admin");

        assertThat(file).doesNotExist();
        verify(historyRepository).deleteByTaskId(1L);
        verify(repository).delete(1L);
    }

    @Test
    void deleteTaskRejectsFileOutsideBackupDirectory() {
        Path outside = tempDir.getParent().resolve("outside-" + UUID.randomUUID() + ".sql");
        BackupTaskRepository repository = mock(BackupTaskRepository.class);
        BackupHistoryRepository historyRepository = mock(BackupHistoryRepository.class);
        when(repository.findById(1L)).thenReturn(Optional.of(new BackupTask(1, "backup", 1, "DATABASE", null, null, null, false, "SUCCESS", "ok", outside.toString(), 8L, Instant.now())));
        when(historyRepository.findPageByTaskId(1L, 100, 0L)).thenReturn(List.of(new BackupHistory(1, 1, 1, "SUCCESS", "ok", outside.toString(), 8L, Instant.now(), Instant.now())));
        BackupService service = service("jdbc:h2:mem:" + UUID.randomUUID(), repository, historyRepository);

        assertThatThrownBy(() -> service.delete(1L, true, "admin"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("备份文件路径不在允许目录内。");
        verify(historyRepository, never()).deleteByTaskId(1L);
        verify(repository, never()).delete(1L);
    }

    @Test
    void writesDatabaseBackupAsSqlAndSkipsViews() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            connection.createStatement().execute("CREATE TABLE users(id INT PRIMARY KEY, name VARCHAR(40), note VARCHAR(80))");
            connection.createStatement().execute("CREATE TABLE roles(id INT PRIMARY KEY, name VARCHAR(40))");
            connection.createStatement().execute("CREATE SCHEMA audit");
            connection.createStatement().execute("CREATE TABLE audit.events(id INT PRIMARY KEY, detail VARCHAR(40))");
            connection.createStatement().execute("CREATE VIEW user_view AS SELECT * FROM users");
            connection.createStatement().execute("INSERT INTO users(id, name, note) VALUES (1, 'Alice', 'a ''quoted'' value')");
            connection.createStatement().execute("INSERT INTO roles(id, name) VALUES (10, 'Admin')");
            connection.createStatement().execute("INSERT INTO audit.events(id, detail) VALUES (20, 'created')");
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
        assertThat(sql).contains("INSERT INTO \"AUDIT\".\"EVENTS\"");
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
    void recordsSuccessfulBackupHistory() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            connection.createStatement().execute("CREATE TABLE users(id INT PRIMARY KEY, name VARCHAR(40))");
            connection.createStatement().execute("INSERT INTO users(id, name) VALUES (1, 'Alice')");
        }
        BackupTaskRepository repository = mock(BackupTaskRepository.class);
        BackupHistoryRepository historyRepository = mock(BackupHistoryRepository.class);
        when(repository.findById(1L)).thenReturn(Optional.of(task("TABLE", "PUBLIC", "USERS")));
        BackupService service = service(url, repository, historyRepository);

        service.run(1L, "admin");

        ArgumentCaptor<BackupHistory> historyCaptor = ArgumentCaptor.forClass(BackupHistory.class);
        verify(historyRepository).insert(historyCaptor.capture());
        BackupHistory history = historyCaptor.getValue();
        assertThat(history.taskId()).isEqualTo(1L);
        assertThat(history.connectionId()).isEqualTo(1L);
        assertThat(history.status()).isEqualTo("SUCCESS");
        assertThat(history.filePath()).isNotBlank();
        assertThat(history.fileSize()).isGreaterThan(0);
        assertThat(history.startedAt()).isNotNull();
        assertThat(history.finishedAt()).isNotNull();
    }

    @Test
    void deleteHistoryKeepsFileByDefaultAndRefreshesTaskSummary() throws Exception {
        Path oldFile = tempDir.resolve("old.sql");
        Files.writeString(oldFile, "old");
        BackupTaskRepository repository = mock(BackupTaskRepository.class);
        BackupHistoryRepository historyRepository = mock(BackupHistoryRepository.class);
        BackupTask task = new BackupTask(1, "backup", 1, "DATABASE", null, null, null, false, "SUCCESS", "old", oldFile.toString(), 3L, Instant.now());
        BackupHistory deleted = new BackupHistory(1, 1, 1, "SUCCESS", "old", oldFile.toString(), 3L, Instant.parse("2026-07-03T00:00:00Z"), Instant.parse("2026-07-03T00:01:00Z"));
        BackupHistory latest = new BackupHistory(2, 1, 1, "FAILED", "new failure", null, null, Instant.parse("2026-07-04T00:00:00Z"), Instant.parse("2026-07-04T00:01:00Z"));
        when(repository.findById(1L)).thenReturn(Optional.of(task));
        when(historyRepository.findByTaskIdAndId(1L, 1L)).thenReturn(Optional.of(deleted));
        when(historyRepository.findPageByTaskId(1L, 1, 0L)).thenReturn(List.of(latest));
        BackupService service = service("jdbc:h2:mem:" + UUID.randomUUID(), repository, historyRepository);

        service.deleteHistory(1L, 1L, false, "admin");

        assertThat(oldFile).exists();
        verify(historyRepository).delete(1L);
        verify(repository).updateSummary(1L, "FAILED", "new failure", null, null, latest.finishedAt());
    }

    @Test
    void deleteHistoryCanDeleteBackupFileAndClearTaskSummary() throws Exception {
        Path file = tempDir.resolve("history.sql");
        Files.writeString(file, "select 1");
        BackupTaskRepository repository = mock(BackupTaskRepository.class);
        BackupHistoryRepository historyRepository = mock(BackupHistoryRepository.class);
        BackupTask task = new BackupTask(1, "backup", 1, "DATABASE", null, null, null, false, "SUCCESS", "ok", file.toString(), 8L, Instant.now());
        BackupHistory history = new BackupHistory(1, 1, 1, "SUCCESS", "ok", file.toString(), 8L, Instant.now(), Instant.now());
        when(repository.findById(1L)).thenReturn(Optional.of(task));
        when(historyRepository.findByTaskIdAndId(1L, 1L)).thenReturn(Optional.of(history));
        when(historyRepository.findPageByTaskId(1L, 1, 0L)).thenReturn(List.of());
        BackupService service = service("jdbc:h2:mem:" + UUID.randomUUID(), repository, historyRepository);

        service.deleteHistory(1L, 1L, true, "admin");

        assertThat(file).doesNotExist();
        verify(repository).updateSummary(1L, null, null, null, null, null);
    }

    @Test
    void rejectsHistoryDownloadOutsideBackupDirectory() {
        Path outside = tempDir.getParent().resolve("outside-" + UUID.randomUUID() + ".sql");
        BackupTaskRepository repository = mock(BackupTaskRepository.class);
        BackupHistoryRepository historyRepository = mock(BackupHistoryRepository.class);
        BackupHistory history = new BackupHistory(1, 1, 1, "SUCCESS", "ok", outside.toString(), 8L, Instant.now(), Instant.now());
        when(historyRepository.findByTaskIdAndId(1L, 1L)).thenReturn(Optional.of(history));
        BackupService service = service("jdbc:h2:mem:" + UUID.randomUUID(), repository, historyRepository);

        assertThatThrownBy(() -> service.historyFile(1L, 1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("备份文件路径不在允许目录内。");
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

    @Test
    void writesClobValuesWithoutTruncation() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        String largeText = "prefix '" + "x".repeat(12_000) + " suffix";
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            connection.createStatement().execute("CREATE TABLE docs(id INT PRIMARY KEY, body CLOB)");
            try (var statement = connection.prepareStatement("INSERT INTO docs(id, body) VALUES (?, ?)")) {
                statement.setInt(1, 1);
                statement.setString(2, largeText);
                statement.executeUpdate();
            }
        }

        BackupTaskRepository repository = mock(BackupTaskRepository.class);
        when(repository.findById(1L)).thenReturn(Optional.of(task("TABLE", "PUBLIC", "DOCS")));
        BackupService service = service(url, repository);

        service.run(1L, "admin");

        ArgumentCaptor<String> pathCaptor = ArgumentCaptor.forClass(String.class);
        verify(repository).updateStatus(eq(1L), eq("SUCCESS"), anyString(), pathCaptor.capture(), anyLong());
        String sql = Files.readString(Path.of(pathCaptor.getValue()));
        assertThat(sql).contains("x".repeat(12_000));
        assertThat(sql).contains("prefix ''");
        assertThat(sql).contains(" suffix");
    }

    @Test
    void failsBackupForBlobColumnsInsteadOfWritingNull() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            connection.createStatement().execute("CREATE TABLE files(id INT PRIMARY KEY, payload BLOB)");
            try (var statement = connection.prepareStatement("INSERT INTO files(id, payload) VALUES (?, ?)")) {
                statement.setInt(1, 1);
                statement.setBytes(2, new byte[]{1, 2, 3});
                statement.executeUpdate();
            }
        }

        BackupTaskRepository repository = mock(BackupTaskRepository.class);
        when(repository.findById(1L)).thenReturn(Optional.of(task("TABLE", "PUBLIC", "FILES")));
        BackupService service = service(url, repository);

        assertThatThrownBy(() -> service.run(1L, "admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SQL 备份暂不支持二进制字段");
        verify(repository).updateStatus(eq(1L), eq("FAILED"), contains("SQL 备份暂不支持二进制字段"));
    }

    @Test
    void failsBackupForBinaryColumnsInsteadOfWritingNull() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            connection.createStatement().execute("CREATE TABLE files(id INT PRIMARY KEY, payload VARBINARY(16))");
            try (var statement = connection.prepareStatement("INSERT INTO files(id, payload) VALUES (?, ?)")) {
                statement.setInt(1, 1);
                statement.setBytes(2, new byte[]{4, 5, 6});
                statement.executeUpdate();
            }
        }

        BackupTaskRepository repository = mock(BackupTaskRepository.class);
        when(repository.findById(1L)).thenReturn(Optional.of(task("TABLE", "PUBLIC", "FILES")));
        BackupService service = service(url, repository);

        assertThatThrownBy(() -> service.run(1L, "admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SQL 备份暂不支持二进制字段");
        verify(repository).updateStatus(eq(1L), eq("FAILED"), contains("SQL 备份暂不支持二进制字段"));
    }

    private BackupService service(String url, BackupTaskRepository repository) {
        return service(url, "h2", repository, mock(BackupHistoryRepository.class));
    }

    private BackupService service(String url, BackupTaskRepository repository, BackupHistoryRepository historyRepository) {
        return service(url, "h2", repository, historyRepository);
    }

    private BackupService service(String url, String dbType, BackupTaskRepository repository) {
        return service(url, dbType, repository, mock(BackupHistoryRepository.class));
    }

    private BackupService service(String url, String dbType, BackupTaskRepository repository, BackupHistoryRepository historyRepository) {
        ConnectionService connections = mock(ConnectionService.class);
        try {
            when(connections.open(anyLong())).thenAnswer(_invocation -> DriverManager.getConnection(url, "sa", ""));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        when(connections.require(anyLong())).thenReturn(new DbConnection(1, "Local DB", dbType, url, "sa", "", "dev", false, Instant.now(), Instant.now()));
        when(connections.password(anyLong())).thenReturn("secret");
        AppProperties properties = new AppProperties();
        properties.getBackup().setDirectory(tempDir.toString());
        return new BackupService(repository, historyRepository, connections, mock(AuditRepository.class), properties);
    }

    private BackupTask task(String scope, String schema, String table) {
        return new BackupTask(1, "backup", 1, scope, schema, table, null, true, null, null, null, null, null);
    }
}
