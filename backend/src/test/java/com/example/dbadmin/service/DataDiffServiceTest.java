package com.example.dbadmin.service;

import com.example.dbadmin.api.ApiProblemException;
import com.example.dbadmin.config.AppProperties;
import com.example.dbadmin.core.DialectRegistry;
import com.example.dbadmin.dto.ApiDtos.DataDiffRequest;
import com.example.dbadmin.dto.ApiDtos.DataDiffResponse;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 数据对比跑在两个真的 H2 库上：主键、列类型、NULL 的形状都来自真实的 JDBC 元数据 ——
 * 用桩数据很容易验证出一个现实里不成立的结论。
 */
class DataDiffServiceTest {
    @Test
    void reportsMissingChangedAndExtraRowsWithASyncScript() throws Exception {
        String source = database("""
                CREATE TABLE orders(id BIGINT PRIMARY KEY, amount DECIMAL(12,2), note VARCHAR(200));
                INSERT INTO orders VALUES (1, 100.00, '相同'), (2, 200.00, '源端改过'), (3, 300.00, '只在源端');
                """);
        String target = database("""
                CREATE TABLE orders(id BIGINT PRIMARY KEY, amount DECIMAL(12,2), note VARCHAR(200));
                INSERT INTO orders VALUES (1, 100.00, '相同'), (2, 999.00, '源端改过'), (4, 400.00, '只在目标端');
                """);
        AuditRepository audit = mock(AuditRepository.class);
        DataDiffService service = service(source, target, audit);

        DataDiffResponse response = service.compare(request(false), "admin");

        assertThat(response.summary().identical()).isEqualTo(1);
        assertThat(response.summary().different()).isEqualTo(1);
        assertThat(response.summary().onlyInSource()).isEqualTo(1);
        assertThat(response.summary().onlyInTarget()).isEqualTo(1);
        assertThat(response.keyColumns()).containsExactly("ID");
        assertThat(response.truncated()).isFalse();

        // 目标端多出来的行默认不删：那往往是目标库自己的数据。
        assertThat(response.script()).hasSize(2);
        // UPDATE 写的是源端的值（200.00），不是目标端那个 999 —— 方向是「把目标端对齐到源端」。
        assertThat(response.script()).anySatisfy(sql ->
                assertThat(sql).startsWith("UPDATE").contains("200.00").doesNotContain("999"));
        assertThat(response.script()).anySatisfy(sql -> assertThat(sql).startsWith("INSERT").contains("只在源端"));

        verify(audit).onConnection(eq("admin"), eq(DataDiffService.ACTION_DATA_DIFF), eq(1L),
                eq("table:ORDERS"), contains("different=1"));
    }

    @Test
    void writesDeletesOnlyWhenAskedTo() throws Exception {
        String source = database("CREATE TABLE orders(id BIGINT PRIMARY KEY, amount DECIMAL(12,2));");
        String target = database("""
                CREATE TABLE orders(id BIGINT PRIMARY KEY, amount DECIMAL(12,2));
                INSERT INTO orders VALUES (9, 9.00);
                """);
        DataDiffService service = service(source, target, mock(AuditRepository.class));

        assertThat(service.compare(request(false), "admin").script()).isEmpty();
        assertThat(service.compare(request(true), "admin").script())
                .containsExactly("DELETE FROM \"PUBLIC\".\"ORDERS\" WHERE \"ID\" = '9';");
    }

    /** UPDATE 的值来自源端 —— 对比的方向是「把目标端对齐到源端」。 */
    @Test
    void updatesTargetTowardsTheSourceValue() throws Exception {
        String source = database("""
                CREATE TABLE orders(id BIGINT PRIMARY KEY, note VARCHAR(50));
                INSERT INTO orders VALUES (1, '源端的值');
                """);
        String target = database("""
                CREATE TABLE orders(id BIGINT PRIMARY KEY, note VARCHAR(50));
                INSERT INTO orders VALUES (1, '目标端的值');
                """);
        DataDiffService service = service(source, target, mock(AuditRepository.class));

        assertThat(service.compare(request(false), "admin").script())
                .containsExactly("UPDATE \"PUBLIC\".\"ORDERS\" SET \"NOTE\" = '源端的值' WHERE \"ID\" = '1';");
    }

    /** 只在一侧存在的字段不参与对比，但要说出来 —— 那往往正是用户想知道的事。 */
    @Test
    void warnsAboutColumnsThatExistOnOnlyOneSide() throws Exception {
        String source = database("""
                CREATE TABLE orders(id BIGINT PRIMARY KEY, note VARCHAR(50), created_at TIMESTAMP);
                INSERT INTO orders VALUES (1, 'x', CURRENT_TIMESTAMP);
                """);
        String target = database("""
                CREATE TABLE orders(id BIGINT PRIMARY KEY, note VARCHAR(50), archived BOOLEAN);
                INSERT INTO orders VALUES (1, 'x', FALSE);
                """);
        DataDiffService service = service(source, target, mock(AuditRepository.class));

        DataDiffResponse response = service.compare(request(false), "admin");

        // 主键列同样参与对比：它对匹配上的行不会不同，但它是表的一部分，同步脚本要靠它插回去。
        assertThat(response.columns()).containsExactly("ID", "NOTE");
        assertThat(response.warnings()).anySatisfy(text -> assertThat(text).contains("CREATED_AT"));
        assertThat(response.warnings()).anySatisfy(text -> assertThat(text).contains("ARCHIVED"));
        assertThat(response.summary().identical()).isEqualTo(1);
    }

