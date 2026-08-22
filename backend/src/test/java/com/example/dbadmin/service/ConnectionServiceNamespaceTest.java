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

    @BeforeEach
    void setUp() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
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

    @Test
    void closingTheScopedConnectionReportsItAsClosed() throws Exception {
        Connection scoped = service.open(CONNECTION_ID, "REPORTING");
        scoped.close();

        assertThat(scoped.isClosed()).isTrue();
    }
}
