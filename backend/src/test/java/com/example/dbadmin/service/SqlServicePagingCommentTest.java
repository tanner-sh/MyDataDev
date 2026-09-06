package com.example.dbadmin.service;

import com.example.dbadmin.config.AppProperties;
import com.example.dbadmin.core.DialectRegistry;
import com.example.dbadmin.dto.ApiDtos.SqlResult;
import com.example.dbadmin.model.DbConnection;
import com.example.dbadmin.repo.AuditRepository;
import com.example.dbadmin.repo.SqlHistoryRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 结尾停在一行注释里的 SQL 照样要能翻页。
 *
 * <p>这曾经是一个静默故障：分页子句被方言追加到原文末尾，而原文最后一行是注释时，
 * {@code LIMIT … OFFSET …} 会整段落进注释里。它不报错也不多返回行（{@code setMaxRows} 还在），
 * 只是每一页都返回第一页 —— 所以下面第一条断言看的是「第 2 页的内容」，不是「有没有抛异常」。</p>
 */
class SqlServicePagingCommentTest {
    private SqlService fixture() throws Exception {
        String url = "jdbc:h2:mem:paging-comment-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        new JdbcTemplate(new DriverManagerDataSource(url, "sa", "")).execute("""
                CREATE TABLE nums(id INT PRIMARY KEY);
                INSERT INTO nums SELECT X FROM SYSTEM_RANGE(1, 20);
                """);
        DbConnection model = new DbConnection(1L, "h2", "h2", url, "sa", "", "dev", false, Instant.now(), Instant.now());
        ConnectionService connections = mock(ConnectionService.class);
        when(connections.require(anyLong())).thenReturn(model);
        when(connections.open(anyLong())).thenAnswer(_i -> DriverManager.getConnection(url, "sa", ""));
        when(connections.open(anyLong(), any())).thenAnswer(_i -> DriverManager.getConnection(url, "sa", ""));

        DialectRegistry dialects = new DialectRegistry();
        MetadataCacheService cache = new MetadataCacheService();
        AuditRepository audit = mock(AuditRepository.class);
        MetadataService metadata = new MetadataService(connections, dialects, audit, cache, new ExecutionGuard());
        AppProperties properties = new AppProperties();
        CryptoService crypto = new CryptoService("test-key-test-key-test-key-32byt");
        DataEditService dataEdit = new DataEditService(
                metadata, connections, audit, dialects, properties,
                new TableCursorCodec(new ObjectMapper(), crypto),
                new RowLocatorCodec(new ObjectMapper(), crypto),
                new ExecutionGuard()
        );
        return new SqlService(
                connections, properties, audit, dialects, mock(SqlHistoryRepository.class),
                metadata, new SqlScriptSplitter(), new SqlStatementClassifier(), new ExecutionGuard(),
                new SqlExecutionRegistry(), dataEdit,
                new SqlExecutionMetrics()
        );
    }

    private static final String SQL_ENDING_IN_A_COMMENT = "SELECT id FROM nums ORDER BY id -- 只看这张表";

    @Test
    void pagesPastTheFirstPageWhenTheStatementEndsInALineComment() throws Exception {
        SqlResult second = fixture()
                .executePage(1L, SQL_ENDING_IN_A_COMMENT, 5, 5, "admin", null, null, null);

        assertThat(second.rows()).hasSize(5);
        assertThat(second.rows().stream().map(row -> row.get(0).toString()).toList())
                .containsExactly("6", "7", "8", "9", "10");
    }

    @Test
    void sortsAStatementThatEndsInALineCommentWithoutASyntaxError() throws Exception {
        SqlResult sorted = fixture()
                .executePage(1L, SQL_ENDING_IN_A_COMMENT, 0, 3, "admin", null, null, null, "ID", "DESC");

        assertThat(sorted.rows().stream().map(row -> row.get(0).toString()).toList())
                .containsExactly("20", "19", "18");
    }

    @Test
    void filtersAStatementThatEndsInALineCommentWithoutASyntaxError() throws Exception {
        assertThatCode(() -> fixture().executePage(
                1L, SQL_ENDING_IN_A_COMMENT, 0, 10, "admin", null, null, null, null, null,
                List.of(new com.example.dbadmin.dto.ApiDtos.SqlResultFilter("ID", "contains", "1"))
        )).doesNotThrowAnyException();
    }
}
