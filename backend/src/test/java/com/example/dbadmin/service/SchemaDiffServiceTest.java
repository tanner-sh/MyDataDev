package com.example.dbadmin.service;

import com.example.dbadmin.api.ApiProblemException;
import com.example.dbadmin.core.DialectRegistry;
import com.example.dbadmin.dto.ApiDtos.SchemaDiffRequest;
import com.example.dbadmin.dto.ApiDtos.SchemaDiffResponse;
import com.example.dbadmin.dto.ApiDtos.SchemaDiffTable;
import com.example.dbadmin.model.DbConnection;
import com.example.dbadmin.repo.AuditRepository;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.contains;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 结构对比跑在两个真的 H2 库上：差异判定依赖 JDBC 元数据的真实形状（索引按列分行、主键背后
 * 还有一个唯一索引），用桩数据很容易验证出一个现实里不成立的结论。
 */
class SchemaDiffServiceTest {
    @Test
    void reportsMissingChangedAndExtraTables() throws Exception {
        String source = database("""
                CREATE TABLE orders(id BIGINT PRIMARY KEY, amount DECIMAL(12,2) NOT NULL, note VARCHAR(200));
                CREATE INDEX idx_orders_note ON orders(note);
                CREATE TABLE audit_trail(id BIGINT PRIMARY KEY, actor VARCHAR(80));
                """);
        String target = database("""
                CREATE TABLE orders(id BIGINT PRIMARY KEY, amount DECIMAL(12,2));
                CREATE TABLE legacy_export(id BIGINT PRIMARY KEY);
                """);
        SchemaDiffService service = service(source, target, mock(AuditRepository.class));

        SchemaDiffResponse response = service.compare(
                new SchemaDiffRequest(1L, "PUBLIC", 2L, "PUBLIC", List.of(), false), "admin");

        assertThat(response.summary().onlyInSource()).isEqualTo(1);
        assertThat(response.summary().onlyInTarget()).isEqualTo(1);
        assertThat(response.summary().different()).isEqualTo(1);
        assertThat(response.summary().identical()).isZero();

        SchemaDiffTable orders = table(response, "ORDERS");
        assertThat(orders.status()).isEqualTo(SchemaComparison.STATUS_DIFFERENT);
        assertThat(orders.items()).extracting("name", "change").contains(
                org.assertj.core.groups.Tuple.tuple("NOTE", SchemaComparison.CHANGE_ADDED),
                org.assertj.core.groups.Tuple.tuple("AMOUNT", SchemaComparison.CHANGE_CHANGED),
                org.assertj.core.groups.Tuple.tuple("IDX_ORDERS_NOTE", SchemaComparison.CHANGE_ADDED));
        assertThat(orders.migration()).anySatisfy(sql -> assertThat(sql).contains("ADD COLUMN").contains("NOTE"));
        assertThat(orders.migration()).anySatisfy(sql -> assertThat(sql).contains("CREATE INDEX"));

        assertThat(table(response, "AUDIT_TRAIL").status()).isEqualTo(SchemaComparison.STATUS_ONLY_IN_SOURCE);
        assertThat(table(response, "AUDIT_TRAIL").migration()).anySatisfy(sql -> assertThat(sql).startsWith("CREATE TABLE"));
        assertThat(table(response, "LEGACY_EXPORT").status()).isEqualTo(SchemaComparison.STATUS_ONLY_IN_TARGET);
    }

    @Test
    void keepsTargetOnlyObjectsUnlessDropsAreRequested() throws Exception {
        String source = database("CREATE TABLE orders(id BIGINT PRIMARY KEY);");
        String target = database("""
                CREATE TABLE orders(id BIGINT PRIMARY KEY, legacy_note VARCHAR(40));
                CREATE TABLE legacy_export(id BIGINT PRIMARY KEY);
                """);

        SchemaDiffResponse kept = service(source, target, mock(AuditRepository.class))
                .compare(new SchemaDiffRequest(1L, "PUBLIC", 2L, "PUBLIC", List.of(), false), "admin");
        assertThat(String.join("\n", kept.migration())).doesNotContain("DROP");

        SchemaDiffResponse dropped = service(source, target, mock(AuditRepository.class))
                .compare(new SchemaDiffRequest(1L, "PUBLIC", 2L, "PUBLIC", List.of(), true), "admin");
        String script = String.join("\n", dropped.migration());
        assertThat(script).contains("DROP COLUMN").contains("LEGACY_NOTE");
        assertThat(script).contains("DROP TABLE").contains("LEGACY_EXPORT");
    }

