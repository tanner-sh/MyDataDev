package com.example.dbadmin.service;

import com.example.dbadmin.api.ApiProblemException;
import com.example.dbadmin.core.DialectRegistry;
import com.example.dbadmin.dto.ApiDtos.MetadataResponse;
import com.example.dbadmin.dto.ApiDtos.ObjectDetail;
import com.example.dbadmin.dto.ApiDtos.ObjectRelations;
import com.example.dbadmin.dto.ApiDtos.ObjectStructure;
import com.example.dbadmin.dto.ApiDtos.TableDesignRequest;
import com.example.dbadmin.dto.ApiDtos.TableLifecycleRequest;
import com.example.dbadmin.dto.ApiDtos.ColumnDesign;
import com.example.dbadmin.dto.ApiDtos.IndexDesign;
import com.example.dbadmin.model.DbConnection;
import com.example.dbadmin.repo.AuditRepository;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;

class MetadataServiceTest {
    @Test
    void readsColumnMetadataInJdbcOrderForOracleStreamValues() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("COLUMN_NAME")).thenReturn("STATUS");
        when(rs.getString("TYPE_NAME")).thenReturn("VARCHAR2");
        when(rs.getInt("COLUMN_SIZE")).thenReturn(20);
        when(rs.getInt("NULLABLE")).thenReturn(java.sql.DatabaseMetaData.columnNullable);
        when(rs.getString("REMARKS")).thenReturn("状态");
        when(rs.getString("COLUMN_DEF")).thenReturn("'READY'");
        when(rs.getInt("ORDINAL_POSITION")).thenReturn(3);

        var column = MetadataService.readColumnInfo(rs);

        assertThat(column.name()).isEqualTo("STATUS");
        assertThat(column.defaultValue()).isEqualTo("'READY'");
        assertThat(column.ordinalPosition()).isEqualTo(3);
        var ordered = inOrder(rs);
        ordered.verify(rs).getString("COLUMN_NAME");
        ordered.verify(rs).getString("TYPE_NAME");
        ordered.verify(rs).getInt("COLUMN_SIZE");
        ordered.verify(rs).getInt("NULLABLE");
        ordered.verify(rs).getString("REMARKS");
        ordered.verify(rs).getString("COLUMN_DEF");
        ordered.verify(rs).getInt("ORDINAL_POSITION");
    }

    @Test
    void readsIndexMetadataBeforeOracleFilterConditionStreamCloses() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getBoolean("NON_UNIQUE")).thenReturn(false);
        when(rs.getString("INDEX_NAME")).thenReturn("PK_ASSET");
        when(rs.getInt("ORDINAL_POSITION")).thenReturn(1);
        when(rs.getString("COLUMN_NAME")).thenReturn("ID");
        when(rs.getString("FILTER_CONDITION")).thenReturn(null);

        var index = MetadataService.readIndexMetadata(rs);

        assertThat(index.name()).isEqualTo("PK_ASSET");
        assertThat(index.columnName()).isEqualTo("ID");
        assertThat(index.nonUnique()).isFalse();
        var ordered = inOrder(rs);
        ordered.verify(rs).getBoolean("NON_UNIQUE");
        ordered.verify(rs).getString("INDEX_NAME");
        ordered.verify(rs).getInt("ORDINAL_POSITION");
        ordered.verify(rs).getString("COLUMN_NAME");
        ordered.verify(rs).getString("FILTER_CONDITION");
    }

    @Test
    void inspectLoadsObjectSummariesWithoutColumnsOrIndexes() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            connection.createStatement().execute("CREATE TABLE alpha_users(id BIGINT PRIMARY KEY, name VARCHAR(40) NOT NULL)");
            connection.createStatement().execute("CREATE INDEX idx_alpha_users_name ON alpha_users(name)");
            connection.createStatement().execute("CREATE TABLE alpha_orders(id BIGINT PRIMARY KEY)");
            connection.createStatement().execute("CREATE TABLE beta_events(id BIGINT PRIMARY KEY)");
        }
        MetadataService service = service(url);
        MetadataResponse response = service.inspect(1L, null, "alpha", 0, 1, false);

        assertThat(response.page()).isZero();
        assertThat(response.namespaceKind()).isEqualTo("SCHEMA");
        assertThat(response.pageSize()).isEqualTo(1);
        assertThat(response.hasMore()).isTrue();
        assertThat(response.totalObjects()).isEqualTo(2);
        assertThat(response.totalObjectsExact()).isFalse();
        assertThat(response.cacheHit()).isFalse();
        assertThat(response.cachedAt()).isNotBlank();
        assertThat(response.objects()).hasSize(1);
        assertThat(response.objects().get(0).name()).contains("ALPHA");
        assertThat(response.objects().get(0).columns()).isEmpty();
        assertThat(response.objects().get(0).indexes()).isEmpty();

        MetadataResponse lastPage = service.inspect(1L, response.selectedSchema(), "alpha", 1, 1, false);
        assertThat(lastPage.hasMore()).isFalse();
        assertThat(lastPage.totalObjectsExact()).isTrue();
        assertThat(lastPage.totalObjects()).isEqualTo(2);

        var completionCatalog = service.completionCatalog(1L, response.selectedSchema(), false);
        assertThat(completionCatalog.namespaceKind()).isEqualTo("SCHEMA");
        assertThat(completionCatalog.selectedSchema()).isEqualTo(response.selectedSchema());
        assertThat(completionCatalog.objects()).extracting("name")
                .contains("ALPHA_USERS", "ALPHA_ORDERS", "BETA_EVENTS");
        assertThat(completionCatalog.objects()).allMatch(object -> object.columns().isEmpty() && object.indexes().isEmpty());
    }

    @Test
    void unfilteredFirstPageDoesNotScanTheWholeObjectCatalogForAnExactCount() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            for (int index = 0; index < 10; index++) {
                connection.createStatement().execute("CREATE TABLE perf_table_" + index + "(id BIGINT PRIMARY KEY)");
            }
        }

        MetadataResponse response = service(url).inspect(1L, "PUBLIC", null, 0, 2, false);

        assertThat(response.objects()).hasSize(2);
        assertThat(response.hasMore()).isTrue();
        assertThat(response.totalObjects()).isEqualTo(3);
        assertThat(response.totalObjectsExact()).isFalse();
    }

    @Test
    void coldInspectionReusesOneJdbcConnectionForSchemasAndFirstPage() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            connection.createStatement().execute("CREATE TABLE users(id BIGINT PRIMARY KEY)");
        }
        ConnectionService connections = mock(ConnectionService.class);
        when(connections.open(1L)).thenAnswer(ignored -> DriverManager.getConnection(url, "sa", ""));
        when(connections.require(1L)).thenReturn(new DbConnection(
                1L, "h2", "h2", url, "sa", "", "dev", false, Instant.now(), Instant.now()
        ));
        MetadataService service = new MetadataService(
                connections, new DialectRegistry(), mock(AuditRepository.class), new MetadataCacheService(), new ExecutionGuard()
        );

        MetadataResponse response = service.inspect(1L, null, null, 0, 200, false);

        assertThat(response.objects()).extracting("name").contains("USERS");
        verify(connections, times(1)).open(1L);
    }

    @Test
    void oracleInspectionUsesNativePagedObjectCatalog() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=Oracle;DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            connection.createStatement().execute("CREATE TABLE ALL_OBJECTS(OWNER VARCHAR2(128), OBJECT_NAME VARCHAR2(128), OBJECT_TYPE VARCHAR2(30))");
            connection.createStatement().execute("INSERT INTO ALL_OBJECTS VALUES ('PUBLIC', 'ALPHA_TABLE', 'TABLE')");
            connection.createStatement().execute("INSERT INTO ALL_OBJECTS VALUES ('PUBLIC', 'BETA_TABLE', 'TABLE')");
            connection.createStatement().execute("INSERT INTO ALL_OBJECTS VALUES ('PUBLIC', 'GAMMA_VIEW', 'VIEW')");
        }
        ConnectionService connections = mock(ConnectionService.class);
        when(connections.open(1L)).thenAnswer(ignored -> DriverManager.getConnection(url, "sa", ""));
        when(connections.require(1L)).thenReturn(new DbConnection(
                1L, "oracle", "oracle", url, "sa", "", "dev", false, Instant.now(), Instant.now()
        ));
        MetadataService service = new MetadataService(
                connections, new DialectRegistry(), mock(AuditRepository.class), new MetadataCacheService(), new ExecutionGuard()
        );

        MetadataResponse first = service.inspect(1L, "PUBLIC", null, 0, 2, false);
        MetadataResponse search = service.inspect(1L, "PUBLIC", "gamma_", 0, 10, false);

        assertThat(first.objects()).extracting("name").containsExactly("ALPHA_TABLE", "BETA_TABLE");
        assertThat(first.hasMore()).isTrue();
        assertThat(first.totalObjectsExact()).isFalse();
        assertThat(search.objects()).extracting("name").containsExactly("GAMMA_VIEW");
    }

    @Test
    void completionTreatsUnderscoreAsALiteralPrefixCharacter() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            connection.createStatement().execute("CREATE TABLE si_table(id BIGINT PRIMARY KEY)");
            connection.createStatement().execute("CREATE TABLE sim_table(id BIGINT PRIMARY KEY)");
            connection.createStatement().execute("CREATE TABLE sie_table(id BIGINT PRIMARY KEY)");
        }
        MetadataService service = service(url);
        MetadataResponse metadata = service.inspect(1L, null, null, 0, 200, false);

        var completion = service.completionCatalog(1L, metadata.selectedSchema(), "si_", 100, false);

        assertThat(completion.objects()).extracting("name").containsExactly("SI_TABLE");
    }

    @Test
    void defaultsToCurrentSchemaAndLoadsOtherSchemasOnDemand() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            connection.createStatement().execute("CREATE TABLE public_users(id BIGINT PRIMARY KEY)");
            connection.createStatement().execute("CREATE SCHEMA other");
            connection.createStatement().execute("CREATE TABLE other.audit_events(id BIGINT PRIMARY KEY)");
        }
        MetadataService service = service(url);

        MetadataResponse current = service.inspect(1L, null, null, 0, 200, false);
        MetadataResponse other = service.inspect(1L, "OTHER", null, 0, 200, false);
        MetadataResponse cachedCurrent = service.inspect(1L, null, null, 0, 200, false);

        assertThat(current.currentSchema()).isEqualTo("PUBLIC");
        assertThat(current.selectedSchema()).isEqualTo("PUBLIC");
        assertThat(current.schemas()).contains("PUBLIC", "OTHER");
        assertThat(current.objects()).extracting("name").containsExactly("PUBLIC_USERS");
        assertThat(other.selectedSchema()).isEqualTo("OTHER");
        assertThat(other.objects()).extracting("name").containsExactly("AUDIT_EVENTS");
        assertThat(other.cacheHit()).isFalse();
        assertThat(cachedCurrent.cacheHit()).isTrue();
    }

    @Test
    void reusesMetadataCacheUntilRefresh() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            connection.createStatement().execute("CREATE TABLE users(id BIGINT PRIMARY KEY)");
        }
        MetadataService service = service(url);

        MetadataResponse first = service.inspect(1L, null, null, 0, 200, false);
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            connection.createStatement().execute("CREATE TABLE orders(id BIGINT PRIMARY KEY)");
        }
        MetadataResponse cached = service.inspect(1L, null, null, 0, 200, false);
        MetadataResponse refreshed = service.inspect(1L, null, null, 0, 200, true);

        assertThat(first.objects()).extracting("name").contains("USERS").doesNotContain("ORDERS");
        assertThat(cached.cacheHit()).isTrue();
        assertThat(cached.objects()).extracting("name").contains("USERS").doesNotContain("ORDERS");
        assertThat(refreshed.cacheHit()).isFalse();
        assertThat(refreshed.objects()).extracting("name").contains("USERS", "ORDERS");
    }

    @Test
    void explicitInvalidationReloadsSchemaAndObjectCatalogFromTheSharedCache() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            connection.createStatement().execute("CREATE TABLE users(id BIGINT PRIMARY KEY)");
        }
        MetadataService service = service(url);
        service.inspect(1L, null, null, 0, 200, false);

        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            connection.createStatement().execute("CREATE SCHEMA archive");
            connection.createStatement().execute("CREATE TABLE archive.events(id BIGINT PRIMARY KEY)");
        }
        service.invalidateConnection(1L);
        MetadataResponse refreshed = service.inspect(1L, "ARCHIVE", null, 0, 200, false);

        assertThat(refreshed.schemas()).contains("ARCHIVE");
        assertThat(refreshed.objects()).extracting("name").containsExactly("EVENTS");
    }

    @Test
    void rowCountRemainsExactForTheUserRequestedCountOperation() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            connection.createStatement().execute("CREATE TABLE events(id INT PRIMARY KEY)");
            connection.createStatement().execute("INSERT INTO events VALUES (1), (2), (3)");
        }

        var count = service(url).rowCount(1L, "PUBLIC", "EVENTS");

        assertThat(count.value()).isEqualTo(3L);
        assertThat(count.exact()).isTrue();
    }

    @Test
    void loadsObjectStructureOnDemand() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            connection.createStatement().execute("CREATE TABLE users(id BIGINT PRIMARY KEY, name VARCHAR(40) NOT NULL)");
            connection.createStatement().execute("CREATE INDEX idx_users_name ON users(name)");
        }
        MetadataService service = service(url);
        ObjectStructure structure = service.structure(1L, null, "USERS");

        assertThat(structure.name()).isEqualTo("USERS");
        assertThat(structure.columns()).extracting("name").contains("ID", "NAME");
        assertThat(structure.indexes()).anyMatch(index -> "IDX_USERS_NAME".equals(index.name()) && "NAME".equals(index.columnName()));
    }

    @Test
    void treatsJdbcMetadataWildcardsAsLiteralObjectNames() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            connection.createStatement().execute("CREATE TABLE orders_2024(id BIGINT PRIMARY KEY, only_expected VARCHAR(20))");
            connection.createStatement().execute("CREATE TABLE ordersX2024(id BIGINT PRIMARY KEY, from_other_table VARCHAR(20))");
        }

        ObjectDetail detail = service(url).detail(1L, "PUBLIC", "ORDERS_2024");

        assertThat(detail.columns()).extracting("name")
                .containsExactly("ID", "ONLY_EXPECTED")
                .doesNotContain("FROM_OTHER_TABLE");
    }

    @Test
    void keepsCaseSensitiveObjectsInSeparateMetadataCacheEntries() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            connection.createStatement().execute("CREATE TABLE \"Foo\"(\"UpperColumn\" INT)");
            connection.createStatement().execute("CREATE TABLE \"foo\"(\"lowerColumn\" INT)");
        }
        MetadataService service = service(url);

        ObjectDetail upper = service.detail(1L, "PUBLIC", "Foo");
        ObjectDetail lower = service.detail(1L, "PUBLIC", "foo");

        assertThat(upper.columns()).extracting("name").containsExactly("UpperColumn");
        assertThat(lower.columns()).extracting("name").containsExactly("lowerColumn");
    }

    @Test
    void requiresExactNamespaceWhenCaseFoldedNameIsAmbiguous() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            connection.createStatement().execute("CREATE SCHEMA \"Foo\"");
            connection.createStatement().execute("CREATE SCHEMA \"foo\"");
            connection.createStatement().execute("CREATE TABLE \"Foo\".upper_table(id INT)");
            connection.createStatement().execute("CREATE TABLE \"foo\".lower_table(id INT)");
        }
        MetadataService service = service(url);

        assertThat(service.inspect(1L, "Foo", null, 0, 20, false).objects())
                .extracting("name").containsExactly("UPPER_TABLE");
        assertThat(service.inspect(1L, "foo", null, 0, 20, false).objects())
                .extracting("name").containsExactly("LOWER_TABLE");
        assertThatThrownBy(() -> service.inspect(1L, "FOO", null, 0, 20, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("大小写不明确");
    }

    @Test
    void loadsStructuralDetailAndDefersExpensiveRowCountAndDdl() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            connection.createStatement().execute("CREATE TABLE users(id BIGINT PRIMARY KEY, name VARCHAR(40) NOT NULL)");
            connection.createStatement().execute("CREATE INDEX idx_users_name ON users(name)");
            connection.createStatement().execute("INSERT INTO users(id, name) VALUES (1, 'Alice'), (2, 'Bob')");
        }
        MetadataService service = service(url);
        ObjectDetail detail = service.detail(1L, null, "USERS");
        var rowCount = service.rowCount(1L, null, "USERS");
        var ddl = service.ddl(1L, null, "USERS", false);

        assertThat(detail.name()).isEqualTo("USERS");
        assertThat(detail.primaryKeys()).containsExactly("ID");
        assertThat(detail.indexes()).anyMatch(index -> "IDX_USERS_NAME".equals(index.name()) && "NAME".equals(index.columnName()));
        assertThat(rowCount.exact()).isTrue();
        assertThat(rowCount.value()).isEqualTo(2L);
        assertThat(ddl.ddl()).contains("CREATE TABLE");
        assertThat(ddl.ddl()).contains("\"ID\" BIGINT NOT NULL");
        assertThat(ddl.ddl()).contains("PRIMARY KEY (\"ID\")");
    }

    @Test
    void detailPopulatesStructureCacheForLaterConsumers() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            connection.createStatement().execute("CREATE TABLE users(id BIGINT PRIMARY KEY, name VARCHAR(40))");
        }
        MetadataService service = service(url);

        service.detail(1L, "PUBLIC", "USERS");
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            connection.createStatement().execute("ALTER TABLE users ADD COLUMN external_value VARCHAR(20)");
        }

        assertThat(service.structure(1L, "PUBLIC", "USERS").columns()).extracting("name")
                .containsExactly("ID", "NAME");
        assertThat(service.structure(1L, "PUBLIC", "USERS", true).columns()).extracting("name")
                .containsExactly("ID", "NAME", "EXTERNAL_VALUE");
    }

    @Test
    void detailReusesCachedStructureUntilExplicitRefresh() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            connection.createStatement().execute("CREATE TABLE users(id BIGINT PRIMARY KEY, name VARCHAR(40))");
        }
        MetadataService service = service(url);

        service.structure(1L, "PUBLIC", "USERS");
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            connection.createStatement().execute("ALTER TABLE users ADD COLUMN external_value VARCHAR(20)");
        }

        assertThat(service.detail(1L, "PUBLIC", "USERS").columns()).extracting("name")
                .containsExactly("ID", "NAME");
        assertThat(service.detail(1L, "PUBLIC", "USERS", true).columns()).extracting("name")
                .containsExactly("ID", "NAME", "EXTERNAL_VALUE");
    }

    @Test
    void returnsViewDetailWithoutRunningCountAndLoadsDdlOnDemand() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            connection.createStatement().execute("CREATE TABLE users(id BIGINT PRIMARY KEY)");
            connection.createStatement().execute("CREATE VIEW active_users AS SELECT * FROM users");
        }
        MetadataService service = service(url);
        ObjectDetail detail = service.detail(1L, null, "ACTIVE_USERS");
        var ddl = service.ddl(1L, null, "ACTIVE_USERS", false);

        assertThat(detail.type()).contains("VIEW");
        assertThat(ddl.ddl()).contains("视图定义反查暂未实现");
    }

    @Test
    void loadsImportedAndExportedRelations() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            connection.createStatement().execute("CREATE TABLE users(id BIGINT PRIMARY KEY)");
            connection.createStatement().execute("CREATE TABLE orders(id BIGINT PRIMARY KEY, user_id BIGINT, CONSTRAINT fk_orders_users FOREIGN KEY(user_id) REFERENCES users(id))");
        }

        MetadataService service = service(url);
        ObjectRelations userRelations = service.relations(1L, null, "USERS");
        ObjectRelations orderRelations = service.relations(1L, null, "ORDERS");

        assertThat(userRelations.exportedKeys()).anyMatch(relation -> "ORDERS".equals(relation.fkTableName()) && "USER_ID".equals(relation.fkColumnName()));
        assertThat(orderRelations.importedKeys()).anyMatch(relation -> "USERS".equals(relation.pkTableName()) && "ID".equals(relation.pkColumnName()));
    }

    @Test
    void previewsTableDesignSql() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            connection.createStatement().execute("CREATE TABLE users(id BIGINT PRIMARY KEY, name VARCHAR(40) NOT NULL)");
        }

        MetadataService service = service(url);
        String structureVersion = service.detail(1L, null, "USERS", true).structureVersion();
        var response = service.previewDesign(1L, new TableDesignRequest(
                null,
                "USERS",
                List.of(
                        new ColumnDesign("ID", "BIGINT", null, false, null, "ID", false),
                        new ColumnDesign("USERNAME", "VARCHAR", 80, false, null, "NAME", false),
                        new ColumnDesign("EMAIL", "VARCHAR", 120, true, null, null, false)
                ),
                List.of(new IndexDesign("IDX_USERS_EMAIL", List.of("EMAIL"), false, null, false)),
                List.of("ID"),
                structureVersion,
                null
        ));

        assertThat(response.sql()).anyMatch(sql -> sql.contains("RENAME COLUMN"));
        assertThat(response.sql()).anyMatch(sql -> sql.contains("ADD COLUMN"));
        assertThat(response.sql()).anyMatch(sql -> sql.contains("CREATE INDEX"));
    }

    @Test
    void rejectsStaleTableDesignWhenLiveStructureChanged() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            connection.createStatement().execute("CREATE TABLE users(id BIGINT PRIMARY KEY)");
        }
        MetadataService service = service(url);
        ObjectDetail original = service.detail(1L, null, "USERS", true);
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            connection.createStatement().execute("ALTER TABLE users ADD COLUMN external_value VARCHAR(40)");
        }

        TableDesignRequest request = new TableDesignRequest(
                null,
                "USERS",
                List.of(new ColumnDesign("ID", "BIGINT", null, false, null, "ID", false)),
                List.of(),
                List.of("ID"),
                original.structureVersion(),
                null
        );

        assertThatThrownBy(() -> service.previewDesign(1L, request))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        problem -> assertThat(problem.code()).isEqualTo("STALE_TABLE_DESIGN"));
        assertThat(service.detail(1L, null, "USERS", true).columns())
                .extracting("name")
                .contains("EXTERNAL_VALUE");
    }

    @Test
    void evictsDetailCacheWhenLaterDdlStatementFailsAfterPartialSuccess() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            connection.createStatement().execute("CREATE TABLE users(name VARCHAR(40))");
        }
        MetadataService service = service(url);
        ObjectDetail original = service.detail(1L, null, "USERS");
        assertThat(original.columns()).extracting("name").contains("NAME");

        TableDesignRequest request = new TableDesignRequest(
                null,
                "USERS",
                List.of(
                        new ColumnDesign("RENAMED", "VARCHAR", 40, true, null, "NAME", false),
                        new ColumnDesign("BROKEN", "THIS_TYPE_DOES_NOT_EXIST", null, true, null, null, false)
                ),
                List.of(),
                List.of(),
                original.structureVersion(),
                "USERS"
        );

        assertThatThrownBy(() -> service.executeDesign(1L, request, "admin"))
                .isInstanceOfSatisfying(ApiProblemException.class, problem -> {
                    assertThat(problem.code()).isEqualTo("DDL_PARTIALLY_APPLIED");
                    assertThat(problem.details().get("executedStatements")).asList().isNotEmpty();
                });
        assertThat(service.detail(1L, null, "USERS").columns()).extracting("name")
                .contains("RENAMED")
                .doesNotContain("NAME");
    }

    @Test
    void createsRenamesAndDropsTableThroughLifecycleApi() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        AuditRepository audit = mock(AuditRepository.class);
        MetadataService service = service(url, "dev", false, audit);
        TableLifecycleRequest create = new TableLifecycleRequest(
                "CREATE",
                "PUBLIC",
                "APP_USER",
                null,
                List.of(
                        new ColumnDesign("ID", "BIGINT", null, false, null, null, false),
                        new ColumnDesign("NAME", "VARCHAR", 80, false, null, null, false)
                ),
                List.of(new IndexDesign("IDX_APP_USER_NAME", List.of("NAME"), false, null, false)),
                List.of("ID"),
                null,
                "PUBLIC.APP_USER"
        );

        assertThat(service.previewTableLifecycle(1L, create).sql())
                .first().asString().contains("CREATE TABLE", "APP_USER", "PRIMARY KEY");
        service.executeTableLifecycle(1L, create, "admin", null);
        ObjectDetail created = service.detail(1L, "PUBLIC", "APP_USER", true);
        assertThat(created.columns()).extracting("name").containsExactly("ID", "NAME");
        assertThat(created.indexes()).extracting("name").contains("IDX_APP_USER_NAME");
        verify(audit).log(eq("admin"), eq("TABLE_CREATE"), contains("PUBLIC.APP_USER"), contains("CREATE TABLE"));

        TableLifecycleRequest rename = new TableLifecycleRequest(
                "RENAME", "PUBLIC", "APP_USER", "APP_MEMBER", null, null, null,
                created.structureVersion(), "PUBLIC.APP_USER"
        );
        assertThat(service.previewTableLifecycle(1L, rename).sql()).singleElement().asString().contains("RENAME TO");
        service.executeTableLifecycle(1L, rename, "admin", null);
        ObjectDetail renamed = service.detail(1L, "PUBLIC", "APP_MEMBER", true);
        assertThat(renamed.columns()).extracting("name").contains("ID", "NAME");
        assertThatThrownBy(() -> service.detail(1L, "PUBLIC", "APP_USER", true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未找到");
        verify(audit).log(eq("admin"), eq("TABLE_RENAME"), contains("PUBLIC.APP_USER"), contains("RENAME TO"));

        TableLifecycleRequest drop = new TableLifecycleRequest(
                "DROP", "PUBLIC", "APP_MEMBER", null, null, null, null,
                renamed.structureVersion(), "PUBLIC.APP_MEMBER"
        );
        assertThat(service.previewTableLifecycle(1L, drop).sql()).containsExactly("DROP TABLE \"PUBLIC\".\"APP_MEMBER\"");
        service.executeTableLifecycle(1L, drop, "admin", null);
        assertThatThrownBy(() -> service.detail(1L, "PUBLIC", "APP_MEMBER", true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未找到");
        verify(audit).log(eq("admin"), eq("TABLE_DROP"), contains("PUBLIC.APP_MEMBER"), contains("DROP TABLE"));
    }

    @Test
    void rejectsDuplicateCreateStaleDropAndWrongConfirmation() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            connection.createStatement().execute("CREATE TABLE users(id BIGINT PRIMARY KEY)");
        }
        MetadataService service = service(url);
        TableLifecycleRequest duplicate = new TableLifecycleRequest(
                "CREATE", "PUBLIC", "USERS", null,
                List.of(new ColumnDesign("ID", "BIGINT", null, false, null, null, false)),
                List.of(), List.of("ID"), null, "PUBLIC.USERS"
        );
        assertThatThrownBy(() -> service.previewTableLifecycle(1L, duplicate))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        problem -> assertThat(problem.code()).isEqualTo("TABLE_ALREADY_EXISTS"));

        ObjectDetail original = service.detail(1L, "PUBLIC", "USERS", true);
        TableLifecycleRequest wrongConfirmation = new TableLifecycleRequest(
                "DROP", "PUBLIC", "USERS", null, null, null, null,
                original.structureVersion(), "USERS"
        );
        assertThatThrownBy(() -> service.executeTableLifecycle(1L, wrongConfirmation, "admin", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("确认文本不匹配");

        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            connection.createStatement().execute("ALTER TABLE users ADD COLUMN external_value VARCHAR(20)");
        }
        TableLifecycleRequest stale = new TableLifecycleRequest(
                "DROP", "PUBLIC", "USERS", null, null, null, null,
                original.structureVersion(), "PUBLIC.USERS"
        );
        assertThatThrownBy(() -> service.previewTableLifecycle(1L, stale))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        problem -> assertThat(problem.code()).isEqualTo("STALE_TABLE_OBJECT"));
    }

    private MetadataService service(String url) throws Exception {
        return service(url, "dev", false, mock(AuditRepository.class));
    }

    private MetadataService service(String url, String environment, boolean readonly, AuditRepository audit) throws Exception {
        ConnectionService connections = mock(ConnectionService.class);
        when(connections.open(anyLong())).thenAnswer(_invocation -> DriverManager.getConnection(url, "sa", ""));
        when(connections.require(anyLong())).thenReturn(new DbConnection(1L, "h2", "h2", url, "sa", "", environment, readonly, Instant.now(), Instant.now()));
        return new MetadataService(
                connections,
                new DialectRegistry(),
                audit,
                new MetadataCacheService(),
                new ExecutionGuard()
        );
    }
}
