package com.example.dbadmin.service;

import com.example.dbadmin.api.ApiProblemException;
import com.example.dbadmin.config.AppProperties;
import com.example.dbadmin.core.DialectRegistry;
import com.example.dbadmin.core.H2Dialect;
import com.example.dbadmin.dto.ApiDtos.ColumnInfo;
import com.example.dbadmin.dto.ApiDtos.DataTransferRequest;
import com.example.dbadmin.dto.ApiDtos.ObjectDetail;
import com.example.dbadmin.model.DbConnection;
import com.example.dbadmin.repo.AuditRepository;
import com.example.dbadmin.repo.SqlHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.StringWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 跨连接传输的准备阶段。
 *
 * <p>用真的 H2 当源库，把 {@link SqlFileExecutionService#uploadScript} 换成一个只捕获脚本写入
 * 回调的替身 —— 于是这些用例既覆盖了「读源库到生成脚本」这一整段，又不必拉起后台队列。</p>
 */
class DataTransferServiceTest {
    private static final String SOURCE_TABLE = "orders";

    private String sourceUrl;
    private ConnectionService connections;
    private MetadataService metadata;
    private DataEditService dataEdit;
    private SqlFileExecutionService sqlFiles;
    private AuditRepository audit;
    private SqlHistoryRepository history;
    private DataTransferService service;

    private DbConnection source = connection(1L, "主库", "dev", false);
    private DbConnection target = connection(2L, "分析库", "dev", false);

    private static DbConnection connection(long id, String name, String environment, boolean readonly) {
        return new DbConnection(id, name, "h2", "jdbc:h2:mem:unused", "sa", "", environment, readonly,
                Instant.now(), Instant.now());
    }

    @BeforeEach
    void setUp() throws Exception {
        sourceUrl = "jdbc:h2:mem:" + UUID.randomUUID() + ";DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(sourceUrl, "sa", "")) {
            connection.createStatement().execute(
                    "CREATE TABLE orders(id INT PRIMARY KEY, name VARCHAR(40), note VARCHAR(80))");
            connection.createStatement().execute("INSERT INTO orders VALUES (1, 'Alice', ''), (2, 'Bob', NULL)");
        }

        connections = mock(ConnectionService.class);
        when(connections.require(1L)).thenAnswer(_i -> source);
        when(connections.require(2L)).thenAnswer(_i -> target);
        when(connections.open(anyLong())).thenAnswer(_i -> DriverManager.getConnection(sourceUrl, "sa", ""));
        when(connections.open(anyLong(), anyString()))
                .thenAnswer(_i -> DriverManager.getConnection(sourceUrl, "sa", ""));

        DialectRegistry dialects = mock(DialectRegistry.class);
        when(dialects.dialectFor(any())).thenReturn(new H2Dialect());

        metadata = mock(MetadataService.class);
        when(metadata.detail(eq(1L), any(), eq(SOURCE_TABLE))).thenReturn(new ObjectDetail(
                null, SOURCE_TABLE, "TABLE",
                List.of(new ColumnInfo("id", "INT", 10, false, null, 1, null),
                        new ColumnInfo("name", "VARCHAR", 40, true, null, 2, null),
                        new ColumnInfo("note", "VARCHAR", 80, true, null, 3, null)),
                List.of(), List.of("id"), "PK"));
        when(metadata.detail(eq(2L), any(), anyString())).thenReturn(new ObjectDetail(
                null, "orders_copy", "TABLE", List.of(), List.of(), List.of("id"), "PK"));

        dataEdit = mock(DataEditService.class);
        when(dataEdit.editableColumns(any(), any(), any(), anyString()))
                .thenReturn(Set.of("id", "name", "note"));

        sqlFiles = mock(SqlFileExecutionService.class);
        audit = mock(AuditRepository.class);
        history = mock(SqlHistoryRepository.class);

        ExportService exportService = new ExportService(connections, dialects, new AppProperties(),
                new com.fasterxml.jackson.databind.ObjectMapper(), new SqlStatementClassifier(),
                new SqlScriptSplitter(), audit, history, new ExecutionGuard());

        service = new DataTransferService(connections, dialects, metadata, dataEdit, sqlFiles,
                new ExecutionGuard(), exportService, new AppProperties(), audit, history);
    }

    private DataTransferRequest request(String sourceSql, String sourceTable) {
        return new DataTransferRequest(1L, null, sourceTable, sourceSql, 2L, null, "orders_copy", "INSERT");
    }

    /** 捕获 uploadScript 的写入回调并运行它，拿到真正生成的脚本。 */
    private String generatedScript(DataTransferRequest request) throws Exception {
        service.prepare(request, "tanner", null);
        ArgumentCaptor<SqlFileExecutionService.ScriptWriter> writer =
                ArgumentCaptor.forClass(SqlFileExecutionService.ScriptWriter.class);
        verify(sqlFiles).uploadScript(eq(2L), anyString(), anyLong(), writer.capture(),
                eq("tanner"), eq("DATA_TRANSFER_PREPARE"), anyString());
        StringWriter out = new StringWriter();
        writer.getValue().write(out);
        return out.toString();
    }

    /** 给了源表就按元数据解析出的规范名生成全表查询，不拿用户输入去拼带引号的 SQL。 */
    @Test
    void transfersAWholeTableAndKeepsEmptyStringsIntact() throws Exception {
        String script = generatedScript(request(null, SOURCE_TABLE));

        assertThat(script).contains("INSERT INTO \"orders_copy\"");
        // 第一行的 note 是空串，第二行是 NULL —— 两者在脚本里必须长得不一样。
        assertThat(script).contains("('1', 'Alice', '')");
        assertThat(script).contains("('2', 'Bob', NULL)");
    }

    /** 自由 SQL 走导出那条「只能是单条查询」的规则，写操作在准备阶段就被挡住。 */
    @Test
    void rejectsSourceStatementsThatAreNotASingleQuery() {
        assertThatThrownBy(() -> service.prepare(request("DELETE FROM orders", null), "tanner", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("单条查询");
    }

    /**
     * 目标连接只读时要在读源库之前就拒绝。
     *
     * <p>顺序有实际代价：源表几百万行读完、脚本落完盘才发现对面不许写，那趟活全白干，而且
     * 生产数据已经在磁盘上躺了一遍。</p>
     */
    @Test
    void refusesReadonlyTargetsBeforeTouchingTheSource() throws Exception {
        target = connection(2L, "分析库", "dev", true);

        assertThatThrownBy(() -> service.prepare(request(null, SOURCE_TABLE), "tanner", null))
                .isInstanceOf(ApiProblemException.class)
                .hasMessageContaining("只读");
        verify(connections, never()).open(anyLong());
        verify(sqlFiles, never()).uploadScript(anyLong(), anyString(), anyLong(), any(), any(), any(), any());
    }

    /**
     * 源端是生产连接时要回连接名确认。
     *
     * <p>传输和导出一样是把数据带出这条连接，不能因为终点是另一个数据库而不是一个下载就少一道门。</p>
     */
    @Test
    void requiresProductionConfirmationOnTheSourceConnection() {
        source = connection(1L, "生产库", "prod", false);

        assertThatThrownBy(() -> service.prepare(request(null, SOURCE_TABLE), "tanner", null))
                .isInstanceOf(ApiProblemException.class)
                .hasFieldOrPropertyWithValue("code", "PRODUCTION_CONFIRMATION_REQUIRED");
    }

    @Test
    void acceptsTheSourceProductionConfirmationWhenItMatchesTheConnectionName() throws Exception {
        source = connection(1L, "生产库", "prod", false);

        service.prepare(request(null, SOURCE_TABLE), "tanner", "生产库");

        verify(sqlFiles).uploadScript(eq(2L), anyString(), anyLong(), any(), eq("tanner"),
                eq("DATA_TRANSFER_PREPARE"), anyString());
    }

    /** 源端也要留下审计：按源连接筛选时「这批数据什么时候被谁搬走的」必须查得出来。 */
    @Test
    void auditsTheReadSideAgainstTheSourceConnection() throws Exception {
        service.prepare(request(null, SOURCE_TABLE), "tanner", null);

        verify(audit).onConnection(eq("tanner"), eq("DATA_TRANSFER_READ"), eq(1L), anyString());
        verify(history).insert(eq(1L), anyString(), eq("TRANSFER"), eq("SUCCESS"), anyLong(), any(), eq("tanner"));
    }

    @Test
    void requiresEitherASourceTableOrASourceQuery() {
        assertThatThrownBy(() -> service.prepare(request(null, null), "tanner", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("源表");
    }
}
