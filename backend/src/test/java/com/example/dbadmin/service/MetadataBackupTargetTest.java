package com.example.dbadmin.service;

import com.example.dbadmin.core.DialectRegistry;
import com.example.dbadmin.dto.ApiDtos.BackupTargetPage;
import com.example.dbadmin.model.DbConnection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MetadataBackupTargetTest {
    private JdbcTemplate jdbc;
    private MetadataService service;

    @BeforeEach
    void setUp() {
        String url = "jdbc:h2:mem:metadata-backup-target-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url, "sa", ""));
        jdbc.execute("CREATE TABLE beta(id INT)");
        jdbc.execute("CREATE TABLE alpha(id INT)");
        jdbc.execute("CREATE VIEW alpha_view AS SELECT * FROM alpha");
        DbConnection model = new DbConnection(
                1, "target", "h2", url, "sa", null, "dev", false, Instant.now(), Instant.now()
        );
        service = new MetadataService(
                new TestConnectionService(model, url), new DialectRegistry(), null, new MetadataCacheService()
        );
    }

    @Test
    void returnsOnlyPhysicalTablesWithStableSearchAndPagination() throws Exception {
        BackupTargetPage namespaces = service.backupTargetNamespaces(1, "pub", 0, 10, false);
        assertThat(namespaces.namespaceKind()).isEqualTo("SCHEMA");
        assertThat(namespaces.currentNamespace()).isEqualTo("PUBLIC");
        assertThat(namespaces.items()).singleElement().satisfies(item -> {
            assertThat(item.name()).isEqualTo("PUBLIC");
            assertThat(item.current()).isTrue();
        });

        BackupTargetPage firstPage = service.backupTargetTables(1, "public", null, 0, 1, false);
        assertThat(firstPage.namespaceName()).isEqualTo("PUBLIC");
        assertThat(firstPage.total()).isEqualTo(2);
        assertThat(firstPage.hasMore()).isTrue();
        assertThat(firstPage.items().get(0).name()).isEqualTo("ALPHA");

        BackupTargetPage search = service.backupTargetTables(1, "PUBLIC", "bet", 0, 10, false);
        assertThat(search.items()).singleElement().satisfies(item -> assertThat(item.name()).isEqualTo("BETA"));
        assertThat(search.items()).noneMatch(item -> item.name().equals("ALPHA_VIEW"));
    }

    @Test
    void doesNotSilentlyTruncateMoreThanFiveHundredNamespaces() throws Exception {
        for (int index = 0; index < 501; index++) {
            jdbc.execute("CREATE SCHEMA S" + String.format("%03d", index));
        }

        BackupTargetPage first = service.backupTargetNamespaces(1, null, 0, 500, true);
        BackupTargetPage second = service.backupTargetNamespaces(1, null, 1, 500, false);

        assertThat(first.total()).isGreaterThan(500);
        assertThat(first.items()).hasSize(500);
        assertThat(first.hasMore()).isTrue();
        assertThat(second.items()).isNotEmpty();
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
    }
}
