package com.example.dbadmin.service;

import com.example.dbadmin.config.AppProperties;
import com.example.dbadmin.core.DialectRegistry;
import com.example.dbadmin.dto.ApiDtos.SchemaObjectLifecycleRequest;
import com.example.dbadmin.dto.ApiDtos.RoutineArgumentInput;
import com.example.dbadmin.dto.ApiDtos.RoutineInvokeRequest;
import com.example.dbadmin.model.DbConnection;
import com.example.dbadmin.repo.AuditRepository;
import com.example.dbadmin.repo.SqlHistoryRepository;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SchemaObjectServiceTest {
    @Test
    void managesH2ViewsWithPreviewConfirmationAndFreshMetadata() throws Exception {
        Fixture fixture = fixture(false, "dev");
        try (Connection connection = DriverManager.getConnection(fixture.url(), "sa", "")) {
            connection.createStatement().execute("CREATE TABLE users(id BIGINT PRIMARY KEY, active BOOLEAN)");
            connection.createStatement().execute("CREATE VIEW active_users AS SELECT id FROM users WHERE active = TRUE");
            connection.createStatement().execute("CREATE SEQUENCE order_seq START WITH 10");
        }

        var views = fixture.service().list(1L, "PUBLIC", "VIEW", null, 0, 100, false);
        var sequences = fixture.service().list(1L, "PUBLIC", "SEQUENCE", null, 0, 100, false);
        assertThat(views.items()).extracting("name").contains("ACTIVE_USERS");
        assertThat(sequences.items()).extracting("name").contains("ORDER_SEQ");

        var detail = fixture.service().detail(1L, views.items().get(0).objectKey(), false);
        assertThat(detail.sourceAvailable()).isTrue();
        assertThat(detail.source()).startsWith("CREATE OR REPLACE VIEW");
        assertThat(detail.operations()).contains("REPLACE", "DROP");

        var create = new SchemaObjectLifecycleRequest(
                "CREATE", "VIEW", "PUBLIC", "recent_users", null,
                "CREATE VIEW PUBLIC.recent_users AS SELECT id FROM users", null, "PUBLIC.recent_users"
        );
        assertThat(fixture.service().preview(1L, create).sql()).containsExactly(create.source());
        assertThat(fixture.service().execute(1L, create, "tester", null).message()).contains("已创建");
        assertThat(fixture.service().list(1L, "PUBLIC", "VIEW", "recent", 0, 100, true).items())
                .extracting("name").contains("RECENT_USERS");
    }

    @Test
    void rejectsClientBatchSeparatorsAndReadonlyMutation() throws Exception {
        Fixture writable = fixture(false, "dev");
        var invalid = new SchemaObjectLifecycleRequest(
                "CREATE", "VIEW", "PUBLIC", "unsafe_view", null,
                "DELIMITER $$\nCREATE VIEW PUBLIC.unsafe_view AS SELECT 1$$", null, null
        );
        assertThatThrownBy(() -> writable.service().preview(1L, invalid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("批处理分隔符");

        var multiple = new SchemaObjectLifecycleRequest(
                "CREATE", "VIEW", "PUBLIC", "unsafe_view", null,
                "CREATE VIEW PUBLIC.unsafe_view AS SELECT 1; DROP TABLE users", null, null
        );
        assertThatThrownBy(() -> writable.service().preview(1L, multiple))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("一个顶层定义语句");

        var wrongTarget = new SchemaObjectLifecycleRequest(
                "CREATE", "VIEW", "PUBLIC", "safe_view", null,
                "CREATE VIEW PUBLIC.other_view AS SELECT 'safe_view' AS value", null, null
        );
        assertThatThrownBy(() -> writable.service().preview(1L, wrongTarget))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("对象名与当前目标不匹配");

        var wrongNamespace = new SchemaObjectLifecycleRequest(
                "CREATE", "VIEW", "PUBLIC", "safe_view", null,
                "CREATE VIEW OTHER.safe_view AS SELECT 1", null, null
        );
        assertThatThrownBy(() -> writable.service().preview(1L, wrongNamespace))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("命名空间与当前目标不匹配");

        var overwritingCreate = new SchemaObjectLifecycleRequest(
                "CREATE", "VIEW", "PUBLIC", "safe_view", null,
                "CREATE OR REPLACE VIEW PUBLIC.safe_view AS SELECT 1", null, null
        );
        assertThatThrownBy(() -> writable.service().preview(1L, overwritingCreate))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能使用 OR REPLACE");

        Fixture readonly = fixture(true, "dev");
        var create = new SchemaObjectLifecycleRequest(
                "CREATE", "VIEW", "PUBLIC", "safe_view", null,
                "CREATE VIEW PUBLIC.safe_view AS SELECT 1", null, "PUBLIC.safe_view"
        );
        assertThatThrownBy(() -> readonly.service().execute(1L, create, "tester", null))
                .hasMessageContaining("只读连接");
    }

    @Test
    void invokesH2FunctionWithTypedArgumentsAndReturnValue() throws Exception {
        Fixture fixture = fixture(false, "dev");
        try (Connection connection = DriverManager.getConnection(fixture.url(), "sa", "")) {
            connection.createStatement().execute("CREATE ALIAS ADD_NUM AS $$ int addNum(int left, int right) { return left + right; } $$");
        }
        var functions = fixture.service().list(1L, "PUBLIC", "FUNCTION", "ADD_NUM", 0, 100, true);
        assertThat(functions.items()).hasSize(1);
        var detail = fixture.service().detail(1L, functions.items().get(0).objectKey(), true);
        assertThat(detail.operations()).contains("INVOKE");
        assertThat(detail.operations()).doesNotContain("REPLACE");
        assertThat(detail.source()).startsWith("CREATE ALIAS");
        var inputs = detail.parameters().stream()
                .filter(parameter -> parameter.mode().equals("IN") || parameter.mode().equals("INOUT"))
                .map(parameter -> new RoutineArgumentInput(parameter.position(), parameter.name(), parameter.position() == 1 ? "2" : "3", false))
                .toList();

        var response = fixture.service().invoke(1L, new RoutineInvokeRequest(detail.object().objectKey(), detail.structureVersion(), inputs), "tester", null);

        assertThat(response.status()).isEqualTo("SUCCESS");
        assertThat(response.returnValue()).isEqualTo(5);
    }

    @Test
    void requiresProductionConnectionNameAndExactObjectConfirmation() throws Exception {
        Fixture fixture = fixture(false, "prod");
        var create = new SchemaObjectLifecycleRequest(
                "CREATE", "VIEW", "PUBLIC", "safe_view", null,
                "CREATE VIEW PUBLIC.safe_view AS SELECT 1", null, "wrong"
        );
        assertThatThrownBy(() -> fixture.service().execute(1L, create, "tester", null))
                .hasMessageContaining("生产连接");
        assertThatThrownBy(() -> fixture.service().execute(1L, create, "tester", "remote"))
                .hasMessageContaining("确认文本不匹配");
    }

    private Fixture fixture(boolean readonly, String environment) throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        try (Connection ignored = DriverManager.getConnection(url, "sa", "")) {
            // Keep the named in-memory database alive for subsequent calls.
        }
        DbConnection configured = new DbConnection(1L, "remote", "h2", url, "sa", "", environment, readonly, Instant.now(), Instant.now());
        ConnectionService connections = mock(ConnectionService.class);
        when(connections.require(1L)).thenReturn(configured);
        when(connections.open(1L)).thenAnswer(ignored -> DriverManager.getConnection(url, "sa", ""));
        SchemaObjectService service = new SchemaObjectService(
                connections,
                new DialectRegistry(),
                new SchemaObjectCatalog(),
                new MetadataCacheService(),
                new ExecutionGuard(),
                mock(AuditRepository.class),
                mock(SqlHistoryRepository.class),
                new AppProperties(),
                new SqlScriptSplitter()
        );
        return new Fixture(url, service);
    }

    private record Fixture(String url, SchemaObjectService service) {
    }
}
