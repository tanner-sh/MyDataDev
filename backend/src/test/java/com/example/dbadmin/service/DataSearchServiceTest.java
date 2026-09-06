package com.example.dbadmin.service;

import com.example.dbadmin.api.ApiProblemException;
import com.example.dbadmin.config.AppProperties;
import com.example.dbadmin.core.DialectRegistry;
import com.example.dbadmin.core.H2Dialect;
import com.example.dbadmin.dto.ApiDtos.ColumnInfo;
import com.example.dbadmin.dto.ApiDtos.DataSearchRequest;
import com.example.dbadmin.dto.ApiDtos.DataSearchResponse;
import com.example.dbadmin.dto.ApiDtos.DataSearchTableHit;
import com.example.dbadmin.dto.ApiDtos.DbObject;
import com.example.dbadmin.dto.ApiDtos.MetadataResponse;
import com.example.dbadmin.dto.ApiDtos.ObjectDetail;
import com.example.dbadmin.model.DbConnection;
import com.example.dbadmin.repo.AuditRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 全库数据检索。
 *
 * <p>用真的 H2 跑查询：这些用例校验的是「命中判定和数据库那边的比较是不是同一件事」，而那
 * 正好是 mock 会假设掉的部分 —— 数字列上搜 "42" 能不能命中，取决于 CAST 之后的文本长什么样。</p>
 */
class DataSearchServiceTest {
    private String url;
    private DbConnection dbConnection = connection("dev");
    private MetadataService metadata;
    private AuditRepository audit;
    private DataSearchService service;

    private static DbConnection connection(String environment) {
        return new DbConnection(1L, "主库", "h2", "jdbc:h2:mem:unused", "sa", "", environment, false,
                Instant.now(), Instant.now());
    }

    private static ColumnInfo column(String name, String type) {
        return new ColumnInfo(name, type, 100, true, null, 1, null);
    }

    @BeforeEach
    void setUp() throws Exception {
        url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE customers(id INT, name VARCHAR(50), phone VARCHAR(30))");
            statement.execute("INSERT INTO customers VALUES (1, '张三', '13800001111'), (2, '李四', '13900002222')");
            statement.execute("CREATE TABLE orders(id INT, note VARCHAR(80), amount INT)");
            statement.execute("INSERT INTO orders VALUES (1, '联系 13800001111 回访', 42), (2, '无关', 7)");
            statement.execute("CREATE TABLE payloads(id INT, blob_col VARBINARY(64))");
            statement.execute("INSERT INTO payloads VALUES (1, X'0102')");
        }

        ConnectionService connections = mock(ConnectionService.class);
        when(connections.require(anyLong())).thenAnswer(_i -> dbConnection);
        when(connections.open(anyLong())).thenAnswer(_i -> DriverManager.getConnection(url, "sa", ""));
        when(connections.open(anyLong(), anyString())).thenAnswer(_i -> DriverManager.getConnection(url, "sa", ""));

        DialectRegistry dialects = mock(DialectRegistry.class);
        when(dialects.dialectFor(any())).thenReturn(new H2Dialect());

        metadata = mock(MetadataService.class);
        when(metadata.inspect(anyLong(), any(), any(), anyInt(), anyInt(), anyBoolean())).thenReturn(catalog(List.of(
                new DbObject(null, "customers", "TABLE", List.of(), List.of()),
                new DbObject(null, "orders", "TABLE", List.of(), List.of()),
                new DbObject(null, "payloads", "TABLE", List.of(), List.of()),
                new DbObject(null, "customer_view", "VIEW", List.of(), List.of())
        )));
        when(metadata.detail(anyLong(), any(), eq("customers"))).thenReturn(new ObjectDetail(
                null, "customers", "TABLE",
                List.of(column("id", "INTEGER"), column("name", "VARCHAR"), column("phone", "VARCHAR")),
                List.of(), List.of("id"), "PK"));
        when(metadata.detail(anyLong(), any(), eq("orders"))).thenReturn(new ObjectDetail(
                null, "orders", "TABLE",
                List.of(column("id", "INTEGER"), column("note", "VARCHAR"), column("amount", "INTEGER")),
                List.of(), List.of("id"), "PK"));
        when(metadata.detail(anyLong(), any(), eq("payloads"))).thenReturn(new ObjectDetail(
                null, "payloads", "TABLE",
                List.of(column("id", "INTEGER"), column("blob_col", "VARBINARY")),
                List.of(), List.of("id"), "PK"));