    @Test
    void identicalSchemasProduceNoMigration() throws Exception {
        String ddl = "CREATE TABLE orders(id BIGINT PRIMARY KEY, note VARCHAR(200)); CREATE INDEX idx_note ON orders(note);";
        SchemaDiffService service = service(database(ddl), database(ddl), mock(AuditRepository.class));

        SchemaDiffResponse response = service.compare(
                new SchemaDiffRequest(1L, "PUBLIC", 2L, "PUBLIC", List.of(), false), "admin");

        assertThat(response.summary().identical()).isEqualTo(1);
        assertThat(response.migration()).isEmpty();
        assertThat(table(response, "ORDERS").items()).isEmpty();
    }

    @Test
    void narrowsTheComparisonToTheRequestedTables() throws Exception {
        String source = database("CREATE TABLE orders(id BIGINT PRIMARY KEY); CREATE TABLE staff(id BIGINT PRIMARY KEY);");
        String target = database("CREATE TABLE orders(id BIGINT PRIMARY KEY);");
        SchemaDiffService service = service(source, target, mock(AuditRepository.class));

        SchemaDiffResponse response = service.compare(
                new SchemaDiffRequest(1L, "PUBLIC", 2L, "PUBLIC", List.of("orders"), false), "admin");

        assertThat(response.tables()).extracting(SchemaDiffTable::tableName).containsExactly("ORDERS");
    }

    @Test
    void warnsWhenTheTwoSidesUseDifferentDatabaseTypes() throws Exception {
        String source = database("CREATE TABLE orders(id BIGINT PRIMARY KEY);");
        String target = database("CREATE TABLE orders(id BIGINT PRIMARY KEY);");
        // 用 PostgreSQL 作为源端类型：它和 H2 一样是 schema 语义，能在同一个 H2 库上跑通元数据读取。
        SchemaDiffService service = service(source, target, mock(AuditRepository.class), "postgresql");

        SchemaDiffResponse response = service.compare(
                new SchemaDiffRequest(1L, "PUBLIC", 2L, "PUBLIC", List.of(), false), "admin");

        assertThat(response.warnings()).anySatisfy(warning -> assertThat(warning).contains("数据库类型不同"));
    }

