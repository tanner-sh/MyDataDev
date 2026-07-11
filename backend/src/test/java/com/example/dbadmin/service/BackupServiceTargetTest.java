package com.example.dbadmin.service;

import com.example.dbadmin.config.AppProperties;
import com.example.dbadmin.core.DialectRegistry;
import com.example.dbadmin.dto.ApiDtos.BackupTaskRequest;
import com.example.dbadmin.model.BackupTask;
import com.example.dbadmin.model.DbConnection;
import com.example.dbadmin.repo.AuditRepository;
import com.example.dbadmin.repo.BackupHistoryRepository;
import com.example.dbadmin.repo.BackupTaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BackupServiceTargetTest {
    @TempDir
    Path tempDir;

    private String targetUrl;
    private JdbcTemplate targetJdbc;
    private BackupTaskRepository taskRepository;
    private BackupService service;

    @BeforeEach
    void setUp() throws Exception {
        targetUrl = "jdbc:h2:mem:backup-target-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        targetJdbc = new JdbcTemplate(new DriverManagerDataSource(targetUrl, "sa", ""));
        targetJdbc.execute("CREATE TABLE users(id INT PRIMARY KEY, name VARCHAR(40))");
        targetJdbc.execute("CREATE TABLE roles(id INT PRIMARY KEY, name VARCHAR(40))");
        targetJdbc.execute("CREATE VIEW user_view AS SELECT * FROM users");
        targetJdbc.update("INSERT INTO users(id, name) VALUES (1, 'Alice')");

        DriverManagerDataSource appDataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:backup-app-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""
        );
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(appDataSource);
        JdbcTemplate appJdbc = new JdbcTemplate(appDataSource);
        taskRepository = new BackupTaskRepository(appJdbc);
        BackupHistoryRepository historyRepository = new BackupHistoryRepository(appJdbc);
        AuditRepository auditRepository = new AuditRepository(appJdbc);
        AppProperties properties = new AppProperties();
        properties.getBackup().setDirectory(tempDir.toString());
        DbConnection model = new DbConnection(
                1, "target", "h2", targetUrl, "sa", null, "dev", false, Instant.now(), Instant.now()
        );
        service = new BackupService(
                taskRepository, historyRepository, new TestConnectionService(model, targetUrl), auditRepository,
                properties, new DialectRegistry()
        );
    }

    @Test
    void resolvesAndPersistsMultiplePhysicalTablesInRequestOrder() {
        BackupTask task = service.create(request("TABLES", "public", null, List.of("roles", "USERS")), "admin");

        assertThat(task.scope()).isEqualTo("TABLES");
        assertThat(task.schemaName()).isEqualTo("PUBLIC");
        assertThat(task.tableNames()).containsExactly("ROLES", "USERS");
        assertThat(task.tableName()).isEqualTo("ROLES");
        assertThat(taskRepository.findById(task.id()).orElseThrow().tableNames()).containsExactly("ROLES", "USERS");
    }

    @Test
    void rejectsViewsAndMissingTablesBeforePersistingTask() {
        assertThatThrownBy(() -> service.create(request("TABLES", "PUBLIC", null, List.of("USERS", "USER_VIEW", "MISSING")), "admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("USER_VIEW")
                .hasMessageContaining("MISSING");

        assertThat(taskRepository.findAll()).isEmpty();
    }

    @Test
    void schemaBackupDynamicallyIncludesTablesCreatedAfterTaskSave() throws Exception {
        BackupTask task = service.create(request("SCHEMA", "PUBLIC", null, List.of()), "admin");
        targetJdbc.execute("CREATE TABLE later(id INT PRIMARY KEY)");

        BackupTask completed = service.run(task.id(), "admin");

        assertThat(completed.lastStatus()).isEqualTo("SUCCESS");
        String sql = Files.readString(Path.of(completed.lastFilePath()));
        assertThat(sql).contains("-- Table: \"PUBLIC\".\"LATER\"");
    }

    @Test
    void failsWholeMultiTableRunWhenOneSavedTargetWasDropped() throws Exception {
        BackupTask task = service.create(request("TABLES", "PUBLIC", null, List.of("USERS", "ROLES")), "admin");
        targetJdbc.execute("DROP TABLE roles");

        assertThatThrownBy(() -> service.run(task.id(), "admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ROLES");
        assertThat(taskRepository.findById(task.id()).orElseThrow().lastStatus()).isEqualTo("FAILED");
        try (var files = Files.list(tempDir)) {
            assertThat(files.filter(path -> path.getFileName().toString().endsWith(".sql")).toList()).isEmpty();
        }
    }

    @Test
    void enforcesCanonicalRequestShapeAndLegacyNamespaceFallback() {
        assertThatThrownBy(() -> service.create(request("DATABASE", "PUBLIC", null, List.of()), "admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("全库备份不能指定");
        assertThatThrownBy(() -> service.create(request("SCHEMA", "PUBLIC", "USERS", List.of()), "admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能指定表");
        assertThatThrownBy(() -> service.create(request("TABLES", null, null, List.of("USERS")), "admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("请选择 Schema/数据库");
        assertThatThrownBy(() -> service.create(request("TABLES", "PUBLIC", "ROLES", List.of("USERS")), "admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("第一项一致");

        BackupTask legacy = service.create(request("TABLE", null, "users", List.of()), "admin");
        assertThat(legacy.scope()).isEqualTo("TABLES");
        assertThat(legacy.schemaName()).isEqualTo("PUBLIC");
        assertThat(legacy.tableNames()).containsExactly("USERS");
    }

    @Test
    void reportsAmbiguousCaseInsensitiveTableName() {
        targetJdbc.execute("CREATE TABLE \"Foo\"(id INT)");
        targetJdbc.execute("CREATE TABLE \"foo\"(id INT)");

        assertThatThrownBy(() -> service.create(request("TABLES", "PUBLIC", null, List.of("FOO")), "admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("大小写不明确");
    }

    private BackupTaskRequest request(String scope, String schemaName, String tableName, List<String> tableNames) {
        return new BackupTaskRequest(
                "backup", 1L, scope, schemaName, tableName, tableNames,
                null, false, "SQL", null, null, null
        );
    }

    private static class TestConnectionService extends ConnectionService {
        private final DbConnection model;
        private final String url;

        TestConnectionService(DbConnection model, String url) {
            super(null, null, null, null, null);
            this.model = model;
            this.url = url;
        }

        @Override
        public Connection open(long id) throws Exception {
            return DriverManager.getConnection(url, "sa", "");
        }

        @Override
        public DbConnection require(long id) {
            return model;
        }

        @Override
        public String password(long id) {
            return "";
        }
    }
}
