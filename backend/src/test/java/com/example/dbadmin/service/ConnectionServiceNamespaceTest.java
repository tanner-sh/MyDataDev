package com.example.dbadmin.service;

import com.example.dbadmin.core.DialectRegistry;
import com.example.dbadmin.model.DbConnection;
import com.example.dbadmin.repo.AuditRepository;
import com.example.dbadmin.repo.BackupTaskRepository;
import com.example.dbadmin.repo.ConnectionRepository;
import com.example.dbadmin.repo.RestoreJobRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 归还池化连接时必须还原命名空间。Hikari 只在 HikariConfig 配置了 catalog/schema 时才重置
 * 这两项，而远程连接池刻意不配置它们，所以还原是本项目自己的责任。
 */
class ConnectionServiceNamespaceTest {
    private static final long CONNECTION_ID = 11L;

    private RemoteDataSourceRegistry dataSources;
    private ConnectionService service;
    private String jdbcUrl;

    @BeforeEach
    void setUp() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        jdbcUrl = url;
        DbConnection row = new DbConnection(
                CONNECTION_ID, "h2", "h2", url, "sa", "cipher", "dev", false, Instant.EPOCH, Instant.EPOCH
        );
        ConnectionRepository repository = mock(ConnectionRepository.class);
        when(repository.findById(CONNECTION_ID)).thenReturn(Optional.of(row));
        CryptoService crypto = mock(CryptoService.class);
        when(crypto.decrypt("cipher")).thenReturn("");

        dataSources = new RemoteDataSourceRegistry();
        service = new ConnectionService(
                repository, crypto, mock(AuditRepository.class), mock(BackupTaskRepository.class),
                mock(MetadataCacheService.class), dataSources, new DialectRegistry(), mock(RestoreJobRepository.class), new SqlTransactionRegistry(),
                new SqlScriptSplitter(), new SqlStatementClassifier()
        );
        try (Connection connection = service.open(CONNECTION_ID)) {
            connection.createStatement().execute("CREATE SCHEMA reporting");
        }
    }

    /** 同一个库、同一个连接池，只是连接配置上带了默认命名空间。 */
    private ConnectionService serviceWithDefaultSchema(String defaultSchema) {
        DbConnection row = new DbConnection(
                CONNECTION_ID, "h2", "h2", jdbcUrl, "sa", "cipher", "dev", false,
                null, null, defaultSchema, null, null, Instant.EPOCH, Instant.EPOCH
        );
        ConnectionRepository repository = mock(ConnectionRepository.class);
        when(repository.findById(CONNECTION_ID)).thenReturn(Optional.of(row));
        CryptoService crypto = mock(CryptoService.class);
        when(crypto.decrypt("cipher")).thenReturn("");
        return new ConnectionService(
                repository, crypto, mock(AuditRepository.class), mock(BackupTaskRepository.class),
                mock(MetadataCacheService.class), dataSources, new DialectRegistry(), mock(RestoreJobRepository.class),
                new SqlTransactionRegistry(), new SqlScriptSplitter(), new SqlStatementClassifier()
        );
    }

    @AfterEach
    void tearDown() {
        dataSources.close();
    }

    @Test
    void restoresTheSchemaBeforeReturningTheConnectionToThePool() throws Exception {
        try (Connection scoped = service.open(CONNECTION_ID, "REPORTING")) {
            assertThat(scoped.getSchema()).isEqualTo("REPORTING");
        }

        try (Connection reused = service.open(CONNECTION_ID)) {
            assertThat(reused.getSchema()).isEqualTo("PUBLIC");
        }
    }

    @Test
    void keepsThePoolWarmWhenTheNamespaceWasRestored() throws Exception {
        try (Connection scoped = service.open(CONNECTION_ID, "REPORTING")) {
            assertThat(scoped.getSchema()).isEqualTo("REPORTING");
        }

        assertThat(dataSources.size()).isEqualTo(1);
    }

    @Test
    void leavesTheConnectionUsableAfterRestoring() throws Exception {
        try (Connection scoped = service.open(CONNECTION_ID, "REPORTING")) {
            scoped.createStatement().execute("CREATE TABLE monthly(id INT)");
        }

        try (Connection reused = service.open(CONNECTION_ID);
             var tables = reused.getMetaData().getTables(null, "REPORTING", "MONTHLY", null)) {
            assertThat(tables.next()).isTrue();
        }
    }

    /**
     * 默认命名空间的回落不能只发生在带 schema 参数的重载上：SQL 文件执行、导出、备份都调用
     * 不带参数的那一个，绕过去就意味着脚本里的无限定表名落到登录账号的默认库。
     */
    @Test
    void appliesTheConfiguredDefaultNamespaceWhenNoneIsRequested() throws Exception {
        try (Connection connection = service.open(CONNECTION_ID)) {
            connection.createStatement().execute("CREATE SCHEMA IF NOT EXISTS analytics");
        }
        ConnectionService withDefault = serviceWithDefaultSchema("ANALYTICS");

        try (Connection connection = withDefault.open(CONNECTION_ID)) {
            assertThat(connection.getSchema()).isEqualTo("ANALYTICS");
        }
        // 归还后仍要还原，否则下一个借用者会静默继承这个命名空间。
        try (Connection reused = service.open(CONNECTION_ID)) {
            assertThat(reused.getSchema()).isEqualTo("PUBLIC");
        }
    }

    @Test
    void closingTheScopedConnectionReportsItAsClosed() throws Exception {
        Connection scoped = service.open(CONNECTION_ID, "REPORTING");
        scoped.close();

        assertThat(scoped.isClosed()).isTrue();
    }
}