    @Test
    void rejectsComparingASchemaWithItself() throws Exception {
        String url = database("CREATE TABLE orders(id BIGINT PRIMARY KEY);");
        SchemaDiffService service = service(url, url, mock(AuditRepository.class));

        assertThatThrownBy(() -> service.compare(
                new SchemaDiffRequest(1L, "PUBLIC", 1L, "public", List.of(), false), "admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("同一个 Schema");
    }

    @Test
    void recordsAnAuditEntry() throws Exception {
        AuditRepository audit = mock(AuditRepository.class);
        SchemaDiffService service = service(
                database("CREATE TABLE orders(id BIGINT PRIMARY KEY);"),
                database("CREATE TABLE orders(id BIGINT PRIMARY KEY);"),
                audit);

        service.compare(new SchemaDiffRequest(1L, "PUBLIC", 2L, "PUBLIC", List.of(), false), "admin");

        // 归属到目标连接，且连接 id 是独立参数而不是拼进字符串。
        verify(audit).onConnection(eq("admin"), eq("SCHEMA_DIFF"), eq(2L), contains("PUBLIC"), contains("source"));
    }

    @Test
    void refusesToCompareMoreTablesThanTheLimit() throws Exception {
        StringBuilder ddl = new StringBuilder();
        for (int index = 0; index <= SchemaDiffService.MAX_TABLES; index++) {
            ddl.append("CREATE TABLE t").append(index).append("(id BIGINT PRIMARY KEY);");
        }
        SchemaDiffService service = service(database(ddl.toString()), database(""), mock(AuditRepository.class));

        assertThatThrownBy(() -> service.compare(
                new SchemaDiffRequest(1L, "PUBLIC", 2L, "PUBLIC", List.of(), false), "admin"))
                .isInstanceOf(ApiProblemException.class)
                .hasMessageContaining("超过单次对比上限");
    }

    @Test
    void skipsDdlForTargetsThatCannotBeDesigned() throws Exception {
        // SQL Server、ClickHouse、SQLite 声明不支持表设计，也没重写 alterTableSql，继承的是
        // DefaultDialect 那套 PostgreSQL 写法 —— `ALTER COLUMN x TYPE y` 在 T-SQL 里根本不是
        // 合法语法。表设计器早就有这道闸门（MetadataService），结构对比曾漏抄，于是发出去一份
        // 看起来完整、实际一条都跑不通的脚本。差异清单本身仍然有用，照常返回。
        String source = database("""
                CREATE TABLE orders(id BIGINT PRIMARY KEY, amount DECIMAL(12,2) NOT NULL, note VARCHAR(200));
                CREATE TABLE audit_trail(id BIGINT PRIMARY KEY);
                """);
        String target = database("CREATE TABLE orders(id BIGINT PRIMARY KEY, amount DECIMAL(12,2));");
        SchemaDiffService service = service(source, target, mock(AuditRepository.class), "h2", "sqlserver");

        SchemaDiffResponse response = service.compare(
                new SchemaDiffRequest(1L, "PUBLIC", 2L, "PUBLIC", List.of(), false), "admin");

        assertThat(response.warnings()).anySatisfy(warning ->
                assertThat(warning).contains("不支持自动生成建表/改表语句"));
        assertThat(table(response, "ORDERS").status()).isEqualTo(SchemaComparison.STATUS_DIFFERENT);
        assertThat(table(response, "ORDERS").items()).isNotEmpty();
        assertThat(table(response, "ORDERS").migration()).isEmpty();
        assertThat(table(response, "AUDIT_TRAIL").migration()).isEmpty();
        assertThat(response.migration()).noneSatisfy(sql -> assertThat(sql).contains("ALTER COLUMN"));
    }

    @Test
    void stillGeneratesDropsForTargetsThatCannotBeDesigned() throws Exception {
        // DROP TABLE 不经过设计稿那条路径，各家写法一致，没有必要跟着一起禁掉。
        String source = database("CREATE TABLE orders(id BIGINT PRIMARY KEY);");
        String target = database("""
                CREATE TABLE orders(id BIGINT PRIMARY KEY);
                CREATE TABLE legacy_export(id BIGINT PRIMARY KEY);
                """);
        SchemaDiffService service = service(source, target, mock(AuditRepository.class), "h2", "sqlserver");

        SchemaDiffResponse response = service.compare(
                new SchemaDiffRequest(1L, "PUBLIC", 2L, "PUBLIC", List.of(), true), "admin");

        assertThat(table(response, "LEGACY_EXPORT").migration()).anySatisfy(sql -> assertThat(sql).startsWith("DROP TABLE"));
    }

    private static SchemaDiffTable table(SchemaDiffResponse response, String name) {
        return response.tables().stream()
                .filter(table -> table.tableName().equalsIgnoreCase(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("对比结果里没有表 " + name));
    }

    private static String database(String ddl) throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            for (String sql : ddl.split(";")) {
                if (!sql.isBlank()) statement.execute(sql);
            }
        }
        return url;
    }

    private static SchemaDiffService service(String sourceUrl, String targetUrl, AuditRepository audit) throws Exception {
        return service(sourceUrl, targetUrl, audit, "h2");
    }

    private static SchemaDiffService service(String sourceUrl, String targetUrl, AuditRepository audit, String sourceDbType) throws Exception {
        return service(sourceUrl, targetUrl, audit, sourceDbType, "h2");
    }

    private static SchemaDiffService service(String sourceUrl, String targetUrl, AuditRepository audit, String sourceDbType, String targetDbType) throws Exception {
        ConnectionService connections = mock(ConnectionService.class);
        when(connections.open(1L)).thenAnswer(ignored -> DriverManager.getConnection(sourceUrl, "sa", ""));
        when(connections.open(2L)).thenAnswer(ignored -> DriverManager.getConnection(targetUrl, "sa", ""));
        when(connections.open(anyLong(), org.mockito.ArgumentMatchers.nullable(String.class)))
                .thenAnswer(invocation -> DriverManager.getConnection(
                        invocation.getArgument(0, Long.class) == 1L ? sourceUrl : targetUrl, "sa", ""));
        when(connections.require(1L)).thenReturn(new DbConnection(
                1L, "source", sourceDbType, sourceUrl, "sa", "", "dev", false, Instant.now(), Instant.now()));
        when(connections.require(2L)).thenReturn(new DbConnection(
                2L, "target", targetDbType, targetUrl, "sa", "", "dev", false, Instant.now(), Instant.now()));
        MetadataService metadata = new MetadataService(
                connections, new DialectRegistry(), mock(AuditRepository.class), new MetadataCacheService(), new ExecutionGuard());
        return new SchemaDiffService(connections, metadata, new DialectRegistry(), audit);
    }
}