    /** 二进制列按文本读出来不可靠，而且一行几 MB 会让行数上限失去意义。 */
    @Test
    void skipsBinaryColumnsAndSaysSo() throws Exception {
        String ddl = """
                CREATE TABLE files(id BIGINT PRIMARY KEY, name VARCHAR(50), payload BLOB);
                INSERT INTO files VALUES (1, 'a', X'0102');
                """;
        DataDiffService service = service(database(ddl), database(ddl), mock(AuditRepository.class));

        DataDiffResponse response = service.compare(new DataDiffRequest(
                1L, "PUBLIC", "files", 2L, "PUBLIC", null, List.of(), false), "admin");

        assertThat(response.columns()).containsExactly("ID", "NAME");
        assertThat(response.warnings()).anySatisfy(text -> assertThat(text).contains("PAYLOAD"));
    }

    @Test
    void refusesWhenThereIsNoKeyToMatchRowsBy() throws Exception {
        String ddl = "CREATE TABLE events(name VARCHAR(50));";
        DataDiffService service = service(database(ddl), database(ddl), mock(AuditRepository.class));

        assertThatThrownBy(() -> service.compare(new DataDiffRequest(
                1L, "PUBLIC", "events", 2L, "PUBLIC", null, List.of(), false), "admin"))
                .isInstanceOf(ApiProblemException.class)
                .satisfies(error -> assertThat(((ApiProblemException) error).code()).isEqualTo("DATA_DIFF_NO_KEY"));
    }

    /** 自选的匹配键不唯一时，比下去只会给出没有意义的结论，不如当场说清楚。 */
    @Test
    void refusesAKeyThatDoesNotIdentifyASingleRow() throws Exception {
        String ddl = """
                CREATE TABLE events(id BIGINT PRIMARY KEY, kind VARCHAR(20));
                INSERT INTO events VALUES (1, 'a'), (2, 'a');
                """;
        DataDiffService service = service(database(ddl), database(ddl), mock(AuditRepository.class));

        assertThatThrownBy(() -> service.compare(new DataDiffRequest(
                1L, "PUBLIC", "events", 2L, "PUBLIC", null, List.of("KIND"), false), "admin"))
                .isInstanceOf(ApiProblemException.class)
                .satisfies(error -> assertThat(((ApiProblemException) error).code())
                        .isEqualTo("DATA_DIFF_DUPLICATE_KEY"));
    }

    @Test
    void refusesToCompareATableWithItself() throws Exception {
        String url = database("CREATE TABLE orders(id BIGINT PRIMARY KEY);");
        DataDiffService service = service(url, url, mock(AuditRepository.class));

        assertThatThrownBy(() -> service.compare(new DataDiffRequest(
                1L, "PUBLIC", "orders", 1L, "PUBLIC", "orders", List.of(), false), "admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("同一张表");
    }

    private static DataDiffRequest request(boolean includeDeletes) {
        return new DataDiffRequest(1L, "PUBLIC", "orders", 2L, "PUBLIC", null, List.of(), includeDeletes);
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

    private static DataDiffService service(String sourceUrl, String targetUrl, AuditRepository audit) throws Exception {
        ConnectionService connections = mock(ConnectionService.class);
        when(connections.open(anyLong(), nullable(String.class)))
                .thenAnswer(invocation -> DriverManager.getConnection(
                        invocation.getArgument(0, Long.class) == 1L ? sourceUrl : targetUrl, "sa", ""));
        when(connections.open(anyLong()))
                .thenAnswer(invocation -> DriverManager.getConnection(
                        invocation.getArgument(0, Long.class) == 1L ? sourceUrl : targetUrl, "sa", ""));
        when(connections.require(1L)).thenReturn(new DbConnection(
                1L, "source", "h2", sourceUrl, "sa", "", "dev", false, Instant.now(), Instant.now()));
        when(connections.require(2L)).thenReturn(new DbConnection(
                2L, "target", "h2", targetUrl, "sa", "", "dev", false, Instant.now(), Instant.now()));
        MetadataService metadata = new MetadataService(
                connections, new DialectRegistry(), mock(AuditRepository.class), new MetadataCacheService(), new ExecutionGuard());
        return new DataDiffService(connections, metadata, new DialectRegistry(), audit, new AppProperties());
    }
}
