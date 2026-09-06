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
    void pagesAndFiltersSchemaObjectsInTheDatabase() throws Exception {
        Fixture fixture = fixture(false, "dev");
        try (Connection connection = DriverManager.getConnection(fixture.url(), "sa", "")) {
            connection.createStatement().execute("CREATE VIEW report_2024 AS SELECT 1 AS metric_value");
            connection.createStatement().execute("CREATE VIEW reportX2024 AS SELECT 1 AS metric_value");
            connection.createStatement().execute("CREATE VIEW summary_view AS SELECT 1 AS metric_value");
        }

        var first = fixture.service().list(1L, "PUBLIC", "VIEW", null, 0, 2, false);
        assertThat(first.items()).hasSize(2);
        assertThat(first.hasMore()).isTrue();
        assertThat(first.total()).isEqualTo(3);
        assertThat(first.totalExact()).isFalse();

        var last = fixture.service().list(1L, "PUBLIC", "VIEW", null, 1, 2, false);
        assertThat(last.items()).hasSize(1);
        assertThat(last.hasMore()).isFalse();
        assertThat(last.total()).isEqualTo(3);
        assertThat(last.totalExact()).isTrue();

        var literalUnderscore = fixture.service().list(1L, "PUBLIC", "VIEW", "report_", 0, 10, false);
        assertThat(literalUnderscore.items()).extracting("name").containsExactly("REPORT_2024");
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

    /**
     * 建对象的语句没抛异常，不等于对象是好的。
     *
     * <p>Oracle 的 {@code CREATE OR REPLACE PROCEDURE} 语法错了也会返回成功，对象以 INVALID
     * 状态留在库里。少了这一步，界面给出一句干净的「已创建」，直到某天有人调用它才发现是坏的
     * —— 那时报错现场离真正的原因已经隔了很远。</p>
     *
     * <p>H2 上造不出这种语义，所以用一张模拟字典表 + 一个只覆写查询语句的方言来验证接线：
     * 三个参数的绑定顺序、只在 CREATE/REPLACE 上查、以及查到之后响应长什么样。</p>
     */
    @Test
    void reportsCompilationErrorsInsteadOfClaimingTheObjectWasCreated() throws Exception {
        Fixture fixture = compilationFixture("PUBLIC", "RECENT_USERS", "VIEW");

        var create = new SchemaObjectLifecycleRequest(
                "CREATE", "VIEW", "PUBLIC", "recent_users", null,
                "CREATE VIEW PUBLIC.recent_users AS SELECT id FROM users", null, "PUBLIC.recent_users"
        );
        var response = fixture.service().execute(1L, create, "tester", null);

        assertThat(response.compilationErrors()).hasSize(1);
        assertThat(response.compilationErrors().get(0).line()).isEqualTo(3);
        assertThat(response.compilationErrors().get(0).position()).isEqualTo(12);
        assertThat(response.compilationErrors().get(0).text()).contains("PLS-00103");
        assertThat(response.message()).contains("编译未通过");
    }

    /** 三个参数按 owner / 对象名 / 对象类型 的顺序绑定；错位的话查出来的是别人的错误。 */
    @Test
    void bindsTheDictionaryLookupToTheObjectThatWasJustCreated() throws Exception {
        Fixture fixture = compilationFixture("PUBLIC", "SOMETHING_ELSE", "VIEW");

        var create = new SchemaObjectLifecycleRequest(
                "CREATE", "VIEW", "PUBLIC", "recent_users", null,
                "CREATE VIEW PUBLIC.recent_users AS SELECT id FROM users", null, "PUBLIC.recent_users"
        );

        assertThat(fixture.service().execute(1L, create, "tester", null).compilationErrors()).isEmpty();
    }

    /** 查不到（没权限读字典表、驱动不支持）就当没有：这是补充诊断，不该让一次已执行的 DDL 变成失败。 */
    @Test
    void aFailedCompilationLookupDoesNotFailTheDdl() throws Exception {
        Fixture fixture = fixtureWithDialect(new com.example.dbadmin.core.H2Dialect() {
            @Override
            public String compilationErrorsSql() {
                return "SELECT line, position, text FROM table_that_does_not_exist WHERE a = ? AND b = ? AND c = ?";
            }
        });
        try (Connection connection = DriverManager.getConnection(fixture.url(), "sa", "")) {
            connection.createStatement().execute("CREATE TABLE users(id BIGINT PRIMARY KEY)");
        }

        var create = new SchemaObjectLifecycleRequest(
                "CREATE", "VIEW", "PUBLIC", "recent_users", null,
                "CREATE VIEW PUBLIC.recent_users AS SELECT id FROM users", null, "PUBLIC.recent_users"
        );
        var response = fixture.service().execute(1L, create, "tester", null);

        assertThat(response.compilationErrors()).isEmpty();
        assertThat(response.message()).contains("已创建");
    }

    /** DROP 之后去字典表里查「它编译过了吗」没有意义，也白花一次往返。 */
    @Test
    void doesNotLookUpCompilationErrorsForDrops() throws Exception {
        Fixture fixture = compilationFixture("PUBLIC", "ACTIVE_USERS", "VIEW");
        try (Connection connection = DriverManager.getConnection(fixture.url(), "sa", "")) {
            connection.createStatement().execute("CREATE VIEW active_users AS SELECT id FROM users");
        }
        var views = fixture.service().list(1L, "PUBLIC", "VIEW", "active", 0, 100, true);
        var detail = fixture.service().detail(1L, views.items().get(0).objectKey(), false);

        var drop = new SchemaObjectLifecycleRequest(
                "DROP", "VIEW", "PUBLIC", "ACTIVE_USERS", views.items().get(0).objectKey(),
                null, detail.structureVersion(), "PUBLIC.ACTIVE_USERS"
        );

        assertThat(fixture.service().execute(1L, drop, "tester", null).compilationErrors()).isEmpty();
    }

    /** 装一张模拟字典表，并让方言按 owner/name/type 三个参数去查它。 */
    private Fixture compilationFixture(String owner, String name, String type) throws Exception {
        Fixture fixture = fixtureWithDialect(new com.example.dbadmin.core.H2Dialect() {
            @Override
            public String compilationErrorsSql() {
                // 与 OracleDialect 同形：名字按大小写不敏感匹配。表单里填的是小写，
                // 而字典表里存的是折成大写之后的名字。
                return "SELECT line AS line, \"position\" AS \"position\", text AS text FROM all_errors_probe"
                        + " WHERE UPPER(owner) = UPPER(COALESCE(?, 'PUBLIC')) AND UPPER(name) = UPPER(?)"
                        + " AND type = ? ORDER BY line";
            }
        });
        try (Connection connection = DriverManager.getConnection(fixture.url(), "sa", "")) {
            connection.createStatement().execute("CREATE TABLE users(id BIGINT PRIMARY KEY)");
            connection.createStatement().execute(
                    "CREATE TABLE all_errors_probe(owner VARCHAR(30), name VARCHAR(60), type VARCHAR(30),"
                            + " line INT, \"position\" INT, text VARCHAR(200))");
            try (var statement = connection.prepareStatement("INSERT INTO all_errors_probe VALUES (?,?,?,?,?,?)")) {
                statement.setString(1, owner);
                statement.setString(2, name);
                statement.setString(3, type);
                statement.setInt(4, 3);
                statement.setInt(5, 12);
                statement.setString(6, "PLS-00103: 出现符号 END");
                statement.execute();
            }
        }
        return fixture;
    }

    private Fixture fixtureWithDialect(com.example.dbadmin.core.DatabaseDialect dialect) throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        try (Connection ignored = DriverManager.getConnection(url, "sa", "")) {
            // Keep the named in-memory database alive for subsequent calls.
        }
        DbConnection configured = new DbConnection(1L, "remote", "h2", url, "sa", "", "dev", false, Instant.now(), Instant.now());
        ConnectionService connections = mock(ConnectionService.class);
        when(connections.require(1L)).thenReturn(configured);
        when(connections.open(1L)).thenAnswer(ignored -> DriverManager.getConnection(url, "sa", ""));
        DialectRegistry registry = mock(DialectRegistry.class);
        when(registry.dialectFor(org.mockito.ArgumentMatchers.any())).thenReturn(dialect);
        SchemaObjectService service = new SchemaObjectService(
                connections,
                registry,
                new SchemaObjectCatalog(new AppProperties()),
                new MetadataCacheService(),
                new ExecutionGuard(),
                mock(AuditRepository.class),
                mock(SqlHistoryRepository.class),
                new AppProperties(),
                new SqlScriptSplitter()
        );
        return new Fixture(url, service);
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
                new SchemaObjectCatalog(new AppProperties()),
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