        audit = mock(AuditRepository.class);
        service = new DataSearchService(connections, dialects, metadata, new ExecutionGuard(),
                new AppProperties(), audit);
    }

    private static MetadataResponse catalog(List<DbObject> objects) {
        return new MetadataResponse(List.of("public"), "public", "public", "SCHEMA", objects,
                objects.size(), true, 0, 500, false, Instant.now().toString(), false);
    }

    private DataSearchResponse search(String keyword) throws Exception {
        return search(new DataSearchRequest(1L, "public", keyword, null, null, null, null), null);
    }

    private DataSearchResponse search(DataSearchRequest request, String confirmation) throws Exception {
        return service.search(request, "tanner", confirmation);
    }

    private static DataSearchTableHit table(DataSearchResponse response, String name) {
        return response.tables().stream().filter(hit -> hit.tableName().equals(name)).findFirst().orElseThrow();
    }

    /** 一个值散落在两张表的不同列里，这正是这个功能存在的理由。 */
    @Test
    void findsAValueAcrossTablesAndNamesTheColumnsThatMatched() throws Exception {
        DataSearchResponse response = search("13800001111");

        assertThat(response.matchedTables()).isEqualTo(2);
        assertThat(table(response, "customers").columns()).extracting("column").containsExactly("phone");
        assertThat(table(response, "orders").columns()).extracting("column").containsExactly("note");
        assertThat(table(response, "customers").columns().get(0).sample()).isEqualTo("13800001111");
    }

    /** 转成文本再比，于是数字列、时间列、布尔列共用同一套「包含」语义。 */
    @Test
    void matchesNonTextColumnsThroughTheSharedTextRule() throws Exception {
        DataSearchResponse response = search("42");

        assertThat(table(response, "orders").columns()).extracting("column").containsExactly("amount");
    }

    /** 视图的数据来自底下的表，扫了只会把同一行报两遍。 */
    @Test
    void skipsViewsSoRowsAreNotReportedTwice() throws Exception {
        search("13800001111");

        verify(metadata, org.mockito.Mockito.never()).detail(anyLong(), any(), eq("customer_view"));
    }

    /** 二进制列按文本读不可靠，跳过并写进 warnings —— 悄悄跳过等于让人以为搜过了。 */
    @Test
    void skipsBinaryColumnsAndSaysSoInWarnings() throws Exception {
        DataSearchResponse response = search("0102");

        assertThat(response.tables()).extracting("tableName").doesNotContain("payloads");
        assertThat(response.warnings()).anySatisfy(warning ->
                assertThat(warning).contains("payloads").contains("blob_col"));
    }

    /** 返回的查询要能直接跑，且跑出来的行与检索报告的一致 —— 否则点进去会看到对不上的结果。 */
    @Test
    void returnsAQueryThatReproducesTheHit() throws Exception {
        String sql = table(search("13800001111"), "customers").sql();

        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             var statement = connection.createStatement();
             var rs = statement.executeQuery(sql)) {
            List<String> phones = new ArrayList<>();
            while (rs.next()) phones.add(rs.getString("phone"));
            assertThat(phones).containsExactly("13800001111");
        }
    }

    /** 精确匹配不该把「联系 13800001111 回访」也算进来。 */
    @Test
    void exactModeOnlyMatchesWholeValues() throws Exception {
        DataSearchResponse response = search(
                new DataSearchRequest(1L, "public", "13800001111", "EQUALS", null, null, null), null);

        assertThat(response.tables()).extracting("tableName").containsExactly("customers");
    }

    /** 用户输入的 % 是要找的字符本身，不是通配符 —— 与结果表格的筛选同一个语义。 */
    @Test
    void treatsWildcardCharactersInTheKeywordAsLiterals() throws Exception {
        assertThat(search("%").matchedTables()).isZero();
    }

    /**
     * 扫描没跑完时必须说出来。
     *
     * <p>把「扫了 2 张表就到上限了」报告成「全库只有这些」，会让人放心地得出错误结论 ——
     * 这比没有这个功能更糟。</p>
     */
    @Test
    void reportsWhyTheScanStoppedInsteadOfClaimingCompleteness() throws Exception {
        DataSearchResponse response = search(
                new DataSearchRequest(1L, "public", "13800001111", null, 1, null, null), null);

        assertThat(response.complete()).isFalse();
        assertThat(response.stopReason()).contains("1");
        assertThat(response.scannedTables()).isEqualTo(1);
    }

    @Test
    void completeScansSaySoWithNoStopReason() throws Exception {
        DataSearchResponse response = search("13800001111");

        assertThat(response.complete()).isTrue();
        assertThat(response.stopReason()).isNull();
        assertThat(response.scannedTables()).isEqualTo(3);
    }

    /** 全库扫描是一次实打实的负载事件，和「打开一张表看看」不是一回事。 */
    @Test
    void requiresProductionConfirmation() {
        dbConnection = connection("prod");

        assertThatThrownBy(() -> search("13800001111"))
                .isInstanceOf(ApiProblemException.class)
                .hasFieldOrPropertyWithValue("code", "PRODUCTION_CONFIRMATION_REQUIRED");
    }

    @Test
    void auditsTheScanAgainstTheConnection() throws Exception {
        search("13800001111");

        verify(audit).onConnection(eq("tanner"), eq("DATA_SEARCH"), eq(1L), anyString());
    }

    @Test
    void rejectsBlankKeywordsRatherThanScanningEverything() {
        assertThatThrownBy(() -> search("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("检索");
    }
}
